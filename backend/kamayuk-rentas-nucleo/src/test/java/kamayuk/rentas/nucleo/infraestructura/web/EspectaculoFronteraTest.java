package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.aplicacion.DirectorioJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.FichaRepositoryJdbc;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.nucleo.aplicacion.RegistrarEspectaculo;
import kamayuk.rentas.nucleo.infraestructura.DeterminacionRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.EspectaculoPublicoRepositoryJdbc;
import kamayuk.rentas.nucleo.parametros.DerivadoPublicado;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * #422 — {@code POST /rentas/espectaculos} con un organizador que no existe, de HTTP a PostgreSQL.
 *
 * <p>{@code EspectaculoControllerTest} mide el transporte contra dobles, y un doble del repositorio
 * no tiene claves foraneas: ahi el defecto se veia como un 201 y un evento de nadie. Contra la base
 * el mismo cuerpo era un 500 con incidencia ERROR, porque {@code espectaculo_contribuyente_fk}
 * rechazaba el {@code INSERT} —que ademas era lo primero que hacia el caso de uso, antes de leer
 * los parametros—. Esta prueba mide lo que veia el cliente.
 *
 * <p>Lo que no es de la frontera se dobla: el conjunto sellado, que es el que {@code normativa}
 * publica ({@link DerivadoPublicado}) y no uno sembrado a mano. Hasta #376 este doble sembraba
 * {@code ALICUOTA_ESPECTACULO:CINE} y el cuerpo decia {@code "tipo":"cine"}: una llave que nadie
 * publica y un tipo que no es del articulo 57 —con el codigo de #376 ese cuerpo es un 422 de
 * validacion antes de llegar al padron—. La conexion es la de {@code kamayuk_app}.
 */
@DisplayName("#422 — Un espectaculo de un organizador que no existe es 404, no 500")
class EspectaculoFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("242202", "Municipalidad de los espectaculos");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        PlatformTransactionManager gestor = new TenantTransactionManager(pool);

        RegistrarEspectaculo servicio =
                envolver(
                        new RegistrarEspectaculo(
                                new EspectaculoPublicoRepositoryJdbc(jdbc),
                                new DeterminacionRepositoryJdbc(jdbc),
                                DerivadoPublicado.conjuntoDelEjercicio(EJERCICIO),
                                envolver(
                                        new DirectorioJdbc(
                                                new ContribuyenteRepositoryJdbc(jdbc),
                                                new FichaRepositoryJdbc(jdbc)),
                                        gestor),
                                (RegistroDeAuditoria registro) -> {},
                                RELOJ),
                        gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(new EspectaculoController(servicio, RELOJ))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
                                new JacksonJsonHttpMessageConverter(
                                        JsonMapper.builder()
                                                .addModule(
                                                        new ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()))
                        .build();
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
    @DisplayName("un organizador que no esta en el padron es 404, sin incidencia y sin evento")
    void unOrganizadorInexistenteEs404() throws Exception {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ListAppender<ILoggingEvent> anotados = new ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        MvcResult resultado;
        try {
            resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/rentas/espectaculos")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"organizadorId\":999999,"
                                                            + "\"denominacion\":\"FUNCION DE"
                                                            + " ESTRENO\",\"tipo\":\"CINEMATOGRAFICO\","
                                                            + "\"lugar\":\"CINE CENTRAL\","
                                                            + "\"fechaEvento\":\"2026-09-12\","
                                                            + "\"aforo\":300,"
                                                            + "\"ingresoDeclarado\":\"9000.00\","
                                                            + "\"observacion\":\"Registro del"
                                                            + " evento presentado en mesa de"
                                                            + " partes\"}"))
                            .andReturn();
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(resultado.getResponse().getStatus())
                .as("espectaculo_contribuyente_fk")
                .isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("NO_ENCONTRADO")
                .contains("999999")
                .doesNotContain("espectaculo_contribuyente_fk")
                .doesNotContain("incidencia");
        assertThat(anotados.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList())
                .as("un rechazo del usuario no es una incidencia del servidor")
                .isEmpty();
        assertThat(espectaculos()).isZero();
    }

    // ------------------------------------------------------------------

    private static long espectaculos() throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT count(*) FROM espectaculo WHERE municipalidad_id = ?")) {
            sentencia.setLong(1, municipalidad);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                return resultado.getLong(1);
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
}
