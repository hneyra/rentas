package kamayuk.rentas.catastro.infraestructura.web;

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
import kamayuk.rentas.catastro.aplicacion.ConsultaDeLaFichaVigente;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
import kamayuk.rentas.catastro.infraestructura.FichaCatastralRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
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
 * Las cuatro fichas, de HTTP a PostgreSQL, sin un doble por el camino.
 *
 * <p>La gemela de {@code ContribuyenteControllerFronteraTest} para catastro, y por el mismo defecto
 * (#486): {@code FichaController} resolvia el predio <b>desde el propio controlador</b>, contra
 * {@code CatastroRepository}, y solo despues entraba a los casos de uso transaccionales. Media
 * peticion dentro y media fuera, y las cuatro rutas contestaban <b>500</b>.
 *
 * <p>Ninguna prueba lo veia: las de esta carpeta montan el controlador sobre <b>dobles en
 * memoria</b> de los repositorios, que no saben nada de RLS, y las de {@code aplicacion} llaman al
 * caso de uso ya envuelto en su transaccion. Esta cruza entera, con el proxy construido a partir de
 * la <b>anotacion</b> —como el contenedor— y conectada como {@code sgtm_app}, que es quien sufre la
 * politica: un superusuario la omite y no verificaria nada.
 */
@DisplayName("RF-016/018 — Las fichas, de HTTP a PostgreSQL (#486)")
class FichaControllerFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC);

    /** Veintitres posiciones, la plantilla del manual (D-10 sigue abierta y no se decide aqui). */
    private static final String CODIGO = "23010100010001000100001";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("230101", "Municipalidad de la frontera catastral");

        long predioId = crearPredio(CODIGO, "AV. GRAU 100");
        crearFichaUnica(predioId);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        ConsultaDeLaFichaVigente fichaVigente =
                conLaTransaccionQueDiceLaAnotacion(
                        new ConsultaDeLaFichaVigente(
                                new CatastroRepositoryJdbc(jdbc),
                                new FichaCatastralRepositoryJdbc(jdbc)),
                        new TenantTransactionManager(pool));

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new FichaController(null, null, null, fichaVigente, RELOJ))
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
        TenantContext.fijar(new MunicipalidadId(municipalidad));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName("la ficha urbana llega a PostgreSQL y vuelve con su area")
    void laFichaUrbanaSeLee() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/catastro/fichas/urbana/" + CODIGO)).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "resolviendo el predio fuera de transaccion, RLS falla con «invalid input"
                                + " syntax for type bigint: \"\"» y esto seria 500")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("CASA HABITACION");
    }

    @Test
    @DisplayName("un codigo que no existe es un 404, no un 500")
    void elCodigoInexistenteSeDistingue() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/catastro/fichas/urbana/23010100010001000109999"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString())
                .as("el codigo estaba mal, no la ficha: los dos «no hay» se distinguen")
                .contains("codigo de referencia catastral");
    }

    @Test
    @DisplayName("un predio sin ficha de ese tipo tambien es 404, y lo dice de otra manera")
    void elPredioSinEsaFichaSeDistingue() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/catastro/fichas/rural/" + CODIGO)).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString())
                .as("el predio existe: lo que no tiene es ficha rural")
                .contains("no tiene ficha");
    }

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

    private static void crearFichaUnica(long predioId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO ficha_catastral (municipalidad_id, predio_id, tipo,"
                                    + " version, area_terreno, uso, vigencia_desde, origen,"
                                    + " documento_origen, observacion, usuario_registro)"
                                    + " VALUES (?, ?, 'UNICA', 1, 120.00, 'CASA HABITACION',"
                                    + " DATE '2026-01-01', 'MIGRACION', 'CARGA', 'siembra',"
                                    + " 'prueba')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, predioId);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }
}
