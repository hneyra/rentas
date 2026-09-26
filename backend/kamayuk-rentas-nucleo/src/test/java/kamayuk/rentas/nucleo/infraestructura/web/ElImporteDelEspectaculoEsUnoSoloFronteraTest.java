package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.aplicacion.DirectorioJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.FichaRepositoryJdbc;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.aplicacion.RegistrarEspectaculo;
import kamayuk.rentas.nucleo.infraestructura.DeterminacionRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.EspectaculoPublicoRepositoryJdbc;
import kamayuk.rentas.nucleo.parametros.DerivadoPublicado;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #378 — El impuesto de un espectaculo es <b>una sola cifra</b>: la del 201, la de la auditoria y
 * la de {@code determinacion.monto_determinado}, de HTTP a PostgreSQL.
 *
 * <h2>Lo que habia</h2>
 *
 * <p>{@code ImpuestoDeEspectaculo} devolvia {@code ingreso × alicuota} sin redondear, porque su
 * javadoc daba D-03 por abierta cuando ADR-0018 de {@code normativa} ya la habia cerrado: «se
 * redondea al cierre de cada regla, a centimo (escala 2), {@code HALF_UP}». La columna es {@code
 * dinero numeric(15,2)} sin {@code CHECK} de escala, asi que PostgreSQL <b>coacciona</b> al guardar
 * y no da error; y el repositorio devolvia el objeto en memoria, no la fila. Con un ingreso de 12
 * 345,67 al 10 %, el 201 y la auditoria decian {@code 1234.567} y la fila {@code 1234.57}.
 *
 * <h2>Por que estas siembras, y no la de siempre</h2>
 *
 * <p>Un ingreso redondo —9 000 × 10 %— da 900 con o sin redondeo, y con cualquier modo: no
 * distingue nada. Aqui van dos que si:
 *
 * <ul>
 *   <li><b>Tres decimales</b> (12 345,67 → 1 234,567): exige que las tres cifras coincidan. Sin
 *       redondear, el 201 dice una y la fila otra.
 *   <li><b>Medio centimo exacto</b> (12 345,65 → 1 234,565): {@code HALF_UP} da 1 234,57 y {@code
 *       HALF_EVEN} 1 234,56. Sellando cada uno, la cifra tiene que seguir al modo <b>sellado</b>;
 *       la coaccion de la columna redondea siempre igual, asi que un redondeo que no saliera del
 *       conjunto se delata en el segundo.
 * </ul>
 *
 * <p>Y el punto sin politica: un conjunto que observa otros puntos y no {@code
 * IMPUESTO_ESPECTACULO} no determina con la cifra sin redondear, contesta 422 nombrando la fila que
 * falta publicar, antes de escribir nada.
 */
@DisplayName(
        "#378 — El impuesto de un espectaculo es la misma cifra en el 201, la auditoria y la fila")
