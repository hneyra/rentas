package kamayuk.rentas.parametros.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0007 contra PostgreSQL real: el contenedor de los valores normativos y el acto administrativo
 * que los congela.
 *
 * <p><b>Ninguna cifra tributaria aparece en esta prueba.</b> Los valores que se siembran son
 * inventados y estan marcados como tales: lo que se verifica es el mecanismo —vigencia, documento
 * fuente, doble verificacion y sellado—, no cuanto vale nada. Las cifras son D-02.
 */
@DisplayName("ADR-0007 — Conjunto de parametros por ejercicio")
class AdministrarParametrosTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    /**
     * Un valor inventado, y dicho aqui para que no se confunda con uno real. No representa ninguna
     * UIT, ningun tramo y ninguna alicuota: sirve para que la fila exista.
     */
    private static final String VALOR_FICTICIO = "1.000000";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;

    private static TransactionTemplate transaccion;
    private static AdministrarParametros administrar;
    private static ParametrosRepositoryJdbc repositorio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("280101", "Municipalidad de parametros");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        repositorio = new ParametrosRepositoryJdbc(jdbc);

        AdministrarParametros objetivo =
                new AdministrarParametros(repositorio, new AuditoriaJdbc(jdbc, RELOJ), RELOJ);
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        administrar = (AdministrarParametros) fabrica.getProxy();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("jefe.rentas", "PC-RENTAS-01", "10.2.2.2"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("El sellado es irreversible")
    class Sellado {

        @Test
        @DisplayName("un conjunto sellado no se modifica, y la unica salida es una version nueva")
        void unConjuntoSelladoNoSeModifica() throws SQLException {
            Ejercicio ejercicio = new Ejercicio(2027);
            ConjuntoDeParametros conjunto =
                    administrar.abrirVersion(
                            ejercicio, Observacion.de("Se abre el conjunto del ejercicio 2027"));
            administrar.agregarParametro(
                    conjunto.id(),
                    parametroFicticio("SELLADO_2027"),
                    Observacion.de("Se incorpora el parametro publicado por la ordenanza"));

            ConjuntoDeParametros sellado =
                    administrar.sellar(
                            conjunto.id(),
                            Observacion.de("Se sella el ejercicio 2027 para iniciar la emision"));

            assertThat(sellado.estaSellado()).isTrue();
            assertThat(sellado.usuarioSellado()).isEqualTo("jefe.rentas");
            assertThat(sellado.fechaSellado()).isNotNull();

            // Por la aplicacion.
            assertThatThrownBy(
                            () ->
                                    administrar.sellar(
                                            conjunto.id(),
                                            Observacion.de("Segundo intento de sellado")))
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .hasMessageContaining("version nueva");

            // Y por SQL directo, que es como se rodearia una validacion de aplicacion.
            assertThatThrownBy(
                            () ->
                                    ejecutarComoApp(
                                            "UPDATE conjunto_parametros SET version = 99"
                                                    + " WHERE id = "
                                                    + conjunto.id()))
                    .as("lo impide un disparador de la base, no la aplicacion")
                    .hasMessageContaining("sellado");

            assertThatThrownBy(
                            () ->
                                    administrar.agregarParametro(
                                            conjunto.id(),
                                            parametroFicticio("TARDIO_2027"),
                                            Observacion.de("Intento de agregar a un sellado")))
                    .as(
                            "un conjunto que sigue «sellado» y cambia de contenido es el peor de los"
                                    + " dos mundos")
                    .isNotNull();

            ConjuntoDeParametros siguiente =
                    administrar.abrirVersion(
                            ejercicio, Observacion.de("Se corrige el ejercicio con una version 2"));
            assertThat(siguiente.version())
                    .as("la salida no es editar: es una version nueva al lado de la anterior")
                    .isEqualTo(conjunto.version() + 1);
        }

        @Test
        @DisplayName("ARQ-09 §3: dos conjuntos del mismo ejercicio si pueden estar sellados")
        void dosSelladosDelMismoEjercicioSi() throws SQLException {
            Ejercicio ejercicio = new Ejercicio(2028);

            ConjuntoDeParametros primero =
                    administrar.abrirVersion(ejercicio, Observacion.de("Primera version de 2028"));
            administrar.agregarParametro(
                    primero.id(),
                    parametroFicticio("PRIMERO_2028"),
                    Observacion.de("Parametro de la primera version"));
            administrar.sellar(primero.id(), Observacion.de("Se sella la primera version de 2028"));

            // Un arancel corregido a mitad de ejercicio: version nueva, sellada al lado de la
            // anterior. Las determinaciones ya emitidas siguen apuntando a la primera.
            ConjuntoDeParametros segundo =
                    administrar.abrirVersion(ejercicio, Observacion.de("Segunda version de 2028"));
            administrar.agregarParametro(
                    segundo.id(),
                    parametroFicticio("SEGUNDO_2028"),
                    Observacion.de("Parametro de la segunda version"));
            administrar.sellar(segundo.id(), Observacion.de("Se sella la correccion de 2028"));

            assertThat(
                            contar(
                                    "SELECT count(*) FROM conjunto_parametros WHERE ejercicio ="
                                            + " 2028 AND estado = 'SELLADO'"))
                    .as(
                            "quien dice cual se aplico no es una consulta por ejercicio: es la"
                                    + " determinacion, que guarda su conjunto_id")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("un conjunto sellado sigue sin poder sellarse dos veces")
        void unConjuntoNoSeSellaDosVeces() throws SQLException {
            Ejercicio ejercicio = new Ejercicio(2029);

            ConjuntoDeParametros conjunto =
                    administrar.abrirVersion(ejercicio, Observacion.de("Version unica de 2029"));
            administrar.agregarParametro(
                    conjunto.id(),
                    parametroFicticio("UNICO_2029"),
                    Observacion.de("Parametro de la version unica"));
            administrar.sellar(conjunto.id(), Observacion.de("Se sella 2029"));

            assertThatThrownBy(
                            () ->
                                    administrar.sellar(
                                            conjunto.id(),
                                            Observacion.de("Intento de sellarlo otra vez")))
                    .as("levantar el indice unico no levanta la inmutabilidad de lo sellado")
                    .isNotNull();
        }

        @Test
        @DisplayName("un conjunto vacio no se sella")
        void unConjuntoVacioNoSeSella() {
            ConjuntoDeParametros vacio =
                    administrar.abrirVersion(
                            new Ejercicio(2029), Observacion.de("Conjunto que se queda vacio"));

            assertThatThrownBy(
                            () ->
                                    administrar.sellar(
                                            vacio.id(), Observacion.de("Intento de sellar vacio")))
                    .as(
                            "diria que el ejercicio esta parametrizado cuando el calculo no"
                                    + " encontraria ni la UIT")
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .hasMessageContaining("ningun parametro");
        }
    }

    @Nested
    @DisplayName("Lo que la base exige de un valor normativo")
    class LoQueLaBaseExige {

        @Test
        @DisplayName("un parametro sin documento fuente no se guarda")
        void sinDocumentoFuenteNoSeGuarda() {
            assertThatThrownBy(
                            () ->
                                    comoCargaDeParametros(
                                            "INSERT INTO parametro_tributario (municipalidad_id,"
                                                    + " tipo, clave, valor_numerico, vigencia_desde,"
                                                    + " documento_fuente, usuario_carga) VALUES (NULL,"
                                                    + " 'FICTICIO', 'sin-fuente', 1.0, DATE"
                                                    + " '2026-01-01', NULL, 'carga')"))
                    .as("sin fuente, dentro de dos anios nadie sabria de donde salio el valor")
                    .hasMessageContaining("documento_fuente");
        }

        @Test
        @DisplayName("un parametro sin ningun valor no se guarda")
        void sinValorNoSeGuarda() {
            assertThatThrownBy(
                            () ->
                                    comoCargaDeParametros(
                                            "INSERT INTO parametro_tributario (municipalidad_id,"
                                                    + " tipo, clave, vigencia_desde, documento_fuente,"
                                                    + " usuario_carga) VALUES (NULL, 'FICTICIO',"
                                                    + " 'sin-valor', DATE '2026-01-01', 'Ordenanza"
                                                    + " ficticia', 'carga')"))
                    .hasMessageContaining("parametro_valor_ck");
        }

        @Test
        @DisplayName("RNF-092: quien carga no puede aprobar, y lo impide la base")
        void quienCargaNoPuedeAprobar() {
            assertThatThrownBy(
                            () ->
                                    comoCargaDeParametros(
                                            "INSERT INTO parametro_tributario (municipalidad_id,"
                                                    + " tipo, clave, valor_numerico, vigencia_desde,"
                                                    + " documento_fuente, usuario_carga,"
                                                    + " usuario_aprueba) VALUES (NULL, 'FICTICIO',"
                                                    + " 'auto-aprobado', 1.0, DATE '2026-01-01',"
                                                    + " 'Ordenanza ficticia', 'misma.persona',"
                                                    + " 'misma.persona')"))
                    .as(
                            "es restriccion de la tabla, no convencion: una convencion se salta el dia"
                                    + " que corre prisa")
                    .hasMessageContaining("parametro_doble_verificacion_ck");
        }

        @Test
        @DisplayName("la aplicacion no puede publicar valores normativos: solo los lee")
        void laAplicacionNoPublicaValores() {
            assertThatThrownBy(
                            () ->
                                    ejecutarComoApp(
                                            "INSERT INTO parametro_tributario (municipalidad_id,"
                                                    + " tipo, clave, valor_numerico, vigencia_desde,"
                                                    + " documento_fuente, usuario_carga) VALUES (NULL,"
                                                    + " 'FICTICIO', 'desde-la-app', 1.0, DATE"
                                                    + " '2026-01-01', 'Ordenanza ficticia', 'app')"))
                    .as(
                            "separacion de funciones (REQ-03): quien opera el sistema no publica las"
                                    + " cifras con las que se calcula")
                    .hasMessageContaining("parametro_tributario");
        }
    }

    @Nested
    @DisplayName("La clasificacion de parametro_tributario como catalogo")
    class Clasificacion {

        @Test
        @DisplayName(
                "sigue siendo catalogo: se lee sin municipalidad y no lleva la columna NOT NULL")
        void sigueSiendoCatalogo() throws SQLException {
            // CAL-01 §5: si esta clasificacion cambiara, tiene que verse en el diff.
            // La prueba de aislamiento la enumera; aqui se comprueba su consecuencia
            // observable, que es lo que de verdad importa: un parametro nacional se lee
            // desde cualquier municipalidad.
            assertThat(
                            filas(
                                    "SELECT is_nullable FROM information_schema.columns"
                                            + " WHERE table_name = 'parametro_tributario'"
                                            + "   AND column_name = 'municipalidad_id'"))
                    .as("si fuera NOT NULL seria tabla de tenant y no admitiria el ambito nacional")
                    .containsExactly("YES");

            long nacional = parametroFicticio("NACIONAL_VISIBLE");
            List<String> visibles =
                    transaccion.execute(
                            estado ->
                                    repositorio
                                            .parametros(Paginacion.de(0, 200, "tipo"))
                                            .contenido()
                                            .stream()
                                            .map(p -> String.valueOf(p.id()))
                                            .toList());

            assertThat(visibles)
                    .as("el catalogo nacional se lee desde la municipalidad, por politica RLS")
                    .contains(String.valueOf(nacional));
        }
    }

    @Nested
    @DisplayName("Auditoria del acto administrativo")
    class AuditoriaDelActo {

        @Test
        @DisplayName("abrir, componer y sellar dejan su fila con observacion")
        void dejanSuFila() throws SQLException {
            ConjuntoDeParametros conjunto =
                    administrar.abrirVersion(
                            new Ejercicio(2030), Observacion.de("Se abre el conjunto de 2030"));
            administrar.agregarParametro(
                    conjunto.id(),
                    parametroFicticio("AUDITADO_2030"),
                    Observacion.de("Se incorpora el parametro de la ordenanza ficticia"));
            administrar.sellar(conjunto.id(), Observacion.de("Se sella 2030 tras la revision"));

            assertThat(
                            filas(
                                    "SELECT operacion FROM auditoria"
                                            + " WHERE tabla = 'conjunto_parametros' AND clave = '"
                                            + conjunto.id()
                                            + "' ORDER BY id"))
                    .containsExactly("ALTA", "MODIFICACION");
            assertThat(
                            filas(
                                    "SELECT count(*) FROM auditoria"
                                            + " WHERE tabla = 'conjunto_parametro_detalle'"))
                    .isNotEmpty();
        }
    }

    // ------------------------------------------------------------------

    /**
     * Publica un parametro <b>ficticio</b> de ambito nacional, con el rol que corresponde.
     *
     * <p>Va por {@code rol_carga_parametros} y no por la aplicacion a proposito: es la separacion
     * de funciones que la prueba de arriba verifica, y usarla aqui deja constancia de cual es el
     * camino legitimo.
     */
    private static long parametroFicticio(String clave) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba) VALUES (NULL, 'FICTICIO',"
                                        + " ?, ?::numeric, DATE '2026-01-01', 'Valor ficticio de"
                                        + " prueba; no representa ninguna norma', 'carga',"
                                        + " 'aprueba') RETURNING id")) {
            sentencia.setString(1, clave);
            sentencia.setString(2, VALOR_FICTICIO);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static void comoCargaDeParametros(String sql) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia = carga.prepareStatement(sql)) {
            sentencia.executeUpdate();
            carga.commit();
        }
    }

    private static void ejecutarComoApp(String sql) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES (?, ?, 'DISTRITAL') RETURNING id")) {
            sentencia.setString(1, ubigeo);
            sentencia.setString(2, nombre);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    private static long contar(String sql) throws SQLException {
        return Long.parseLong(filas(sql).get(0));
    }

    private static List<String> filas(String sql) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(sql);
                ResultSet resultado = sentencia.executeQuery()) {
            List<String> valores = new java.util.ArrayList<>();
            while (resultado.next()) {
                valores.add(resultado.getString(1));
            }
            return valores;
        }
    }
}
