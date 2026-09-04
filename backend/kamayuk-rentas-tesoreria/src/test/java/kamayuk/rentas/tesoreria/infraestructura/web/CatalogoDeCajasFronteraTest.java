package kamayuk.rentas.tesoreria.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.tesoreria.aplicacion.ConsultaDeCajas;
import kamayuk.rentas.tesoreria.infraestructura.CajaRepositoryJdbc;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import kamayuk.rentas.web.ParametrosDePaginacion;
import org.jspecify.annotations.Nullable;
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
 * El catalogo de ventanillas, de HTTP a PostgreSQL y sin un doble por el camino (#618).
 *
 * <h2>Por que va hasta la base</h2>
 *
 * <p>Porque las dos cosas que este issue tiene que demostrar no se pueden demostrar de otro modo.
 * El <b>aislamiento</b> —que la lista solo trae las cajas de la municipalidad del token, y que dos
 * municipalidades con una caja {@code C-01} cada una reciben cada una la suya— lo sostiene la
 * politica RLS, que un doble no tiene: {@code CajaRepositoryJdbc} no lleva ningun {@code WHERE
 * municipalidad_id} y no debe llevarlo (regla 2), asi que lo unico que separa las dos
 * municipalidades es la politica de {@code V6} con el valor que {@code SET LOCAL} fijo. Y el
 * <b>area legible</b> sale de un {@code LEFT JOIN} con una segunda tabla que tambien tiene RLS:
 * comprobarlo en memoria seria comprobar el doble.
 *
 * <p>La conexion es la de {@code sgtm_app}. Un superusuario omite RLS incluso con {@code FORCE ROW
 * LEVEL SECURITY}, asi que una prueba escrita sobre el no verificaria ningun aislamiento; y con
 * {@code sgtm_owner} tampoco basta —{@code FORCE} lo sujeta a la politica igual, de modo que la
 * rotura clasica escrita con el dueño saldria en VERDE (#537, #545)—. Lo fija {@link
 * #seConectaComoSgtmApp}.
 *
 * <p>El proxy transaccional se construye con {@link AnnotationTransactionAttributeSource}, o sea
 * <b>obedeciendo a la anotacion</b> como haria el contenedor: envolver el caso de uso en un {@code
 * TransactionTemplate} incondicional dejaria la prueba pasando con el {@code @Transactional}
 * quitado, que es justo el modo de fallo que existe para impedir (#430, #569).
 */
@DisplayName("RF-080 — El catalogo de ventanillas, de HTTP a PostgreSQL (#618)")
class CatalogoDeCajasFronteraTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static long municipalidadSinCajas;
    private static JdbcClient jdbc;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("241201", "Municipalidad que cobra en ventanilla");
        municipalidadB = crearMunicipalidad("241202", "Municipalidad vecina");
        municipalidadSinCajas = crearMunicipalidad("241203", "Municipalidad recien implantada");

        // ── La municipalidad A ────────────────────────────────────────────
        // Ocho ventanillas —las de una municipalidad provincial— elegidas para
        // que ninguna comprobacion pueda pasar por casualidad: con area, una
        // SIN area —la caja tributaria general, que un JOIN interno se llevaria
        // por delante—, y una dada de baja. Ademas C-03 se llama IGUAL que
        // C-01, que es como se rotulan las ventanillas de dos sedes.
        //
        // OCHO y no cuatro, y esta medido: con cuatro filas el orden por nombre
        // sale bien SIN desempate —el motor las devuelve en el orden del heap y
        // la rotura de `desempatandoPor` pasa en VERDE—, que es el «determinista
        // por accidente» de #548. A partir de ocho, PostgreSQL ordena la pagina
        // entera con quicksort y una pagina de una fila con top-N heapsort, y
        // los dos algoritmos colocan los empates al reves: medido sobre estas
        // mismas ocho filas, la pagina entera abre por C-03 y las paginas de una
        // en una por C-01.
        //
        // Los rotulos empiezan por letras distintas a proposito: asi el orden
        // por nombre es el mismo en cualquier colacion del motor, y lo que la
        // prueba mide es el desempate y no el locale de quien la corre.
        long rentas = sembrarArea(municipalidadA, "REN", "GERENCIA DE RENTAS");
        long tramite = sembrarArea(municipalidadA, "TRAM", "TRAMITE DOCUMENTARIO");
        sembrarCaja(municipalidadA, "C-01", "CAJA TRIBUTARIA", "001", rentas, true);
        sembrarCaja(municipalidadA, "C-02", "MODULO DE TASAS", "002", tramite, true);
        sembrarCaja(municipalidadA, "C-03", "CAJA TRIBUTARIA", "003", null, true);
        sembrarCaja(municipalidadA, "C-04", "SEDE NORTE", "004", rentas, true);
        sembrarCaja(municipalidadA, "C-05", "SEDE SUR", "005", rentas, true);
        sembrarCaja(municipalidadA, "C-06", "TERMINAL TERRESTRE", "006", tramite, true);
        sembrarCaja(municipalidadA, "C-07", "MERCADO CENTRAL", "007", tramite, true);
        sembrarCaja(municipalidadA, "C-99", "PLATAFORMA DEL MERCADO", "099", rentas, false);

        // ── La municipalidad B ────────────────────────────────────────────
        // El MISMO codigo de caja y el mismo codigo de area: si el aislamiento
        // fallara saldrian mezcladas y el codigo no lo diria, porque es el mismo.
        long rentasB = sembrarArea(municipalidadB, "REN", "GERENCIA DE ADMINISTRACION");
        sembrarCaja(municipalidadB, "C-01", "VENTANILLA UNICA DE LA VECINA", "001", rentasB, true);

        // ── La municipalidad sin cajas ────────────────────────────────────
        // No se le siembra nada: es el estado de una instalacion recien
        // implantada, antes del paso 4 de la siembra (#460).

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        ConsultaDeCajas catalogo =
                envolver(new ConsultaDeCajas(new CajaRepositoryJdbc(jdbc)), gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(new CatalogoDeCajasController(catalogo))
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
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName(
            "AC 1 — lista las ventanillas de la municipalidad, por codigo, en el sobre de siempre")
    void listaLasVentanillasDeLaMunicipalidad() throws Exception {
        MvcResult resultado = cajas("");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "sin el @Transactional del caso de uso, la politica RLS no devuelve vacio:"
                                + " falla con «invalid input syntax for type bigint: \"\"» y esto"
                                + " seria 500 (#486)")
                .isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"totalElementos\":8");
        assertThat(codigosDe(cuerpo))
                .as("por codigo, que es como la municipalidad rotula sus ventanillas")
                .containsExactly("C-01", "C-02", "C-03", "C-04", "C-05", "C-06", "C-07", "C-99");
    }

    @Test
    @DisplayName("AC 1 — el area viaja legible, y la caja que no cuelga de ninguna sale igual")
    void elAreaViajaLegibleYLaQueNoTieneSaleIgual() throws Exception {
        String cuerpo = cajas("").getResponse().getContentAsString();

        assertThat(unaCaja(cuerpo, "C-01"))
                .as(
                        "«area_id» es un numero que fuera del servidor no lo puede leer nadie"
                                + " (RNF-080): lo que se publica es el codigo y el nombre")
                .contains("\"areaCodigo\":\"REN\"")
                .contains("\"areaNombre\":\"GERENCIA DE RENTAS\"");
        assertThat(unaCaja(cuerpo, "C-03"))
                .as(
                        "la caja tributaria general no cuelga de ningun area: con un JOIN interno"
                                + " desapareceria del catalogo la ventanilla por la que entra la"
                                + " mayor parte del dinero")
                .contains("\"areaCodigo\":null")
                .contains("\"areaNombre\":null");
    }

    @Test
    @DisplayName("AC 1 — la ventanilla dada de baja sale, y se distingue de las abiertas")
    void laDadaDeBajaSaleYSeDistingue() throws Exception {
        String cuerpo = cajas("").getResponse().getContentAsString();

        assertThat(unaCaja(cuerpo, "C-99"))
                .as(
                        "sus recibos siguen existiendo (RNF-051): si no saliera, el filtro «Caja»"
                                + " del duplicado no podria nombrarla y esos recibos quedarian"
                                + " inencontrables sin que nada lo dijera (#431, #427)")
                .contains("\"activa\":false");
        assertThat(unaCaja(cuerpo, "C-01"))
                .as("y las abiertas se distinguen de ella, que es para lo que viaja la columna")
                .contains("\"activa\":true");
    }

    @Test
    @DisplayName("AC 1 — una municipalidad sin ninguna caja es 200 con cero filas, no un 404")
    void laMunicipalidadSinCajasEsUnaPaginaVacia() throws Exception {
        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadSinCajas));

        MvcResult resultado = cajas("");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "una instalacion recien implantada y todavia sin ventanillas no es un"
                                + " error: es el estado por el que pasan todas antes del paso 4 de"
                                + " la siembra (#460)")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"totalElementos\":0")
                .contains("\"contenido\":[]");
    }

    @Test
    @DisplayName("AC 2 — la vecina recibe SU C-01, no el de la municipalidad A")
    void elCodigoNoSeConfundeConElDeOtraMunicipalidad() throws Exception {
        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        String cuerpo = cajas("").getResponse().getContentAsString();

        assertThat(cuerpo)
                .as(
                        "con el pool conectado como superusuario del cluster saldrian las nueve, y"
                                + " el codigo repetido no lo diria: C-01 esta en las dos")
                .contains("\"totalElementos\":1");
        assertThat(codigosDe(cuerpo)).containsExactly("C-01");
        assertThat(unaCaja(cuerpo, "C-01"))
                .as("y el C-01 que recibe es el SUYO, con su rotulo y con SU area")
                .contains("\"nombre\":\"VENTANILLA UNICA DE LA VECINA\"")
                .contains("\"areaNombre\":\"GERENCIA DE ADMINISTRACION\"");
        assertThat(cuerpo)
                .as(
                        "el area de la municipalidad A no se cuela por el LEFT JOIN, que tambien"
                                + " tiene RLS: las dos tienen un area «REN»")
                .doesNotContain("GERENCIA DE RENTAS");
    }

    @Test
    @DisplayName("AC 2 — el centinela: la prueba se conecta como sgtm_app y no como otra cosa")
    void seConectaComoSgtmApp() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                .as(
                        "con superusuario RLS se omite —incluso con FORCE ROW LEVEL SECURITY— y"
                                + " todo lo de este archivo pasaria sin verificar nada. Con"
                                + " sgtm_owner NO basta: FORCE lo sujeta a la politica igual, asi"
                                + " que la rotura clasica escrita con el dueño sale VERDE (#537,"
                                + " #545)")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    @Test
    @DisplayName("pagina: dos paginas consecutivas no repiten una fila ni se saltan otra")
    void paginaSinRepetirNiSaltarse() throws Exception {
        String primera = cajas("pagina=0&tamano=3").getResponse().getContentAsString();
        String segunda = cajas("pagina=1&tamano=3").getResponse().getContentAsString();
        String tercera = cajas("pagina=2&tamano=3").getResponse().getContentAsString();

        assertThat(codigosDe(primera)).containsExactly("C-01", "C-02", "C-03");
        assertThat(codigosDe(segunda)).containsExactly("C-04", "C-05", "C-06");
        assertThat(codigosDe(tercera)).containsExactly("C-07", "C-99");
        assertThat(primera).contains("\"totalPaginas\":3").contains("\"hayMas\":true");
        assertThat(tercera).contains("\"hayMas\":false");
    }

    @Test
    @DisplayName("ordenar por nombre es un orden TOTAL: dos ventanillas homonimas no se barajan")
    void elOrdenPorNombreEsTotal() throws Exception {
        List<String> enteras =
                codigosDe(cajas("ordenarPor=nombre").getResponse().getContentAsString());
        List<String> deUnaEnUna = new ArrayList<>();
        for (int pagina = 0; pagina < 8; pagina++) {
            deUnaEnUna.addAll(
                    codigosDe(
                            cajas("ordenarPor=nombre&pagina=" + pagina + "&tamano=1")
                                    .getResponse()
                                    .getContentAsString()));
        }

        assertThat(deUnaEnUna)
                .as(
                        "«CAJA TRIBUTARIA» es el nombre de C-01 y de C-03: sin"
                                + " desempatandoPor(codigo) el motor no promete cual va primero, y"
                                + " no promete lo mismo pidiendo la pagina entera —quicksort— que"
                                + " pidiendola de una en una —top-N heapsort—, asi que una de las"
                                + " dos se repite y la otra se pierde (#543, #548)")
                .containsExactlyElementsOf(enteras);
        assertThat(enteras)
                .containsExactly("C-01", "C-03", "C-07", "C-02", "C-99", "C-04", "C-05", "C-06");
    }

    @Test
    @DisplayName("ordenar por un campo que no esta en la lista blanca es 422, no una inyeccion")
    void elOrdenSeValidaContraLaListaBlanca() throws Exception {
        MvcResult resultado = cajas("ordenarPor=(SELECT+1)");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("No se puede ordenar por");
    }

    @Test
    @DisplayName(
            "la lectura pide LECTURA sobre caja_tributaria, y la comparte con las otras cuatro")
    void laLecturaPideLecturaYLaComparte() throws Exception {
        // La regla de ArchUnit admite `@RequiereAcceso` «en la clase o en cada endpoint», asi que
        // ve la anotacion y no CUAL es: cambiar el acceso por otro del catalogo la deja en verde
        // (#431, #543, #555). Y de `oTambien` no sabe nada, aunque sea lo que decide si un cajero
        // que solo cierra su turno puede llenar su desplegable o recibe un 403.
        RequiereAcceso requisito =
                CatalogoDeCajasController.class
                        .getMethod("listar", ParametrosDePaginacion.class)
                        .getAnnotation(RequiereAcceso.class);

        assertThat(requisito)
                .as("la declara el metodo, no la hereda: la clase no tiene")
                .isNotNull();
        assertThat(requisito.acceso()).isEqualTo("caja_tributaria");
        assertThat(requisito.privilegio())
                .as("mirar el catalogo de ventanillas no cobra nada")
                .isEqualTo(Privilegio.LECTURA);
        assertThat(requisito.oTambien())
                .as(
                        "son exactamente las opciones del catalogo cuya operacion exige el codigo"
                                + " de una caja: sin ellas hay que otorgar la ventanilla de cobro"
                                + " entera a quien solo cierra su turno o busca un recibo")
                .containsExactlyInAnyOrder(
                        "caja_tasas", "cierre_caja", "avance_recaudacion", "duplicado_recibo");
    }

    @Test
    @DisplayName("no declara ningun parametro propio: solo el dialecto de la paginacion")
    void noDeclaraNingunParametroPropio() throws Exception {
        // Desde #539 un parametro que el contrato declara y ningun argumento reclama se contesta
        // con 422 nombrandolo. Aqui no hay ninguno, y esta prueba es lo que hace que anadir uno
        // —un «activa», un «codigo»— obligue a leerlo en el mismo cambio.
        assertThat(
                        CatalogoDeCajasController.class
                                .getMethod("listar", ParametrosDePaginacion.class)
                                .getParameterCount())
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------

    private static MvcResult cajas(String consulta) throws Exception {
        return mvc.perform(get("/rentas/api/v1/tesoreria/cajas?" + consulta)).andReturn();
    }

    /** Los codigos de caja del cuerpo, en el orden en que salieron. */
    private static List<String> codigosDe(String cuerpo) {
        Matcher casa = Pattern.compile("\"codigo\":\"([^\"]+)\"").matcher(cuerpo);
        List<String> codigos = new ArrayList<>();
        while (casa.find()) {
            codigos.add(casa.group(1));
        }
        return codigos;
    }

    /** El trozo del JSON de una caja, para no leer los campos de la fila de al lado. */
    private static String unaCaja(String cuerpo, String codigo) {
        Matcher casa =
                Pattern.compile("\\{[^{}]*\"codigo\":\"" + codigo + "\"[^{}]*\\}").matcher(cuerpo);
        assertThat(casa.find()).as("la caja " + codigo + " tenia que estar en la pagina").isTrue();
        return casa.group();
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

    private static long sembrarArea(long municipalidadId, String codigo, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO area (municipalidad_id, codigo, nombre, activa)"
                                    + " VALUES (?, ?, ?, true) RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, nombre);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static void sembrarCaja(
            long municipalidadId,
            String codigo,
            String nombre,
            String serie,
            @Nullable Long areaId,
            boolean activa)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO caja (municipalidad_id, codigo, nombre, serie, area_id,"
                                    + " activa) VALUES (?, ?, ?, ?, ?, ?)")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, nombre);
                sentencia.setString(4, serie);
                if (areaId == null) {
                    sentencia.setNull(5, Types.BIGINT);
                } else {
                    sentencia.setLong(5, areaId);
                }
                sentencia.setBoolean(6, activa);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    /** El proxy que obedece a la anotacion, como el contenedor. Ver el javadoc de la clase. */
    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }
}
