package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.dominio.CategoriasConstructivas;
import kamayuk.rentas.catastro.dominio.Construccion;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.OrigenDeLaFicha;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.infraestructura.FichaCatastralRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
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
 * La ficha catastral y su versionado, contra PostgreSQL real.
 *
 * <p>Todo lo que sigue defiende una sola frase del manual: <b>modificar una ficha no
 * sobrescribe</b> (cap. 2 §Actualizacion del Catastro). En 2029, cuando alguien discuta una
 * determinacion de 2027, la ficha que regia en 2027 tiene que seguir ahi entera.
 */
@DisplayName("RF-001/007 — Ficha catastral y versionado")
class ActualizarFichaCatastralTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;

    private static TransactionTemplate transaccion;
    private static FichaCatastralRepositoryJdbc repositorio;
    private static ActualizarFichaCatastral fichas;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240101", "Municipalidad de la ficha catastral");
        otraMunicipalidad = crearMunicipalidad("240102", "Municipalidad vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        repositorio = new FichaCatastralRepositoryJdbc(jdbc);
        fichas =
                envolver(
                        new ActualizarFichaCatastral(
                                repositorio, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
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
        OrigenContext.fijar(new Origen("catastro.tecnico", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("El versionado que nunca sobrescribe")
    class Versionado {

        @Test
        @DisplayName("modificar deja DOS filas: la anterior cerrada y la nueva vigente")
        void modificarDejaDosFilas() throws SQLException {
            long predio = crearPredio("24010100100100101010001", "AV. VERSIONADA 100");

            fichas.registrarPrimera(
                    primera(predio, "120.00", "CASA HABITACION"),
                    Observacion.de("Alta de la ficha por declaracion jurada"));

            fichas.actualizar(
                    predio,
                    TipoFicha.UNICA,
                    LocalDate.of(2026, 7, 1),
                    OrigenDeLaFicha.FISCALIZACION,
                    "Acta de fiscalizacion 045-2026",
                    null,
                    null,
                    null,
                    Observacion.de("Se verifica en campo un area mayor a la declarada"));

            List<FichaCatastral> historial =
                    transaccion.execute(estado -> repositorio.historial(predio, TipoFicha.UNICA));

            assertThat(historial).isNotNull().hasSize(2);
            assertThat(historial.get(0).version()).isEqualTo(2);
            assertThat(historial.get(0).estaVigente()).isTrue();
            assertThat(historial.get(1).version()).isEqualTo(1);
            assertThat(historial.get(1).vigenciaHasta())
                    .as("la anterior se cierra el dia antes; ninguna fecha tiene dos fichas")
                    .isEqualTo(LocalDate.of(2026, 6, 30));
            assertThat(historial.get(1).uso())
                    .as("y sus datos siguen intactos: cero UPDATE sobre la version anterior")
                    .isEqualTo("CASA HABITACION");
        }

        @Test
        @DisplayName("se puede reconstruir el estado de la ficha a una fecha pasada")
        void seReconstruyeElPasado() throws SQLException {
            long predio = crearPredio("24010100100100101010002", "AV. RECONSTRUIDA 200");

            fichas.registrarPrimera(
                    primera(predio, "100.00", "TERRENO SIN CONSTRUIR"),
                    Observacion.de("Alta del predio como terreno"));

            fichas.actualizar(
                    predio,
                    TipoFicha.UNICA,
                    LocalDate.of(2026, 9, 1),
                    OrigenDeLaFicha.DECLARACION_JURADA,
                    "Declaracion jurada 090-2026",
                    null,
                    null,
                    null,
                    Observacion.de("El contribuyente declara la construccion terminada"));

            Optional<FichaCatastral> enMarzo =
                    fichas.vigenteA(predio, TipoFicha.UNICA, LocalDate.of(2026, 3, 15));
            Optional<FichaCatastral> enOctubre =
                    fichas.vigenteA(predio, TipoFicha.UNICA, LocalDate.of(2026, 10, 15));

            assertThat(enMarzo).isPresent();
            assertThat(enMarzo.get().version())
                    .as(
                            "una determinacion de marzo se calculo sobre la version 1, y defenderla"
                                    + " exige poder recuperarla")
                    .isEqualTo(1);
            assertThat(enOctubre).isPresent();
            assertThat(enOctubre.get().version()).isEqualTo(2);
        }

        @Test
        @DisplayName("versionar copia las construcciones: la nueva no nace vacia")
        void versionarCopiaLasConstrucciones() throws SQLException {
            long predio = crearPredio("24010100100100101010003", "AV. CON PISOS 300");

            FichaCatastral conPisos =
                    primera(predio, "150.00", "CASA HABITACION")
                            .con(
                                    List.of(
                                            Construccion.en(
                                                    "1",
                                                    new AreaM2(new BigDecimal("80.00")),
                                                    CategoriasConstructivas.todas('C')),
                                            Construccion.en(
                                                    "2",
                                                    new AreaM2(new BigDecimal("60.00")),
                                                    CategoriasConstructivas.todas('D'))));

            fichas.registrarPrimera(conPisos, Observacion.de("Alta con dos pisos declarados"));

            FichaCatastral segunda =
                    fichas.actualizar(
                            predio,
                            TipoFicha.UNICA,
                            LocalDate.of(2026, 7, 1),
                            OrigenDeLaFicha.RESOLUCION,
                            "Resolucion de gerencia 010-2026",
                            null,
                            null,
                            null,
                            Observacion.de("Cambio de uso por resolucion, sin tocar la fabrica"));

            assertThat(segunda.construcciones())
                    .as(
                            "sin copiar, la version nueva nacería vacia: seria borrar lo declarado"
                                    + " sin que ningun DELETE apareciera en el diff")
                    .hasSize(2);

            Optional<FichaCatastral> anterior =
                    fichas.vigenteA(predio, TipoFicha.UNICA, LocalDate.of(2026, 3, 1));
            assertThat(anterior).isPresent();
            assertThat(anterior.get().construcciones())
                    .as("y la anterior conserva las suyas")
                    .hasSize(2);
        }

        @Test
        @DisplayName("una version cerrada ya no se puede versionar")
        void unaVersionCerradaNoSeVersiona() throws SQLException {
            long predio = crearPredio("24010100100100101010004", "AV. CERRADA 400");

            FichaCatastral primera =
                    fichas.registrarPrimera(
                            primera(predio, "90.00", "COMERCIO"),
                            Observacion.de("Alta de la ficha del local"));

            FichaCatastral cerrada = primera.cerradaEl(LocalDate.of(2026, 6, 30));

            assertThatThrownBy(
                            () ->
                                    cerrada.siguienteVersion(
                                            LocalDate.of(2026, 7, 1),
                                            OrigenDeLaFicha.DECLARACION_JURADA,
                                            "Documento",
                                            Observacion.de("Intento de ramificar el historial")))
                    .as("versionar una cerrada ramificaria el historial en dos lineas")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("un predio no puede tener dos primeras fichas")
        void noHayDosPrimeras() throws SQLException {
            long predio = crearPredio("24010100100100101010005", "AV. UNICA 500");

            fichas.registrarPrimera(
                    primera(predio, "70.00", "CASA HABITACION"),
                    Observacion.de("Alta de la ficha"));

            assertThatThrownBy(
                            () ->
                                    fichas.registrarPrimera(
                                            primera(predio, "80.00", "CASA HABITACION"),
                                            Observacion.de("Segundo intento de primera ficha")))
                    .isInstanceOf(ActualizarFichaCatastral.YaTieneFicha.class);
        }

        @Test
        @DisplayName("no se versiona lo que no existe")
        void noSeVersionaLoQueNoExiste() throws SQLException {
            long predio = crearPredio("24010100100100101010006", "AV. SIN FICHA 600");

            assertThatThrownBy(
                            () ->
                                    fichas.actualizar(
                                            predio,
                                            TipoFicha.UNICA,
                                            LocalDate.of(2026, 7, 1),
                                            OrigenDeLaFicha.DECLARACION_JURADA,
                                            "Documento",
                                            null,
                                            null,
                                            null,
                                            Observacion.de("Actualizar una ficha inexistente")))
                    .isInstanceOf(ActualizarFichaCatastral.SinFichaVigente.class);
        }
    }

    @Nested
    @DisplayName("Lo que la ficha guarda y lo que no")
    class LoQueGuarda {

        @Test
        @DisplayName("la construccion guarda CATEGORIAS, no importes (regla 5)")
        void laConstruccionGuardaCategorias() throws SQLException {
            long predio = crearPredio("24010100100100101010101", "AV. CATEGORIAS 100");

            fichas.registrarPrimera(
                    primera(predio, "200.00", "CASA HABITACION")
                            .con(
                                    List.of(
                                            Construccion.en(
                                                    "1",
                                                    new AreaM2(new BigDecimal("120.00")),
                                                    new CategoriasConstructivas(
                                                            'A', 'B', 'C', 'D', 'E', 'F', 'G')))),
                    Observacion.de("Alta con las siete categorias declaradas"));

            Optional<FichaCatastral> leida =
                    fichas.vigenteA(predio, TipoFicha.UNICA, LocalDate.of(2026, 6, 1));

            assertThat(leida).isPresent();
            Construccion construccion = leida.get().construcciones().get(0);
            assertThat(construccion.categorias().muros()).isEqualTo('A');
            assertThat(construccion.categorias().instalaciones()).isEqualTo('G');
            assertThat(construccion.categorias().declaradas())
                    .as(
                            "cuanto vale cada categoria es D-02a y vive en datos versionados; la"
                                    + " ficha guarda a que fila del cuadro pertenece, no el importe")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("una categoria fuera de la A-J se rechaza")
        void unaCategoriaFueraDeRango() {
            assertThatThrownBy(() -> CategoriasConstructivas.todas('Z'))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("A");
        }

        @Test
        @DisplayName("y la J de la Selva SI se admite: el rango llegaba hasta la I y la norma no")
        void laDecimaCategoriaDeLaSelva() {
            // #436: el cuadro de la Costa —el unico transcrito hasta entonces— tiene nueve
            // categorias, y de ahi salio el rango. El Anexo I.4, para la Selva, tiene diez:
            // la J es «CAÑA GUAYAQUIL PONA O PINTOC». Con A..I, una municipalidad de la
            // Selva no podia fichar una construccion de ese material — y eso no esperaba a
            // que se cargara ningun cuadro.
            assertThat(CategoriasConstructivas.todas('J').muros())
                    .as("la decima categoria del Anexo I.4, que el rango A..I rechazaba")
                    .isEqualTo('J');
        }

        @Test
        @DisplayName("sin observacion no se construye una version")
        void sinObservacionNoSeGuarda() {
            assertThatThrownBy(() -> Observacion.de("   "))
                    .as("regla 10: sin observacion no se guarda, y se comprueba en el tipo")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("la version queda auditada, con su observacion")
        void laVersionQuedaAuditada() throws SQLException {
            long predio = crearPredio("24010100100100101010102", "AV. AUDITADA 200");

            fichas.registrarPrimera(
                    primera(predio, "110.00", "CASA HABITACION"),
                    Observacion.de("Alta para comprobar el rastro de auditoria de la ficha"));

            Long filas =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT count(*) FROM auditoria"
                                                            + " WHERE tabla = 'ficha_catastral'"
                                                            + "   AND observacion LIKE '%rastro de"
                                                            + " auditoria de la ficha%'")
                                            .query(Long.class)
                                            .single());

            assertThat(filas).isNotNull().isPositive();
        }
    }

    @Nested
    @DisplayName("Aislamiento")
    class Aislamiento {

        @Test
        @DisplayName("la ficha de un predio de A no es legible con contexto de B")
        void laFichaDeANoSeLeeDesdeB() throws SQLException {
            long predio = crearPredio("24010100100100101010201", "AV. AISLADA 100");

            fichas.registrarPrimera(
                    primera(predio, "130.00", "CASA HABITACION"),
                    Observacion.de("Alta de la ficha para la prueba de aislamiento"));

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));

            Optional<FichaCatastral> desdeLaVecina =
                    fichas.vigenteA(predio, TipoFicha.UNICA, LocalDate.of(2026, 6, 1));

            assertThat(desdeLaVecina)
                    .as("el catastro de un distrito no se lee desde el vecino")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------

    private static FichaCatastral primera(long predioId, String area, String uso) {
        return FichaCatastral.primera(
                predioId,
                TipoFicha.UNICA,
                new AreaM2(new BigDecimal(area)),
                uso,
                LocalDate.of(2026, 1, 1),
                OrigenDeLaFicha.DECLARACION_JURADA,
                "Declaracion jurada 001-2026",
                Observacion.de("Version inicial de la ficha del predio"));
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

    private static long crearPredio(String codigo, String direccion) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion) VALUES (?, ?, 'URBANO', ?) RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, direccion);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }
}