class ElImporteDelEspectaculoEsUnoSoloFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long organizador;
    private static JdbcClient jdbc;
    private static PlatformTransactionManager gestor;

    private final List<RegistroDeAuditoria> auditados = new ArrayList<>();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("242203", "Municipalidad del centimo");
        organizador = crearOrganizador();

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void contexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("cajero.ventanilla", "PC-07", "10.0.0.7"));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("12 345,67 al 10 %: el 201, la auditoria y la fila dicen 1234.57")
    void tresDecimales() throws Exception {
        MvcResult resultado =
                montar(conRedondeo(RoundingMode.HALF_UP))
                        .perform(registrar("12345.67"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("cuerpo: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        assertLasTresCifras(resultado, "1234.57");
    }

    @Test
    @DisplayName("12 345,65 al 10 % con HALF_UP sellado: el medio centimo sube, 1234.57")
    void medioCentimoConHalfUp() throws Exception {
        MvcResult resultado =
                montar(conRedondeo(RoundingMode.HALF_UP))
                        .perform(registrar("12345.65"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("cuerpo: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        assertLasTresCifras(resultado, "1234.57");
    }

    @Test
    @DisplayName(
            "12 345,65 al 10 % con HALF_EVEN sellado: manda el conjunto, no la columna, 1234.56")
    void medioCentimoConHalfEven() throws Exception {
        MvcResult resultado =
                montar(conRedondeo(RoundingMode.HALF_EVEN))
                        .perform(registrar("12345.65"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("cuerpo: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        assertLasTresCifras(resultado, "1234.56");
    }

    @Test
    @DisplayName(
            "sin REDONDEO:IMPUESTO_ESPECTACULO no determina: 422 nombrando la fila, sin escribir")
    void sinLaPoliticaDelPunto() throws Exception {
        long eventosAntes = contar("espectaculo");
        long determinacionesAntes = contar("determinacion");
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ListAppender<ILoggingEvent> anotados = new ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        MvcResult resultado;
        try {
            // Un conjunto que SI observa puntos —el de la alcabala— y no el de este tributo: el
            // tercer estado de PoliticasDeRedondeoSelladas, el que tiene que nombrar la fila.
            resultado =
                    montar(
                                    DerivadoPublicado.conjuntoDelEjercicioConRedondeo(
                                            EJERCICIO,
                                            RoundingMode.HALF_UP,
                                            PuntoDeRedondeo.IMPUESTO_ALCABALA))
                            .perform(registrar("12345.67"))
                            .andReturn();
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(resultado.getResponse().getStatus())
                .as("cuerpo: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,"
                                + "\"llave\":\"REDONDEO:IMPUESTO_ESPECTACULO\"}");
        assertThat(anotados.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList())
                .as("falta publicar una fila: no es una incidencia del servidor")
                .isEmpty();
        assertThat(contar("espectaculo")).as("ni el evento").isEqualTo(eventosAntes);
        assertThat(contar("determinacion"))
                .as("ni la determinacion")
                .isEqualTo(determinacionesAntes);
        assertThat(auditados).isEmpty();
    }

    // ------------------------------------------------------------------

    /**
     * La respuesta, la auditoria y la fila dicen la cifra esperada, <b>con su escala</b>: {@code
     * 1234.57} y no {@code 1234.570}, porque es la misma cadena la que se muestra y se concilia.
     */
    private void assertLasTresCifras(MvcResult resultado, String esperada) throws Exception {
        JsonNode cuerpo = JSON.readTree(resultado.getResponse().getContentAsString());
        long id = cuerpo.get("id").asLong();
        String respuesta = cuerpo.get("montoDeterminado").asString();

        assertThat(auditados).as("un alta, una auditoria").hasSize(1);
        String auditada =
                JSON.readTree(auditados.get(0).datosNuevos()).get("montoDeterminado").asString();

        String guardada = montoGuardado(id).toPlainString();

        assertThat(List.of(respuesta, auditada, guardada))
                .as("201, auditoria y SELECT monto_determinado de la determinacion %d", id)
                .containsExactly(esperada, esperada, esperada);
    }

    private MockMvc montar(LectorDeParametros parametros) {
        RegistrarEspectaculo servicio =
                envolver(
                        new RegistrarEspectaculo(
                                new EspectaculoPublicoRepositoryJdbc(jdbc),
                                new DeterminacionRepositoryJdbc(jdbc),
                                parametros,
                                envolver(
                                        new DirectorioJdbc(
                                                new ContribuyenteRepositoryJdbc(jdbc),
                                                new FichaRepositoryJdbc(jdbc)),
                                        gestor),
                                auditados::add),
                        gestor);
        return MockMvcBuilders.standaloneSetup(new EspectaculoController(servicio, RELOJ))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    private static LectorDeParametros conRedondeo(RoundingMode modo) {
        return DerivadoPublicado.conjuntoDelEjercicioConRedondeo(
                EJERCICIO, modo, PuntoDeRedondeo.IMPUESTO_ESPECTACULO);
    }

    private static org.springframework.test.web.servlet.RequestBuilder registrar(String ingreso) {
        return post("/rentas/api/v1/rentas/espectaculos")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        "{\"organizadorId\":"
                                + organizador
                                + ",\"denominacion\":\"FUNCION DE ESTRENO\","
                                + "\"tipo\":\"CINEMATOGRAFICO\",\"lugar\":\"CINE CENTRAL\","
                                + "\"fechaEvento\":\"2026-09-12\",\"aforo\":300,"
                                + "\"ingresoDeclarado\":\""
                                + ingreso
                                + "\",\"observacion\":\"Registro del evento presentado en mesa"
                                + " de partes\"}");
    }

    private static BigDecimal montoGuardado(long id) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT monto_determinado FROM determinacion WHERE id = ?")) {
            sentencia.setLong(1, id);
            try (ResultSet fila = sentencia.executeQuery()) {
                assertThat(fila.next()).as("la determinacion %d esta en la base", id).isTrue();
                return fila.getBigDecimal(1);
            }
        }
    }

    private static long contar(String tabla) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT count(*) FROM " + tabla + " WHERE municipalidad_id = ?")) {
            sentencia.setLong(1, municipalidad);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                return fila.getLong(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, PlatformTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
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

    private static long crearOrganizador() throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, 'C-CENT-1', 'RUC', '20606060601', 'JURIDICA',"
                                    + " 'ORGANIZADOR DEL CENTIMO', 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }
}
