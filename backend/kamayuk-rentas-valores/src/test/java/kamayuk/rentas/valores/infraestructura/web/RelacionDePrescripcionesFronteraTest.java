package kamayuk.rentas.valores.infraestructura.web;

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
import kamayuk.rentas.contribuyentes.aplicacion.DirectorioJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.FichaRepositoryJdbc;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.valores.aplicacion.ConsultaDePrescripciones;
import kamayuk.rentas.valores.aplicacion.DeclararPrescripcion;
import kamayuk.rentas.valores.aplicacion.PlazosParametrizados;
import kamayuk.rentas.valores.dobles.ParametrosDeMentira;
import kamayuk.rentas.valores.infraestructura.PrescripcionRepositoryJdbc;
import kamayuk.rentas.valores.infraestructura.ValorRepositoryJdbc;
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
 * #674 — La relacion de prescripciones declaradas, de HTTP a PostgreSQL y sin un doble por el
 * camino.
 *
 * <h2>Para que existe esta lectura</h2>
 *
 * <p>Es la contrapartida de la decision de #674: declarar la prescripcion <b>no toca el libro</b>
 * —lo que prescribe es la accion de cobro, no la obligacion (art. 43 del TUO del Codigo
 * Tributario)—, asi que la deuda sigue siendo cartera pendiente y emision del ejercicio hasta que
 * la administracion la de de baja con RF-044. Si esa deuda inexigible no se puede <b>ver</b> en
 * ninguna parte, la decision se vuelve indistinguible de un descuido.
 *
 * <h2>Por que esta prueba y no una de capa web con un repositorio de mentira</h2>
 *
 * <p>Por dos defectos que un doble no puede reproducir. Uno: sin {@code @Transactional} no hay
 * {@code SET LOCAL}, y sin el la politica RLS no devuelve vacio —<b>revienta</b> con 500 (#486)—.
 * Dos: un parametro <b>declarado y no leido</b> contesta 200 y devuelve otra cosa, asi que la unica
 * comprobacion que muerde es la que <b>compara las filas devueltas contra el conjunto sembrado</b>
 * (#425, #541).
 *
 * <p>Conectada como {@code sgtm_app} —quien sufre la politica RLS—, nunca como {@code sgtm_owner}:
 * con {@code FORCE ROW LEVEL SECURITY} el dueno tambien queda sujeto a la politica, de modo que esa
 * mutacion pasaria en verde sin demostrar nada (#537, #545, #601, #639). Quien la omite es el
 * superusuario del cluster.
 */
@DisplayName("#674 — GET /coactiva/prescripcion, de HTTP a PostgreSQL")
class RelacionDePrescripcionesFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2033-06-15T12:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static MockMvc mvc;
    private static JdbcClient jdbc;

    /** La de PEREZ sobre PREDIAL 2018-2020: prescriben 2018 y 2019, no 2020. */
    private static long enParte;

    /** La de PEREZ sobre ARBITRIOS 2019: no procede. */
    private static long noProcede;

    /** La de QUISPE sobre PREDIAL 2017: procede entera. */
    private static long deQuispe;

    /** La de la municipalidad vecina; no la tiene que ver nadie desde A. */
    private static long deLaVecina;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("270101", "Municipalidad de la relacion");
        municipalidadB = crearMunicipalidad("270102", "Municipalidad vecina");

        long perez = crearContribuyente(municipalidadA, "PR-0001", "80770001", "PEREZ, JUAN");
        long quispe = crearContribuyente(municipalidadA, "PR-0002", "80770002", "QUISPE, ANA");
        long vecino = crearContribuyente(municipalidadB, "PR-0001", "80770003", "VECINO, LUIS");

        long conjuntoA = crearConjunto(municipalidadA);
        long conjuntoB = crearConjunto(municipalidadB);

        enParte =
                crearPrescripcion(
                        municipalidadA,
                        perez,
                        "PREDIAL",
                        2018,
                        2020,
                        conjuntoA,
                        "PROCEDE_EN_PARTE",
                        "RES-001-2033");
        prescritos(municipalidadA, enParte, 2018, true);
        prescritos(municipalidadA, enParte, 2019, true);
        prescritos(municipalidadA, enParte, 2020, false);

        noProcede =
                crearPrescripcion(
                        municipalidadA,
                        perez,
                        "ARBITRIO",
                        2019,
                        2019,
                        conjuntoA,
                        "NO_PROCEDE",
                        null);
        prescritos(municipalidadA, noProcede, 2019, false);

        deQuispe =
                crearPrescripcion(
                        municipalidadA,
                        quispe,
                        "PREDIAL",
                        2017,
                        2017,
                        conjuntoA,
                        "PROCEDE",
                        "RES-002-2033");
        prescritos(municipalidadA, deQuispe, 2017, true);

        deLaVecina =
                crearPrescripcion(
                        municipalidadB, vecino, "PREDIAL", 2018, 2018, conjuntoB, "PROCEDE", null);
        prescritos(municipalidadB, deLaVecina, 2018, true);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        DirectorioJdbc padron =
                new DirectorioJdbc(
                        new ContribuyenteRepositoryJdbc(jdbc), new FichaRepositoryJdbc(jdbc));

        PrescripcionRepositoryJdbc prescripciones = new PrescripcionRepositoryJdbc(jdbc);
        ConsultaDePrescripciones consulta =
                conLaTransaccionQueDiceLaAnotacion(
                        new ConsultaDePrescripciones(prescripciones, padron),
                        new TenantTransactionManager(pool));

        // El POST no se ejercita aqui —lo miden `PrescripcionControllerTest` y
        // `NotificacionYPaseJdbcTest`—, pero el controlador es uno solo y se monta entero.
        DeclararPrescripcion declarar =
                new DeclararPrescripcion(
                        prescripciones,
                        new ValorRepositoryJdbc(jdbc),
                        new PlazosParametrizados(new ParametrosDeMentira()),
                        registro -> {});

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new PrescripcionController(declarar, consulta, padron, RELOJ))
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
    @DisplayName(
            "publica que deuda quedo sin accion de cobro, con los ejercicios que prescribieron")
    void publicaLoQuePrescribio() throws Exception {
        String json = pedir("/rentas/api/v1/coactiva/prescripcion");

        assertThat(json).contains("\"totalElementos\":3");
        assertThat(json)
                .as("los ejercicios que de verdad prescribieron, no el rango solicitado")
                .contains("\"ejerciciosPrescritos\":[2018,2019]")
                .contains("\"ejerciciosPrescritos\":[2017]")
                .contains("\"ejerciciosPrescritos\":[]");
        assertThat(json)
                .as("con quien es, para poder identificar la obligacion alcanzada")
                .contains("\"codContribuyente\":\"PR-0001\"")
                .contains("\"contribuyente\":\"PEREZ, JUAN\"");
        assertThat(json)
                .as("ninguna cifra de dinero: la prescripcion no extingue un importe")
                .doesNotContain("importe")
                .doesNotContain("insoluto");
    }

    @Test
    @DisplayName("«codContribuyente» acota, y un codigo que no esta en el padron es 404")
    void elContribuyenteAcota() throws Exception {
        String json = pedir("/rentas/api/v1/coactiva/prescripcion", "codContribuyente", "PR-0002");

        assertThat(json).contains("\"totalElementos\":1").contains("\"id\":" + deQuispe);
        assertThat(json).doesNotContain("\"id\":" + enParte).doesNotContain("\"id\":" + noProcede);

        MvcResult inexistente =
                mvc.perform(
                                get("/rentas/api/v1/coactiva/prescripcion")
                                        .param("codContribuyente", "PR-9999"))
                        .andReturn();
        assertThat(inexistente.getResponse().getStatus()).isEqualTo(404);
        assertThat(inexistente.getResponse().getContentAsString()).contains("PR-9999");
    }

    @Test
    @DisplayName("«tributo» acota")
    void elTributoAcota() throws Exception {
        String json = pedir("/rentas/api/v1/coactiva/prescripcion", "tributo", "ARBITRIO");

        assertThat(json).contains("\"totalElementos\":1").contains("\"id\":" + noProcede);
        assertThat(json).doesNotContain("\"id\":" + enParte);
    }

    @Test
    @DisplayName("«ejercicio» acota por el rango RESUELTO, prescribiera o no")
    void elEjercicioAcotaPorElRangoResuelto() throws Exception {
        String json = pedir("/rentas/api/v1/coactiva/prescripcion", "ejercicio", "2019");

        assertThat(json)
                .as("las dos que resolvieron 2019: la que prescribio y la que NO")
                .contains("\"totalElementos\":2")
                .contains("\"id\":" + enParte)
                .contains("\"id\":" + noProcede);
        assertThat(json).as("la de 2017 no resolvio 2019").doesNotContain("\"id\":" + deQuispe);
    }

    @Test
    @DisplayName("«resultado» acota, y una palabra que no es ninguna de las tres es 422")
    void elResultadoAcota() throws Exception {
        String json = pedir("/rentas/api/v1/coactiva/prescripcion", "resultado", "NO_PROCEDE");

        assertThat(json).contains("\"totalElementos\":1").contains("\"id\":" + noProcede);

        MvcResult desconocido =
                mvc.perform(
                                get("/rentas/api/v1/coactiva/prescripcion")
                                        .param("resultado", "PENDIENTE"))
                        .andReturn();
        assertThat(desconocido.getResponse().getStatus()).isEqualTo(422);
        assertThat(desconocido.getResponse().getContentAsString())
                .as("el mensaje dice cuales hay, en vez de devolver la relacion entera")
                .contains("PROCEDE_EN_PARTE");
    }

    @Test
    @DisplayName("un ejercicio fuera del rango del dominio es 422, no una relacion vacia")
    void unEjercicioImposibleEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/coactiva/prescripcion").param("ejercicio", "1800"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("la declaracion de la municipalidad vecina no sale, y la suya solo la ve ella")
    void cadaMunicipalidadVeLaSuya() throws Exception {
        assertThat(pedir("/rentas/api/v1/coactiva/prescripcion"))
                .doesNotContain("\"id\":" + deLaVecina)
                .doesNotContain("VECINO, LUIS");

        TenantContext.fijar(new MunicipalidadId(municipalidadB));
        String desdeB = pedir("/rentas/api/v1/coactiva/prescripcion");

        assertThat(desdeB)
                .as("una municipalidad ve una declaracion: la suya")
                .contains("\"totalElementos\":1")
                .contains("VECINO, LUIS");
        assertThat(desdeB).doesNotContain("PEREZ, JUAN").doesNotContain("QUISPE, ANA");
    }

    @Test
    @DisplayName("se conecta como sgtm_app, que es quien sufre la politica RLS")
    void seConectaComoSgtmApp() {
        // Mira el POOL que usa el controlador, y no una conexion aparte: es lo unico que impide
        // que un cambio de fixture devuelva la conexion sin que nadie lo note (#545). Con
        // `sgtm_owner` la mutacion de aislamiento pasaria en verde —FORCE ROW LEVEL SECURITY
        // sujeta tambien al dueno (#537)— y con el superusuario del cluster la politica se omite
        // entera; esta linea caza los dos casos.
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    // ------------------------------------------------------------------

    private static String pedir(String ruta, String... parametros) throws Exception {
        var peticion = get(ruta);
        for (int i = 0; i < parametros.length; i += 2) {
            peticion = peticion.param(parametros[i], parametros[i + 1]);
        }
        MvcResult resultado = mvc.perform(peticion).andReturn();
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        return resultado.getResponse().getContentAsString();
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

    // ---------- siembra ----------

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

    private static long crearContribuyente(
            long municipalidad, String codigo, String dni, String nombre) {
        return comoApp(
                municipalidad,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'siembra') RETURNING id",
                municipalidad,
                codigo,
                dni,
                nombre);
    }

    private static long crearConjunto(long municipalidad) {
        return comoApp(
                municipalidad,
                "INSERT INTO conjunto_parametros (municipalidad_id, ejercicio, version)"
                        + " VALUES (?, 2033, 1) RETURNING id",
                municipalidad);
    }

    private static long crearPrescripcion(
            long municipalidad,
            long contribuyente,
            String tributo,
            int desde,
            int hasta,
            long conjunto,
            String resultado,
            String resolucion) {
        return comoApp(
                municipalidad,
                "INSERT INTO prescripcion (municipalidad_id, contribuyente_id, tributo,"
                        + " ejercicio_desde, ejercicio_hasta, fecha_presentacion, causal,"
                        + " plazo_anios, conjunto_id, resultado, resolucion, usuario_registro,"
                        + " observacion)"
                        + " VALUES (?, ?, ?, ?, ?, DATE '2033-06-01', 'DECLARACION_PRESENTADA', 4,"
                        + " ?, ?, ?, 'siembra', 'Se resuelve la solicitud presentada')"
                        + " RETURNING id",
                municipalidad,
                contribuyente,
                tributo,
                desde,
                hasta,
                conjunto,
                resultado,
                resolucion);
    }

    private static void prescritos(
            long municipalidad, long prescripcion, int ejercicio, boolean prescrita) {
        comoApp(
                municipalidad,
                "INSERT INTO prescripcion_ejercicio (municipalidad_id, prescripcion_id, ejercicio,"
                        + " inicio_computo, inicio_vigente, fecha_prescripcion, prescrita)"
                        + " VALUES (?, ?, ?, DATE '2019-01-01', DATE '2019-01-01',"
                        + " DATE '2023-01-01', ?) RETURNING id",
                municipalidad,
                prescripcion,
                ejercicio,
                prescrita);
    }

    private static long comoApp(long municipalidad, String sql, Object... valores) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setObject(i + 1, valores[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }
}
