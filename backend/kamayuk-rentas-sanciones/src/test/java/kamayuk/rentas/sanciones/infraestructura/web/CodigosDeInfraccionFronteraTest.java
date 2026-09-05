package kamayuk.rentas.sanciones.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.sanciones.aplicacion.ConsultasDeSanciones;
import kamayuk.rentas.sanciones.infraestructura.CodigoInfraccionRepositoryJdbc;
import kamayuk.rentas.sanciones.infraestructura.NotificacionAdministrativaRepositoryJdbc;
import kamayuk.rentas.sanciones.infraestructura.PapeletaRepositoryJdbc;
import kamayuk.rentas.sanciones.infraestructura.ProcedimientoSancionadorRepositoryJdbc;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Una de las nueve rutas de este modulo que corrian fuera de transaccion, cruzada entera (#486).
 *
 * <h2>Por que hace falta, teniendo la regla de arquitectura</h2>
 *
 * <p>{@code NINGUN_CONTROLADOR_SOSTIENE_UN_REPOSITORIO} impide que el defecto vuelva, y lo impide
 * en <b>todos</b> los controladores a la vez. Lo que no puede decir es que la ruta arreglada
 * <b>funcione</b>: una regla estatica comprueba la forma, no que la peticion llegue a PostgreSQL y
 * vuelva con filas. Las dos cosas hacen falta, y ninguna sustituye a la otra.
 *
 * <p>El proxy transaccional se construye con {@link AnnotationTransactionAttributeSource}, o sea
 * obedeciendo a la anotacion como el contenedor: quitarle el {@code @Transactional} a {@code
 * ConsultasDeSanciones.codigos} deja al proxy sin nada que hacer y esto se pone rojo con el error
 * de produccion exacto. Envolverlo en un {@code TransactionTemplate} incondicional habria hecho
 * pasar la prueba con la anotacion quitada.
 *
 * <p>La conexion es la de {@code kamayuk_app}: un superusuario omite RLS incluso con {@code FORCE
 * ROW LEVEL SECURITY}.
 */
@DisplayName("RF-060 — El catalogo de infracciones, de HTTP a PostgreSQL (#486)")
class CodigosDeInfraccionFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("260101", "Municipalidad de la frontera");
        municipalidadB = crearMunicipalidad("260102", "Municipalidad vecina");
        crearCodigo(municipalidadA, "M-01", "Conducir sin licencia");
        crearCodigo(municipalidadB, "M-99", "Codigo de la vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        ConsultasDeSanciones consulta =
                conLaTransaccionQueDiceLaAnotacion(
                        new ConsultasDeSanciones(
                                new PapeletaRepositoryJdbc(jdbc),
                                new CodigoInfraccionRepositoryJdbc(jdbc),
                                new ProcedimientoSancionadorRepositoryJdbc(jdbc),
                                new NotificacionAdministrativaRepositoryJdbc(jdbc)),
                        new TenantTransactionManager(pool));

        mvc =
                MockMvcBuilders.standaloneSetup(new CodigosTransitoController(consulta, RELOJ))
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
    void fijarTenant() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName("la peticion llega a PostgreSQL y vuelve con el catalogo de su municipalidad")
    void elCatalogoSeLee() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/transito/codigos")).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "con la consulta en el controlador, RLS falla con «invalid input syntax for"
                                + " type bigint: \"\"» y esto es 500")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("Conducir sin licencia");
    }

    @Test
    @DisplayName("y no trae el catalogo de la municipalidad vecina")
    void elAislamientoSeSostiene() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/transito/codigos")).andReturn();

        assertThat(resultado.getResponse().getContentAsString())
                .as("lo que las separa es RLS, no el criterio")
                .doesNotContain("Codigo de la vecina");
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> T conLaTransaccionQueDiceLaAnotacion(
            T objetivo, TenantTransactionManager gestor) {
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

    private static void crearCodigo(long municipalidadId, String codigo, String descripcion)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO codigo_infraccion (municipalidad_id, familia, codigo,"
                                    + " descripcion, porcentaje_uit, base_legal, vigencia_desde)"
                                    + " VALUES (?, 'TRANSITO', ?, ?, 8.0000, 'D.S. 016-2009-MTC',"
                                    + " DATE '2026-01-01')")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, descripcion);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }
}
