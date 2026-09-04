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
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * La unica puerta de los demas contextos a los valores normativos, contra PostgreSQL real.
 *
 * <p>Los valores sembrados son <b>ficticios</b> y estan marcados como tales en su propio documento
 * fuente. Lo que se verifica es que solo salga lo sellado.
 */
@DisplayName("ADR-0007 — Lectura del conjunto sellado")
class LectorDeParametrosSelladosTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static AdministrarParametros administrar;
    private static LectorDeParametros lector;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("290101", "Municipalidad del lector");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        ParametrosRepositoryJdbc repositorio = new ParametrosRepositoryJdbc(jdbc);

        administrar =
                envolver(
                        new AdministrarParametros(
                                repositorio, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
        lector = envolver(new LectorDeParametrosSellados(repositorio), gestor);
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
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("pedir un ejercicio sin conjunto sellado falla")
    void sinSelladoFalla() {
        assertThatThrownBy(() -> lector.vigenteEn(new Ejercicio(2035)))
                .as(
                        "calcular con un conjunto abierto produce una cifra que manana puede ser otra,"
                                + " y el contribuyente ya tendria el recibo")
                .isInstanceOf(LectorDeParametros.EjercicioSinSellar.class)
                .hasMessageContaining("2035");
    }

    @Test
    @DisplayName("un conjunto abierto tampoco cuenta, aunque tenga sus parametros")
    void unConjuntoAbiertoTampocoCuenta() throws SQLException {
        Ejercicio ejercicio = new Ejercicio(2036);
        ConjuntoDeParametros abierto =
                administrar.abrirVersion(ejercicio, Observacion.de("Se prepara el ejercicio 2036"));
        administrar.agregarParametro(
                abierto.id(),
                parametroFicticio("ABIERTO_2036"),
                Observacion.de("Se incorpora un parametro mientras se prepara"));

        assertThatThrownBy(() -> lector.vigenteEn(ejercicio))
                .as("tener parametros no es estar sellado")
                .isInstanceOf(LectorDeParametros.EjercicioSinSellar.class);
    }

    @Test
    @DisplayName("el sellado se entrega como objeto inmutable, con su ejercicio y su version")
    void elSelladoSeEntrega() throws SQLException {
        Ejercicio ejercicio = new Ejercicio(2037);
        ConjuntoDeParametros conjunto =
                administrar.abrirVersion(ejercicio, Observacion.de("Se abre el ejercicio 2037"));
        administrar.agregarParametro(
                conjunto.id(),
                parametroFicticio("SELLADO_2037"),
                Observacion.de("Se incorpora el parametro de la ordenanza ficticia"));
        administrar.sellar(conjunto.id(), Observacion.de("Se sella 2037 tras la revision"));

        ParametrosSellados sellados = lector.vigenteEn(ejercicio);

        assertThat(sellados.ejercicio()).isEqualTo(ejercicio);
        assertThat(sellados.version()).isEqualTo(conjunto.version());
        assertThat(sellados.numero("FICTICIO", "SELLADO_2037"))
                .as("el valor sale del conjunto, no de ninguna constante del codigo")
                .isPresent();
        assertThat(sellados.numero("FICTICIO", "no-existe")).isEmpty();
    }

    @Test
    @DisplayName("ARQ-09 §3: dos versiones selladas del mismo ejercicio conviven")
    void dosVersionesSelladasConviven() throws SQLException {
        Ejercicio ejercicio = new Ejercicio(2038);

        ConjuntoDeParametros primera =
                administrar.abrirVersion(ejercicio, Observacion.de("Se abre 2038 por primera vez"));
        administrar.agregarParametro(
                primera.id(),
                parametroFicticio("ARANCEL_FICTICIO_V1"),
                Observacion.de("Se incorpora el arancel ficticio inicial"));
        administrar.sellar(primera.id(), Observacion.de("Se sella 2038 para emitir"));

        // A mitad de ejercicio se corrige el arancel: version nueva, no correccion de la anterior.
        ConjuntoDeParametros segunda =
                administrar.abrirVersion(
                        ejercicio, Observacion.de("Se corrige el arancel ficticio a mitad de ano"));
        administrar.agregarParametro(
                segunda.id(),
                parametroFicticio("ARANCEL_FICTICIO_V2"),
                Observacion.de("Se incorpora el arancel ficticio corregido"));
        administrar.sellar(segunda.id(), Observacion.de("Se sella la correccion de 2038"));

        assertThat(segunda.version())
                .as("la correccion es una version nueva, no una edicion de la anterior")
                .isGreaterThan(primera.version());

        ParametrosSellados vigente = lector.vigenteEn(ejercicio);
        assertThat(vigente.version())
                .as("una determinacion nueva usa la ultima version sellada")
                .isEqualTo(segunda.version());
        assertThat(vigente.numero("FICTICIO", "ARANCEL_FICTICIO_V2")).isPresent();

        ParametrosSellados original = lector.porConjunto(IdentificadorDeConjunto.de(primera.id()));
        assertThat(original.version())
                .as(
                        "recalcular la determinacion emitida en junio recupera SU conjunto, no el"
                                + " que rige hoy: si no, la cifra cambiaria sin ningun error")
                .isEqualTo(primera.version());
        assertThat(original.numero("FICTICIO", "ARANCEL_FICTICIO_V1")).isPresent();
        assertThat(original.numero("FICTICIO", "ARANCEL_FICTICIO_V2"))
                .as("el conjunto original no conoce la correccion posterior")
                .isEmpty();
    }

    @Test
    @DisplayName("un conjunto abierto no se recupera ni por su identificador")
    void unConjuntoAbiertoNoSeRecuperaPorId() throws SQLException {
        ConjuntoDeParametros abierto =
                administrar.abrirVersion(
                        new Ejercicio(2039), Observacion.de("Se prepara el ejercicio 2039"));

        assertThatThrownBy(() -> lector.porConjunto(IdentificadorDeConjunto.de(abierto.id())))
                .as(
                        "que una determinacion apunte a un conjunto abierto significa que se emitio"
                                + " sin sellar: no se calcula sobre eso, se investiga")
                .isInstanceOf(LectorDeParametros.ConjuntoNoSellado.class);
    }

    @Test
    @DisplayName("un conjunto que no existe no se sustituye por el vigente del ejercicio")
    void unConjuntoInexistenteFalla() {
        assertThatThrownBy(() -> lector.porConjunto(IdentificadorDeConjunto.de(999_999L)))
                .as("sustituirlo daria una cifra plausible y equivocada")
                .isInstanceOf(LectorDeParametros.ConjuntoNoSellado.class);
    }

    @Test
    @DisplayName("#659 — con las cinco UIT dentro, 2026 se resuelve con la de 2026")
    void laUitQueRigeElEjercicio() throws SQLException {
        Ejercicio ejercicio = new Ejercicio(2040);
        ConjuntoDeParametros conjunto =
                administrar.abrirVersion(ejercicio, Observacion.de("Se abre el ejercicio 2040"));
        // El historico entero, como lo publica parametros-2026.csv: una fila por ejercicio.
        // Se siembra en un orden que NO es el que se espera leer, porque el defecto de #659 se
        // colaba precisamente por el orden: `ORDER BY p.tipo, p.clave` empata en las cinco.
        for (int anio : new int[] {2038, 2041, 2040, 2039, 2037}) {
            administrar.agregarParametro(
                    conjunto.id(),
                    parametroConVigencia(
                            "UIT_FICTICIA", null, anio * 100L, anio + "-01-01", anio + "-12-31"),
                    Observacion.de("Se incorpora la UIT ficticia de " + anio));
        }
        administrar.sellar(conjunto.id(), Observacion.de("Se sella 2040 con el historico dentro"));

        ParametrosSellados sellados = lector.vigenteEn(ejercicio);

        assertThat(sellados.numero("UIT_FICTICIA", null))
                .as(
                        "hasta #659 sobrevivia una cualquiera de las cinco y el ejercicio se"
                                + " determinaba con la de otro año: 234,00 donde deben ser 180,00")
                .contains(
                        new kamayuk.rentas.dominio.ValorNormativo(
                                new java.math.BigDecimal("204000.000000")));
    }

    @Test
    @DisplayName("#659 — se resuelve con el ejercicio del conjunto, no con el reloj (regla 6)")
    void seResuelveConElEjercicioDelConjunto() throws SQLException {
        // El mismo historico, sellado para DOS ejercicios distintos. Si la resolucion mirara el
        // reloj —que en esta prueba esta fijado en 2026— los dos conjuntos darian la misma cifra,
        // y recalcular una determinacion vieja daria hoy otro importe.
        long[] cifras = new long[2];
        int[] ejercicios = {2042, 2043};
        for (int i = 0; i < ejercicios.length; i++) {
            Ejercicio ejercicio = new Ejercicio(ejercicios[i]);
            ConjuntoDeParametros conjunto =
                    administrar.abrirVersion(
                            ejercicio, Observacion.de("Se abre el ejercicio " + ejercicio));
            for (int anio : ejercicios) {
                administrar.agregarParametro(
                        conjunto.id(),
                        parametroConVigencia(
                                "UIT_POR_EJERCICIO",
                                null,
                                anio * 100L,
                                anio + "-01-01",
                                anio + "-12-31"),
                        Observacion.de("Se incorpora la UIT ficticia de " + anio));
            }
            administrar.sellar(conjunto.id(), Observacion.de("Se sella " + ejercicio));
            cifras[i] =
                    lector.vigenteEn(ejercicio)
                            .exigirNumero("UIT_POR_EJERCICIO", null)
                            .valor()
                            .longValue();
        }

        assertThat(cifras[0]).isEqualTo(204_200L);
        assertThat(cifras[1])
                .as("cada conjunto resuelve con SU ejercicio: recalcular 2042 en 2043 no lo mueve")
                .isEqualTo(204_300L);
    }

    @Test
    @DisplayName("#659 — la llave con una sola vigencia se sigue resolviendo igual")
    void laLlaveConUnaSolaVigenciaSigueIgual() throws SQLException {
        Ejercicio ejercicio = new Ejercicio(2044);
        ConjuntoDeParametros conjunto =
                administrar.abrirVersion(ejercicio, Observacion.de("Se abre el ejercicio 2044"));
        // Vigencia abierta por arriba, que es la forma de casi todo el corpus: los tramos del
        // predial rigen «desde 2004-11-15» y sin fecha de fin.
        administrar.agregarParametro(
                conjunto.id(),
                parametroConVigencia("TRAMO_FICTICIO", "1", 20L, "2004-11-15", null),
                Observacion.de("Se incorpora el tramo ficticio"));
        administrar.sellar(conjunto.id(), Observacion.de("Se sella 2044"));

        assertThat(lector.vigenteEn(ejercicio).numero("TRAMO_FICTICIO", "1"))
                .as("filtrar por vigencia no puede dejar fuera lo que rige indefinidamente")
                .isPresent();
    }

    @Test
    @DisplayName("#659 — lo que rige PARTE del ejercicio sigue dentro: no se ancla al 1 de enero")
    void loQueRigeParteDelEjercicioSigueDentro() throws SQLException {
        Ejercicio ejercicio = new Ejercicio(2047);
        ConjuntoDeParametros conjunto =
                administrar.abrirVersion(ejercicio, Observacion.de("Se abre el ejercicio 2047"));
        // La forma de una campaña de beneficio: rige de marzo a junio, no el ejercicio entero
        // (D-02b, #72). Resolver contra el 1 de enero la dejaria fuera del conjunto sin que nada
        // lo dijera, que es el defecto de #659 con el signo cambiado.
        administrar.agregarParametro(
                conjunto.id(),
                parametroConVigencia(
                        "BENEFICIO_FICTICIO", "AMNISTIA", 50L, "2047-03-01", "2047-06-30"),
                Observacion.de("Se incorpora la campaña ficticia"));
        administrar.sellar(conjunto.id(), Observacion.de("Se sella 2047 con la campaña dentro"));

        assertThat(lector.vigenteEn(ejercicio).numero("BENEFICIO_FICTICIO", "AMNISTIA"))
                .as("la campaña es del ejercicio 2047 aunque no lo cubra entero")
                .isPresent();
    }

    @Test
    @DisplayName("#659 — la llave cuyas vigencias no alcanzan el ejercicio falta, y se dice")
    void laLlaveQueNoAlcanzaElEjercicioFalta() throws SQLException {
        Ejercicio ejercicio = new Ejercicio(2045);
        ConjuntoDeParametros conjunto =
                administrar.abrirVersion(ejercicio, Observacion.de("Se abre el ejercicio 2045"));
        administrar.agregarParametro(
                conjunto.id(),
                parametroConVigencia("CADUCADO_FICTICIO", null, 7L, "2020-01-01", "2020-12-31"),
                Observacion.de("Se incorpora un valor que caduco en 2020"));
        administrar.sellar(conjunto.id(), Observacion.de("Se sella 2045"));

        assertThatThrownBy(
                        () -> lector.vigenteEn(ejercicio).exigirNumero("CADUCADO_FICTICIO", null))
                .as(
                        "un valor que caduco no es el valor del ejercicio: sustituirlo es el defecto"
                                + " de #659 con otra cara")
                .isInstanceOf(ParametrosSellados.ParametroAusente.class)
                .hasMessageContaining("CADUCADO_FICTICIO");
    }

    @Test
    @DisplayName("#659 — dos vigencias que se solapan en el ejercicio no se eligen: se dicen")
    void dosVigenciasQueSeSolapanNoSeEligen() throws SQLException {
        Ejercicio ejercicio = new Ejercicio(2046);
        ConjuntoDeParametros conjunto =
                administrar.abrirVersion(ejercicio, Observacion.de("Se abre el ejercicio 2046"));
        administrar.agregarParametro(
                conjunto.id(),
                parametroConVigencia("SOLAPADO_FICTICIO", null, 1L, "2046-01-01", "2046-06-30"),
                Observacion.de("Se incorpora la primera mitad"));
        administrar.agregarParametro(
                conjunto.id(),
                parametroConVigencia("SOLAPADO_FICTICIO", null, 2L, "2046-04-01", "2046-12-31"),
                Observacion.de("Se incorpora la segunda, que pisa a la primera"));
        administrar.sellar(conjunto.id(), Observacion.de("Se sella 2046 con la contradiccion"));

        assertThatThrownBy(() -> lector.vigenteEn(ejercicio))
                .as("elegir una de las dos en silencio es exactamente lo que #659 cerro")
                .isInstanceOf(LectorDeParametrosSellados.VigenciasQueSeSolapan.class)
                .hasMessageContaining("SOLAPADO_FICTICIO")
                .hasMessageContaining("2046-04-01");
    }

    // ------------------------------------------------------------------

    /**
     * Publica un parametro <b>ficticio</b> con el rol que corresponde: la aplicacion no publica.
     */
    private static long parametroFicticio(String clave) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba) VALUES (NULL, 'FICTICIO',"
                                        + " ?, 1.000000, DATE '2026-01-01', 'Valor ficticio de prueba;"
                                        + " no representa ninguna norma', 'carga', 'aprueba')"
                                        + " RETURNING id")) {
            sentencia.setString(1, clave);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    /**
     * Publica un parametro <b>ficticio</b> con tipo, clave, valor y vigencia propios (#659).
     *
     * <p>Los tipos llevan {@code FICTICIA}/{@code FICTICIO} en el nombre a proposito: son valores
     * inventados y ninguna regla los pide, de modo que sembrarlos no puede parecerse a cargar una
     * cifra normativa sin su corpus (regla 5).
     */
    private static long parametroConVigencia(
            String tipo, String clave, long valor, String desde, String hasta) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, vigencia_hasta,"
                                        + " documento_fuente, usuario_carga, usuario_aprueba)"
                                        + " VALUES (NULL, ?, ?, ?, CAST(? AS date),"
                                        + " CAST(? AS date), 'Valor ficticio de prueba; no"
                                        + " representa ninguna norma', 'carga', 'aprueba')"
                                        + " RETURNING id")) {
            sentencia.setString(1, tipo);
            sentencia.setString(2, clave);
            sentencia.setLong(3, valor);
            sentencia.setString(4, desde);
            sentencia.setString(5, hasta);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
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
}
