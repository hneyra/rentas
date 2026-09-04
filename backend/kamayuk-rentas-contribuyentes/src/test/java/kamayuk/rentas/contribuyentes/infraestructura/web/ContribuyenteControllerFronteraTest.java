package kamayuk.rentas.contribuyentes.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.aplicacion.ConsultaDelPadron;
import kamayuk.rentas.contribuyentes.aplicacion.RegistrarContribuyente;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.GuardiaDeParametros;
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
 * La frontera entera: HTTP, controlador, repositorio y PostgreSQL, sin un doble por el camino.
 *
 * <h2>Por que existe</h2>
 *
 * <p>{@code GET /rentas/contribuyentes} contestaba <b>500</b> en la marcha blanca (#486), y ninguna
 * prueba lo veia. No por descuido, sino porque las dos familias de pruebas del modulo se reparten
 * la frontera y <b>ninguna la cruza</b>:
 *
 * <ul>
 *   <li>las de repositorio hablan con PostgreSQL de verdad, pero <b>desde dentro</b> de una
 *       transaccion que abre la propia prueba;
 *   <li>las de capa web llegan por HTTP, pero contra un <b>doble</b> del repositorio, que no sabe
 *       nada de RLS.
 * </ul>
 *
 * <p>Entre las dos queda el trozo que fallaba: el controlador llamando al repositorio <b>sin
 * transaccion</b>, que es exactamente lo que hace la aplicacion cuando llega una peticion.
 *
 * <h2>Que la hace fiel, y no un montaje que pasa siempre</h2>
 *
 * <p>El proxy transaccional se construye con {@link AnnotationTransactionAttributeSource}, o sea
 * <b>obedeciendo a la anotacion</b>, igual que el contenedor en produccion: si {@link
 * ConsultaDelPadron#buscar} deja de declarar {@code @Transactional}, el proxy no abre nada y la
 * consulta sale sin {@code SET LOCAL app.municipalidad_id}. Envolver el objeto en un {@code
 * TransactionTemplate} incondicional habria hecho pasar la prueba con la anotacion quitada, que es
 * el modo de fallo que esta prueba existe para impedir.
 *
 * <p>Y la conexion es la de {@code sgtm_app}: un superusuario omite RLS incluso con {@code FORCE
 * ROW LEVEL SECURITY}, asi que una prueba escrita sobre el no verificaria nada.
 */
@DisplayName("RF-011 — El padron, de HTTP a PostgreSQL (#486)")
class ContribuyenteControllerFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(
                    LocalDate.of(2026, 8, 30).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        municipalidadA = crearMunicipalidad("220101", "Municipalidad de la frontera A");
        municipalidadB = crearMunicipalidad("220102", "Municipalidad de la frontera B");

        sembrar(municipalidadA, "00001", "40123456", "PEÑA GARCIA, MARIA DEL CARMEN");
        sembrar(municipalidadA, "00002", "40123457", "QUISPE MAMANI, JOSE LUIS");
        sembrar(municipalidadB, "00001", "40999999", "OTRO PADRON, PERSONA DISTINTA");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        ContribuyenteRepositoryJdbc repositorio = new ContribuyenteRepositoryJdbc(jdbc);
        ConsultaDelPadron consulta =
                conLaTransaccionQueDiceLaAnotacion(new ConsultaDelPadron(repositorio), gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new ContribuyenteController(
                                        consulta,
                                        // Esta prueba mide la LECTURA (#486); lo que el mismo
                                        // controlador escribe desde #488 lo mide
                                        // EscrituraDelPadronControllerTest. Los colaboradores de
                                        // escritura se construyen igualmente —ninguna prueba de
                                        // aqui los llama— porque un `null` en un paquete
                                        // `@NullMarked` es una promesa rota que el dia que alguien
                                        // anada un caso de escritura aqui sale como un NPE.
                                        new RegistrarContribuyente(
                                                repositorio, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                                        (usuario, acceso, privilegio, fecha) -> true,
                                        RELOJ))
                        // #539: el mismo interceptor que instala la aplicacion. Sin el, pedir por
                        // `dni` en minusculas devuelve el padron entero con 200.
                        .addInterceptors(new GuardiaDeParametros())
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
    @DisplayName("la peticion llega a PostgreSQL y vuelve con el padron de su municipalidad")
    void elPadronSeLee() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/rentas/contribuyentes")).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "sin transaccion, RLS falla con «invalid input syntax for type bigint: \"\"»"
                                + " y esto seria 500")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("PEÑA GARCIA")
                .contains("QUISPE MAMANI");
    }

    @Test
    @DisplayName("y no trae el padron de la municipalidad vecina")
    void elAislamientoSeSostieneEnLaFrontera() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/rentas/contribuyentes")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .as("el codigo 00001 existe en las dos: lo que las separa es RLS, no el criterio")
                .doesNotContain("OTRO PADRON");
    }

    @Test
    @DisplayName("el filtro por documento tambien cruza entera")
    void elFiltroViajaYFiltra() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/rentas/contribuyentes").param("dNI", "40123457"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("QUISPE MAMANI")
                .doesNotContain("PEÑA GARCIA");
    }

    @Test
    @DisplayName("el mismo filtro bien escrito trae UNA fila del padron sembrado")
    void elFiltroBienEscritoTraeUnaFila() throws Exception {
        MvcResult respuesta =
                mvc.perform(get("/rentas/api/v1/rentas/contribuyentes").param("dNI", "40123457"))
                        .andReturn();

        assertThat(filasDevueltas(respuesta))
                .as("hay dos contribuyentes sembrados en esta municipalidad, y se pidio uno")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("y escrito «dni» no devuelve el padron entero: 422 que nombra el parametro (#539)")
    void elFiltroMalEscritoNoAbreElPadron() throws Exception {
        MvcResult respuesta =
                mvc.perform(get("/rentas/api/v1/rentas/contribuyentes").param("dni", "40123457"))
                        .andReturn();

        assertThat(filasDevueltas(respuesta))
                .as(
                        "esto es el defecto entero: con el parametro ignorado la respuesta era 200"
                                + " con las DOS filas del padron —contra Catacaos, 10 603—, o sea la"
                                + " peticion pidiendo a una persona y recibiendo a todas")
                .isZero();
        assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
        assertThat(respuesta.getResponse().getContentAsString())
                .as("y nombrarlo es lo unico que separa arreglarlo de creer que el padron esta mal")
                .contains("Parametro desconocido: 'dni'");
    }

    /**
     * Cuantas filas del padron devolvio la peticion: {@code 0} si no fue un {@code 200}.
     *
     * <p>Se mide sobre {@code totalElementos} del sobre y no sobre el codigo de estado, que es lo
     * que #539 pide: una prueba que solo comprobara «no es 500» seguiria en verde con el defecto
     * dentro, porque el defecto <b>era</b> un 200.
     */
    private static int filasDevueltas(MvcResult respuesta) throws Exception {
        if (respuesta.getResponse().getStatus() != 200) {
            return 0;
        }
        Matcher total =
                Pattern.compile("\"totalElementos\"\\s*:\\s*(\\d+)")
                        .matcher(respuesta.getResponse().getContentAsString());
        assertThat(total.find()).as("el sobre paginado trae su total").isTrue();
        return Integer.parseInt(total.group(1));
    }

    /**
     * El proxy que obedece a la anotacion, como el contenedor.
     *
     * <p>Es lo que convierte esta prueba en una medida y no en un montaje: quitarle el
     * {@code @Transactional} a {@link ConsultaDelPadron#buscar} deja al proxy sin nada que hacer, y
     * las tres pruebas de arriba se ponen rojas con el error de RLS de verdad.
     */
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

    private static void sembrar(
            long municipalidadId, String codigo, String documento, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'siembra')")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, documento);
                sentencia.setString(4, nombre);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }
}
