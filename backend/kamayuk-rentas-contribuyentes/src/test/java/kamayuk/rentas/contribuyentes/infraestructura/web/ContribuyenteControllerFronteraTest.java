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
import org.junit.jupiter.api.Nested;
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
 * <p>Y la conexion es la de {@code kamayuk_app}: un superusuario omite RLS incluso con {@code FORCE
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

        sembrar(municipalidadA, "00001", "DNI", "40123456", "PEÑA GARCIA, MARIA DEL CARMEN");
        sembrar(municipalidadA, "00002", "DNI", "40123457", "QUISPE MAMANI, JOSE LUIS");
        // Un extranjero, que es el caso que #35 existe para poder preguntar: hasta entonces el
        // borde publicaba `dNI` y `rUC` y ninguno mas, asi que un carne no se podia comprobar.
        sembrar(municipalidadA, "00003", "CE", "001234567890", "MARQUEZ SOLIS, DIEGO ARMANDO");
        sembrar(municipalidadB, "00001", "DNI", "40999999", "OTRO PADRON, PERSONA DISTINTA");

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
                mvc.perform(
                                get("/rentas/api/v1/rentas/contribuyentes")
                                        .param("numeroDocumento", "40123457"))
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
                mvc.perform(
                                get("/rentas/api/v1/rentas/contribuyentes")
                                        .param("numeroDocumento", "40123457"))
                        .andReturn();

        assertThat(filasDevueltas(respuesta))
                .as("hay tres contribuyentes sembrados en esta municipalidad, y se pidio uno")
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

    @Nested
    @DisplayName("#35 AC-1 — los parametros se llaman como manda la guia")
    class LosNombresDeLosParametros {

        @Test
        @DisplayName("«dNI» y «rUC» ya no existen: 422 que los nombra, no un filtro que se ignora")
        void losNombresViejosYaNoSeAdmiten() throws Exception {
            for (String viejo : java.util.List.of("dNI", "rUC")) {
                MvcResult respuesta =
                        mvc.perform(
                                        get("/rentas/api/v1/rentas/contribuyentes")
                                                .param(viejo, "40123457"))
                                .andReturn();

                assertThat(respuesta.getResponse().getStatus())
                        .as(
                                "un parametro retirado que se ignorara devolveria el padron entero"
                                        + " con 200, que es exactamente el defecto de #539 por el otro"
                                        + " extremo. Lo para GuardiaDeParametros: «%s»",
                                viejo)
                        .isEqualTo(422);
                assertThat(respuesta.getResponse().getContentAsString())
                        .contains("Parametro desconocido: '" + viejo + "'")
                        .as("y dice como se llaman ahora")
                        .contains("Se admiten: ")
                        .contains("numeroDocumento")
                        .contains("tipoDocumento");
            }
        }
    }

    @Nested
    @DisplayName("#35 AC-2 — buscar por codigo admite prefijo")
    class ElCodigoPorPrefijo {

        @Test
        @DisplayName("las primeras cifras encuentran MAS DE UNA fila, y antes devolvian cero")
        void elPrefijoEncuentraMasDeUna() throws Exception {
            MvcResult respuesta =
                    mvc.perform(get("/rentas/api/v1/rentas/contribuyentes").param("codigo", "0000"))
                            .andReturn();

            assertThat(filasDevueltas(respuesta))
                    .as(
                            "con la igualdad de antes de #35, medido contra Catacaos:"
                                    + " «?codigo=000000000» sobre 10 603 contribuyentes cuyos codigos"
                                    + " empiezan todos por ceros devolvia 0, y sin error — que se lee"
                                    + " como «ese contribuyente no existe»")
                    .isEqualTo(3);
            assertThat(respuesta.getResponse().getContentAsString())
                    .contains("PEÑA GARCIA")
                    .contains("QUISPE MAMANI")
                    .contains("MARQUEZ SOLIS");
        }

        @Test
        @DisplayName("y el codigo entero sigue trayendo una sola")
        void elCodigoEnteroSigueTrayendoUna() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    get("/rentas/api/v1/rentas/contribuyentes")
                                            .param("codigo", "00002"))
                            .andReturn();

            assertThat(filasDevueltas(respuesta))
                    .as(
                            "el prefijo mas largo que hay es el codigo entero: quien ya lo sabe no"
                                    + " recibe una lista")
                    .isEqualTo(1);
            assertThat(respuesta.getResponse().getContentAsString()).contains("QUISPE MAMANI");
        }

        @Test
        @DisplayName("y no cruza el aislamiento: la vecina tiene el mismo 00001 y no sale")
        void elPrefijoNoCruzaElAislamiento() throws Exception {
            MvcResult respuesta =
                    mvc.perform(get("/rentas/api/v1/rentas/contribuyentes").param("codigo", "0000"))
                            .andReturn();

            assertThat(respuesta.getResponse().getContentAsString())
                    .as("un filtro mas ancho es mas filas, y quien las acota sigue siendo RLS")
                    .doesNotContain("OTRO PADRON");
        }
    }

    @Nested
    @DisplayName("#35 AC-3 — se puede comprobar cualquier tipo de documento")
    class CualquierTipoDeDocumento {

        @Test
        @DisplayName("un carne de extranjeria se encuentra, y antes no habia como preguntarlo")
        void elCarneDeExtranjeriaSeEncuentra() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    get("/rentas/api/v1/rentas/contribuyentes")
                                            .param("tipoDocumento", "CE")
                                            .param("numeroDocumento", "001234567890"))
                            .andReturn();

            assertThat(filasDevueltas(respuesta))
                    .as(
                            "esto es lo que el alta necesita para no dar de alta dos veces al mismo"
                                    + " extranjero: con «dNI» y «rUC» como unicos filtros, la unica"
                                    + " manera de «comprobarlo» era traerse el padron y mirar la"
                                    + " primera pagina")
                    .isEqualTo(1);
            assertThat(respuesta.getResponse().getContentAsString()).contains("MARQUEZ SOLIS");
        }

        @Test
        @DisplayName("el tipo es opcional: el numero solo tambien encuentra al extranjero")
        void elTipoEsOpcional() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    get("/rentas/api/v1/rentas/contribuyentes")
                                            .param("numeroDocumento", "001234567890"))
                            .andReturn();

            assertThat(filasDevueltas(respuesta))
                    .as("quien atiende teclea el numero que trae el carne, no lo clasifica")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("y el tipo que no existe es 422 NOMBRANDO los seis, no una pagina vacia")
        void elTipoDesconocidoEs422ConSuVocabulario() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    get("/rentas/api/v1/rentas/contribuyentes")
                                            .param("tipoDocumento", "CARNE")
                                            .param("numeroDocumento", "001234567890"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString())
                    .contains("Tipo de documento desconocido: 'CARNE'")
                    .as(
                            "rechazar tambien es leer, y decir con que vocabulario es lo que lo"
                                    + " convierte en un rechazo que se arregla")
                    .contains("Se admiten: DNI, RUC, CE, PASAPORTE, PARTIDA, OTRO");
        }

        @Test
        @DisplayName("y el tipo SIN numero es 422: acotar solo por tipo es pedir medio padron")
        void elTipoSinNumeroEs422() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    get("/rentas/api/v1/rentas/contribuyentes")
                                            .param("tipoDocumento", "DNI"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString())
                    .contains("Buscar por tipo de documento sin numero");
        }
    }

    @Nested
    @DisplayName("#35 AC-4 — ORDEN_NO_ADMITIDO dice por que campos SI se puede ordenar")
    class ElOrdenQueNoSeAdmite {

        @Test
        @DisplayName("«deuda» sigue siendo 422, y ahora el cuerpo trae la lista")
        void ordenarPorDeudaDiceLaLista() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    get("/rentas/api/v1/rentas/contribuyentes")
                                            .param("ordenarPor", "deuda"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            String cuerpo = respuesta.getResponse().getContentAsString();
            assertThat(cuerpo)
                    .contains("\"codigo\":\"ORDEN_NO_ADMITIDO\"")
                    .contains("Campo pedido: deuda");
            assertThat(cuerpo)
                    .as(
                            "la misma operacion contesta «Se admiten: …» cuando el PARAMETRO no"
                                    + " existe; sin esta linea, la misma clase de error tenia dos"
                                    + " calidades de respuesta y quien integra tenia que adivinar")
                    .contains("Se admiten: ")
                    .contains("codigoContribuyente")
                    .contains("nombreRazonSocial")
                    .contains("numeroDocumento");
            assertThat(cuerpo)
                    .as("y no ofrece lo que esta operacion no publica")
                    .doesNotContain("deuda,");
        }
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
            long municipalidadId, String codigo, String tipo, String documento, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, ?, ?, 'NATURAL', ?, 'siembra')")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, tipo);
                sentencia.setString(4, documento);
                sentencia.setString(5, nombre);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }
}
