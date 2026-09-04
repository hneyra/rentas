package kamayuk.rentas.catastro.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import kamayuk.rentas.catastro.aplicacion.ConsultaDelPlanoCatastral;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El plano catastral, de HTTP a PostgreSQL con PostGIS y sin un doble por el camino (#536,
 * ADR-0022).
 *
 * <h2>Por que va hasta la base</h2>
 *
 * <p>Porque lo que hay que demostrar no se puede demostrar de otro modo. La <b>geometria</b> la
 * serializa {@code ST_AsGeoJSON} y ningun doble sabe hacerlo; el <b>marco</b> lo resuelve el motor
 * comparando las columnas que el propio motor deriva del poligono; y el <b>aislamiento</b> lo
 * sostiene la politica RLS, que un doble no tiene. La conexion es la de {@code sgtm_app}: un
 * superusuario omite RLS incluso con {@code FORCE ROW LEVEL SECURITY}, asi que una prueba escrita
 * sobre el no verificaria ningun aislamiento.
 *
 * <h2>El marco de los ensayos</h2>
 *
 * <p>Todos los lotes se siembran en Catacaos —alrededor de {@code -80.68, -5.26}— y los marcos se
 * escriben en grados. El «lote de fuera» esta a un decimo de grado, unos once kilometros: lejos de
 * cualquier redondeo y cerca de lo que separa dos sectores de un distrito.
 */
@DisplayName("ADR-0022 — El plano catastral, de HTTP a PostgreSQL (#536)")
class PlanoCatastralFronteraTest {

    private static final String RUTA = "/rentas/api/v1/catastro/predios/plano";

    /** El marco de los ensayos: el cuadrado que contiene a los lotes «de dentro». */
    private static final String MARCO = "-80.690,-5.270,-80.670,-5.250";

    /**
     * Un marco disjunto del anterior, y del distrito: el Titicaca.
     *
     * <p>Existe para el AC 2 de #613. Sin el, la igualdad de {@code sinGeometria} entre dos
     * peticiones podria salir por casualidad —comparar el marco consigo mismo no demuestra nada—;
     * lo que demuestra que el marco no acota la cuenta es que una peticion que no devuelve ni un
     * lote devuelva la misma cifra que la que los devuelve todos.
     */
    private static final String MARCO_VACIO = "-69.500,-16.200,-69.400,-16.100";

    /**
     * El poligono que se siembra y el que tiene que salir, vertice a vertice.
     *
     * <p>Se escribe con pocos decimales a proposito: asi la comparacion contra lo que devuelve
     * {@code ST_AsGeoJSON} es exacta y no depende de como se imprima un flotante.
     */
    private static final String LOTE_DE_DENTRO =
            "MULTIPOLYGON(((-80.68 -5.26, -80.679 -5.26, -80.679 -5.259, -80.68 -5.259,"
                    + " -80.68 -5.26)))";

    private static final String LOTE_DE_FUERA =
            "MULTIPOLYGON(((-80.58 -5.16, -80.579 -5.16, -80.579 -5.159, -80.58 -5.159,"
                    + " -80.58 -5.16)))";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("200105", "Municipalidad del plano");
        municipalidadB = crearMunicipalidad("200106", "Municipalidad vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        CatastroRepositoryJdbc catastro = new CatastroRepositoryJdbc(jdbc);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new PlanoCatastralController(
                                        envolver(new ConsultaDelPlanoCatastral(catastro), gestor)))
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
    void limpiar() throws SQLException {
        TenantContext.limpiar();
        vaciarPredios(municipalidadA);
        vaciarPredios(municipalidadB);
    }

    // ------------------------------------------------------------------
    // AC 1 — sale el del marco, con sus vertices
    // ------------------------------------------------------------------

    @Test
    @DisplayName("del marco sale el lote que cae dentro, y sus vertices son los que se sembraron")
    void saleElLoteDelMarcoConSusVertices() throws Exception {
        long dentro =
                sembrar(
                        municipalidadA,
                        "20010500000000000000001",
                        "CALLE DE DENTRO 1",
                        LOTE_DE_DENTRO);
        sembrar(municipalidadA, "20010500000000000000002", "CALLE DE FUERA 1", LOTE_DE_FUERA);

        JsonNode plano = pedir(MARCO);

        assertThat(plano.get("lotes").size())
                .as("el marco contiene a uno de los dos, y solo ese se dibuja")
                .isEqualTo(1);

        JsonNode lote = plano.get("lotes").get(0);
        assertThat(lote.get("predioId").asLong()).isEqualTo(dentro);
        assertThat(lote.get("codRefCatastral").asString()).isEqualTo("20010500000000000000001");

        JsonNode geometria = lote.get("geometria");
        assertThat(geometria.get("type").asString())
                .as("la columna es geography(MultiPolygon, 4326) y se sirve tal cual")
                .isEqualTo("MultiPolygon");

        // Vertice a vertice: sin reproyectar (serian metros de una zona UTM) y sin simplificar
        // (ST_Simplify tira vertices, y un vertice movido es un lindero movido).
        assertThat(geometria.get("coordinates").toString())
                .isEqualTo(
                        "[[[[-80.68,-5.26],[-80.679,-5.26],[-80.679,-5.259],"
                                + "[-80.68,-5.259],[-80.68,-5.26]]]]");
    }

    @Test
    @DisplayName("el marco filtra de verdad: el lote de fuera no sale por ningun lado")
    void elLoteDeFueraNoSale() throws Exception {
        sembrar(municipalidadA, "20010500000000000000002", "CALLE DE FUERA 1", LOTE_DE_FUERA);

        JsonNode plano = pedir(MARCO);

        assertThat(plano.get("lotes")).isEmpty();
        assertThat(plano.get("sinGeometria").asLong())
                .as("tiene poligono, solo que en otro sitio: no cuenta como sin levantar")
                .isZero();
    }

    // ------------------------------------------------------------------
    // AC 2 — bbox obligatorio
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sin 'bbox' es 422 nombrandolo, nunca el padron entero ni 200 con cero lotes")
    void sinMarcoEs422() throws Exception {
        sembrar(municipalidadA, "20010500000000000000001", "CALLE DE DENTRO 1", LOTE_DE_DENTRO);

        MvcResult sinMarco = mvc.perform(get(RUTA)).andReturn();

        assertThat(sinMarco.getResponse().getStatus())
                .as("un 200 con cero lotes se leeria como «aqui no hay predios»")
                .isEqualTo(422);
        assertThat(sinMarco.getResponse().getContentAsString()).contains("bbox");
    }

    @Test
    @DisplayName("un marco del reves o ilegible es 422, no un rectangulo vacio")
    void unMarcoIlegibleEs422() throws Exception {
        assertThat(
                        mvc.perform(get(RUTA).param("bbox", "no,es,un,marco"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(422);
        assertThat(
                        mvc.perform(get(RUTA).param("bbox", "-80.67,-5.25,-80.69,-5.27"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("oeste mayor que este: un rectangulo imposible, no uno vacio")
                .isEqualTo(422);
        assertThat(
                        mvc.perform(get(RUTA).param("bbox", "-190,-5.27,-80.67,-5.25"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("fuera del rango de coordenadas")
                .isEqualTo(422);
    }

    // ------------------------------------------------------------------
    // AC 3 — se niega antes que recortarse
    // ------------------------------------------------------------------

    @Test
    @DisplayName("con mas lotes que el tope es 422 con la cuenta, y con menos son todos")
    void conMasLotesQueElTopeSeNiega() throws Exception {
        sembrar(municipalidadA, "20010500000000000000001", "CALLE 1", LOTE_DE_DENTRO);
        sembrar(municipalidadA, "20010500000000000000002", "CALLE 2", LOTE_DE_DENTRO);
        sembrar(municipalidadA, "20010500000000000000003", "CALLE 3", LOTE_DE_DENTRO);

        MvcResult noCabe =
                mvc.perform(get(RUTA).param("bbox", MARCO).param("limite", "2")).andReturn();

        assertThat(noCabe.getResponse().getStatus())
                .as(
                        "la alternativa es devolver los dos primeros, y eso dibuja un plano al que"
                                + " le falta un lote: un hueco que se lee como «ahi no hay nada»")
                .isEqualTo(422);
        assertThat(noCabe.getResponse().getContentAsString())
                .as("dice cuantos hay y cual es el tope: una respuesta que se puede obedecer")
                .contains("3")
                .contains("2");

        JsonNode cabe = pedir(MARCO, "3");
        assertThat(cabe.get("lotes").size()).isEqualTo(3);
        assertThat(cabe.has("truncado"))
                .as("no hay marca de truncado porque no hay nada truncado (ADR-0022 §2)")
                .isFalse();
    }

    @Test
    @DisplayName("#611 — «acercate» trae su propio codigo; los otros tres 422 no lo traen")
    void elMarcoLlenoSeDistingueSinLeerElMensaje() throws Exception {
        sembrar(municipalidadA, "20010500000000000000001", "CALLE 1", LOTE_DE_DENTRO);
        sembrar(municipalidadA, "20010500000000000000002", "CALLE 2", LOTE_DE_DENTRO);
        sembrar(municipalidadA, "20010500000000000000003", "CALLE 3", LOTE_DE_DENTRO);

        String lleno = codigoDe(mvc.perform(get(RUTA).param("bbox", MARCO).param("limite", "2")));
        String sinMarco = codigoDe(mvc.perform(get(RUTA)));
        String delReves =
                codigoDe(mvc.perform(get(RUTA).param("bbox", "-80.67,-5.25,-80.69,-5.27")));
        String sobreElTope =
                codigoDe(
                        mvc.perform(
                                get(RUTA)
                                        .param("bbox", MARCO)
                                        .param(
                                                "limite",
                                                String.valueOf(
                                                        PlanoCatastralController.TOPE_DEL_SERVIDOR
                                                                + 1))));

        assertThat(lleno)
                .as(
                        "los otros tres dicen «corrige la peticion» y este dice «la peticion esta"
                                + " bien, acercate»: es lo unico que el plano puede ofrecer"
                                + " resolver solo, y con VALIDACION en los cuatro solo lo separaba"
                                + " el texto")
                .isEqualTo("MARCO_CON_DEMASIADOS_LOTES");
        assertThat(List.of(sinMarco, delReves, sobreElTope))
                .as(
                        "y hay que medirlo en las dos direcciones: darselo tambien a un rechazo"
                                + " deja la comparacion del marco lleno en verde y no distingue"
                                + " nada")
                .containsExactly("VALIDACION", "VALIDACION", "VALIDACION");
    }

    @Test
    @DisplayName("#611 — las dos cifras viajan como dato, no dentro de la frase")
    void lasDosCifrasViajanComoDato() throws Exception {
        sembrar(municipalidadA, "20010500000000000000001", "CALLE 1", LOTE_DE_DENTRO);
        sembrar(municipalidadA, "20010500000000000000002", "CALLE 2", LOTE_DE_DENTRO);
        sembrar(municipalidadA, "20010500000000000000003", "CALLE 3", LOTE_DE_DENTRO);

        JsonNode error =
                JSON.readTree(
                        mvc.perform(get(RUTA).param("bbox", MARCO).param("limite", "2"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        List<String> detalles = new java.util.ArrayList<>();
        error.get("properties").get("detalles").forEach(d -> detalles.add(d.asText()));

        assertThat(detalles)
                .as(
                        "leerlas de la frase obliga a analizar castellano, y la frase se reescribe:"
                                + " reescribir el mensaje de MarcoConDemasiadosLotes sin tocar las"
                                + " cifras tiene que dejar esta prueba en verde")
                .containsExactly("lotes=3", "tope=2");
    }

    @Test
    @DisplayName("la respuesta no pagina: ni pagina, ni tamano, ni total")
    void laRespuestaNoPagina() throws Exception {
        sembrar(municipalidadA, "20010500000000000000001", "CALLE 1", LOTE_DE_DENTRO);

        JsonNode plano = pedir(MARCO);

        assertThat(plano.propertyNames())
                .as(
                        "«la pagina 2» de un plano no significa nada: no hay un orden que la"
                                + " convierta en una porcion del territorio")
                .containsExactlyInAnyOrder("lotes", "sinGeometria");
    }

    @Test
    @DisplayName("pedir mas del tope del servidor es 422 nombrando la cifra, no un recorte callado")
    void pedirMasDelTopeDelServidorEs422() throws Exception {
        MvcResult pasado =
                mvc.perform(
                                get(RUTA)
                                        .param("bbox", MARCO)
                                        .param(
                                                "limite",
                                                String.valueOf(
                                                        PlanoCatastralController.TOPE_DEL_SERVIDOR
                                                                + 1)))
                        .andReturn();

        assertThat(pasado.getResponse().getStatus()).isEqualTo(422);
        assertThat(pasado.getResponse().getContentAsString())
                .contains(String.valueOf(PlanoCatastralController.TOPE_DEL_SERVIDOR));
    }

    // ------------------------------------------------------------------
    // AC 4 y AC 10 — sinGeometria sale siempre y es cierto
    // ------------------------------------------------------------------

    @Test
    @DisplayName("de tres predios con un poligono: un lote y sinGeometria 2")
    void cuentaLosQueNoTienenPoligono() throws Exception {
        sembrar(municipalidadA, "20010500000000000000001", "CALLE 1", LOTE_DE_DENTRO);
        sembrar(municipalidadA, "20010500000000000000002", "CALLE 2", null);
        sembrar(municipalidadA, "20010500000000000000003", "CALLE 3", null);

        JsonNode plano = pedir(MARCO);

        assertThat(plano.get("lotes").size()).isEqualTo(1);
        assertThat(plano.get("sinGeometria").asLong())
                .as(
                        "sin esta cifra el visor diria «este sector tiene un lote», y lo que pasa"
                                + " es que tiene tres y dos no estan levantados")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("con los tres levantados sinGeometria es 0, y esta presente")
    void conTodosLevantadosLaCifraEsCeroYSale() throws Exception {
        sembrar(municipalidadA, "20010500000000000000001", "CALLE 1", LOTE_DE_DENTRO);
        sembrar(municipalidadA, "20010500000000000000002", "CALLE 2", LOTE_DE_DENTRO);
        sembrar(municipalidadA, "20010500000000000000003", "CALLE 3", LOTE_DE_DENTRO);

        JsonNode plano = pedir(MARCO);

        assertThat(plano.has("sinGeometria"))
                .as("«cero sin levantar» es una afirmacion util; omitirla la vuelve indistinguible")
                .isTrue();
        assertThat(plano.get("sinGeometria").asLong()).isZero();
    }

    @Test
    @DisplayName("los mismos filtros acotan la cuenta: el sector que se mira, no el padron entero")
    void laCuentaLaAcotanLosMismosFiltros() throws Exception {
        long sectorMirado = crearSector(municipalidadA, "SC-1", "Sector que se mira");
        long otroSector = crearSector(municipalidadA, "SC-2", "Sector de al lado");
        sembrarEnSector(municipalidadA, "20010500000000000000001", "CALLE 1", null, sectorMirado);
        sembrarEnSector(municipalidadA, "20010500000000000000002", "CALLE 2", null, otroSector);
        sembrarEnSector(municipalidadA, "20010500000000000000003", "CALLE 3", null, otroSector);

        JsonNode plano = pedir(MARCO, null, "SC-1");

        assertThat(plano.get("sinGeometria").asLong())
                .as(
                        "contar los del padron entero daria 3, que es la cifra plausible y"
                                + " equivocada: dos de ellos no son de este sector")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("el marco NO acota la cuenta: dos marcos disjuntos dan la misma cifra (#613)")
    void elMarcoNoAcotaLaCuentaDeLosQueNoTienenPoligono() throws Exception {
        sembrar(municipalidadA, "20010500000000000000001", "CALLE 1", LOTE_DE_DENTRO);
        sembrar(municipalidadA, "20010500000000000000002", "CALLE 2", null);
        sembrar(municipalidadA, "20010500000000000000003", "CALLE 3", null);

        JsonNode dondeEstaElDistrito = pedir(MARCO);
        JsonNode alOtroLadoDelPais = pedir(MARCO_VACIO);

        assertThat(dondeEstaElDistrito.get("lotes").size())
                .as("el primer marco tiene que contener el lote: sin eso los dos son el mismo caso")
                .isEqualTo(1);
        assertThat(alOtroLadoDelPais.get("lotes").size())
                .as("el segundo marco tiene que estar vacio de verdad")
                .isZero();

        assertThat(alOtroLadoDelPais.get("sinGeometria").asLong())
                .as(
                        "«sinGeometria» cuenta el padron con los mismos filtros, no el marco: si"
                                + " alguien «arregla» prediosSinGeometria metiendole EN_EL_MARCO,"
                                + " las cuatro columnas marco_* de un predio sin poligono son"
                                + " nulas, ninguna desigualdad se cumple y esto cae a 0 — la cifra"
                                + " se apagaria justo cuando mas hace falta (#613)")
                .isEqualTo(dondeEstaElDistrito.get("sinGeometria").asLong())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("hoy, sin un solo poligono cargado, el plano contesta la verdad y se distingue")
    void conCeroGeometriaCargadaContestaLaVerdad() throws Exception {
        sembrar(municipalidadA, "20010500000000000000001", "CALLE 1", null);
        sembrar(municipalidadA, "20010500000000000000002", "CALLE 2", null);

        JsonNode plano = pedir(MARCO);

        assertThat(plano.get("lotes"))
                .as("es el primer estado que la pantalla tiene que saber dibujar")
                .isEmpty();
        assertThat(plano.get("sinGeometria").asLong())
                .as("«aqui no hay levantamiento» no es «aqui no hay predios»")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // AC 5 — la lista de campos es cerrada
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ni el titular, ni un importe, ni un area: la lista de campos es cerrada")
    void laListaDeCamposEsCerrada() throws Exception {
        sembrar(municipalidadA, "20010500000000000000001", "CALLE 1", LOTE_DE_DENTRO);

        JsonNode lote = pedir(MARCO).get("lotes").get(0);

        assertThat(lote.propertyNames())
                .as(
                        "el titular se resuelve al clic en /catastro/predios/{id}/titulares"
                                + " (ADR-0015 §2.4), y el area del poligono no es la imponible"
                                + " (ADR-0021)")
                .containsExactlyInAnyOrderElementsOf(
                        Set.of(
                                "predioId",
                                "codRefCatastral",
                                "direccion",
                                "codigoDeSector",
                                "codigoDeManzana",
                                "lote",
                                "estado",
                                "geometria"));
    }

    @Test
    @DisplayName("un lote retirado del padron se dibuja y se dice: no desaparece del plano")
    void elLoteRetiradoSeDibujaYSeDice() throws Exception {
        sembrar(municipalidadA, "20010500000000000000001", "CALLE 1", LOTE_DE_DENTRO);
        long retirado =
                sembrar(municipalidadA, "20010500000000000000002", "CALLE 2", LOTE_DE_DENTRO);
        darDeBaja(municipalidadA, retirado);

        JsonNode plano = pedir(MARCO);

        assertThat(plano.get("lotes").size())
                .as(
                        "esconder el retirado deja un HUECO en el plano, y un hueco se lee como"
                                + " «ahi no hay lote» y no como «ese lote esta de baja»: es el"
                                + " defecto de ADR-0022 §2 producido por el filtro en vez de por el"
                                + " tope")
                .isEqualTo(2);

        JsonNode elRetirado =
                plano.get("lotes")
                        .valueStream()
                        .filter(l -> l.get("predioId").asLong() == retirado)
                        .findFirst()
                        .orElseThrow();
        assertThat(elRetirado.get("estado").asString())
                .as("y dibujarlo como uno mas diria que sigue en el padron")
                .isEqualTo("DADO_DE_BAJA");
    }

    // ------------------------------------------------------------------
    // AC 6 — aislamiento
    // ------------------------------------------------------------------

    @Test
    @DisplayName("dos municipalidades en el mismo marco: cada una ve la suya, y su cuenta")
    void elAislamientoSeSostiene() throws Exception {
        sembrar(municipalidadA, "20010500000000000000001", "CALLE DE LA A 1", LOTE_DE_DENTRO);
        sembrar(municipalidadA, "20010500000000000000009", "CALLE DE LA A 9", null);
        sembrar(municipalidadB, "20010600000000000000001", "CALLE DE LA B 1", LOTE_DE_DENTRO);
        sembrar(municipalidadB, "20010600000000000000002", "CALLE DE LA B 2", LOTE_DE_DENTRO);
        sembrar(municipalidadB, "20010600000000000000009", "CALLE DE LA B 9", null);

        JsonNode desdeA = pedir(MARCO);

        assertThat(desdeA.get("lotes").size())
                .as(
                        "con el pool conectado como superusuario —que omite RLS incluso con FORCE"
                                + " ROW LEVEL SECURITY— saldrian los tres")
                .isEqualTo(1);
        assertThat(desdeA.get("lotes").get(0).get("direccion").asString())
                .isEqualTo("CALLE DE LA A 1");
        assertThat(desdeA.get("sinGeometria").asLong())
                .as("y la cuenta de los no levantados tambien es solo la suya")
                .isEqualTo(1);

        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        JsonNode desdeB = pedir(MARCO);
        assertThat(desdeB.get("lotes").size()).isEqualTo(2);
        assertThat(desdeB.toString()).doesNotContain("CALLE DE LA A");
        assertThat(desdeB.get("sinGeometria").asLong()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // AC 7 — la lectura ocurre dentro de una transaccion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("la lectura entra en transaccion: sin ella, RLS la tumba con un 500")
    void laLecturaOcurreEnTransaccion() throws Exception {
        sembrar(municipalidadA, "20010500000000000000001", "CALLE 1", LOTE_DE_DENTRO);

        MvcResult respuesta = mvc.perform(get(RUTA).param("bbox", MARCO)).andReturn();

        assertThat(respuesta.getResponse().getStatus())
                .as(
                        "quitandole el @Transactional a ConsultaDelPlanoCatastral esto es 500 e"
                                + " «invalid input syntax for type bigint: \"\"»: sin SET LOCAL no"
                                + " hay contexto que la politica RLS pueda evaluar (#486)")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("el plano exige LECTURA sobre consulta_fichas, no el permiso de actualizar")
    void elPlanoExigeElPermisoDeBuscarUnPredio() {
        RequiereAccesoDelPlano acceso = accesoDeclarado();

        assertThat(acceso.acceso())
                .as("el mapa es la busqueda de un predio por otro camino (ADR-0022)")
                .isEqualTo("consulta_fichas");
        assertThat(acceso.privilegio())
                .as("pedir el de actualizar el catastro dejaria sin mapa a quien solo mira")
                .isEqualTo(kamayuk.rentas.autorizacion.Privilegio.LECTURA);
    }

    // ------------------------------------------------------------------

    private record RequiereAccesoDelPlano(
            String acceso, kamayuk.rentas.autorizacion.Privilegio privilegio) {}

    private static RequiereAccesoDelPlano accesoDeclarado() {
        kamayuk.rentas.autorizacion.RequiereAcceso anotacion =
                PlanoCatastralController.class.getAnnotation(
                        kamayuk.rentas.autorizacion.RequiereAcceso.class);
        assertThat(anotacion).as("el controlador tiene que declarar @RequiereAcceso").isNotNull();
        return new RequiereAccesoDelPlano(anotacion.acceso(), anotacion.privilegio());
    }

    /**
     * El codigo del catalogo, que es el discriminador estable (#611).
     *
     * <p>Se lee del campo y <b>nunca</b> del mensaje: comparar el texto es exactamente el defecto
     * que este issue existe para cerrar — con el discriminador quitado, una asercion sobre la frase
     * seguiria en verde.
     */
    private static String codigoDe(org.springframework.test.web.servlet.ResultActions peticion)
            throws Exception {
        JsonNode cuerpo = JSON.readTree(peticion.andReturn().getResponse().getContentAsString());
        return cuerpo.get("properties").get("codigo").asText();
    }

    private static JsonNode pedir(String marco) throws Exception {
        return pedir(marco, null, null);
    }

    private static JsonNode pedir(String marco, String limite) throws Exception {
        return pedir(marco, limite, null);
    }

    private static JsonNode pedir(
            String marco,
            @org.jspecify.annotations.Nullable String limite,
            @org.jspecify.annotations.Nullable String sector)
            throws Exception {
        var peticion = get(RUTA).param("bbox", marco);
        if (limite != null) {
            peticion = peticion.param("limite", limite);
        }
        if (sector != null) {
            peticion = peticion.param("codigoDeSector", sector);
        }
        MvcResult resultado = mvc.perform(peticion).andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as("la respuesta: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(resultado.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------
    // Siembra
    // ------------------------------------------------------------------

    private static long sembrar(
            long municipalidadId,
            String codigo,
            String direccion,
            @org.jspecify.annotations.Nullable String wkt)
            throws SQLException {
        return sembrarEnSector(municipalidadId, codigo, direccion, wkt, null);
    }

    /**
     * Un predio con —o sin— su poligono.
     *
     * <p>Se escribe con SQL directo y no por el caso de uso a proposito: {@code sgtm_app} no
     * escribe la geometria por HTTP (ADR-0021), entra por la carga cartografica, y lo que esta
     * prueba mide es la lectura.
     */
    private static long sembrarEnSector(
            long municipalidadId,
            String codigo,
            String direccion,
            @org.jspecify.annotations.Nullable String wkt,
            @org.jspecify.annotations.Nullable Long sectorId)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion, sector_id, estado, geometria)"
                                    + " VALUES (?, ?, 'URBANO', ?, ?, 'ACTIVO',"
                                    + "         ST_GeogFromText(?))"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, direccion);
                if (sectorId == null) {
                    sentencia.setNull(4, java.sql.Types.BIGINT);
                } else {
                    sentencia.setLong(4, sectorId);
                }
                sentencia.setString(5, wkt);
                try (var resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    /** Lo retira del padron por SQL directo: lo que aqui se mide es la lectura, no el acto. */
    private static void darDeBaja(long municipalidadId, long predioId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE predio SET estado = 'DADO_DE_BAJA' WHERE id = ?")) {
                sentencia.setLong(1, predioId);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static void vaciarPredios(long municipalidadId) throws SQLException {
        // Como owner: sgtm_app no tiene DELETE en ninguna tabla (regla 4), y aqui lo que se
        // limpia es la siembra de una prueba, no un dato del padron.
        //
        // Y CON su contexto de tenant fijado, que es lo que costo la primera corrida: `predio`
        // declara FORCE ROW LEVEL SECURITY, asi que el dueno de la tabla tambien queda sujeto a
        // la politica y sin SET LOCAL la sentencia no borra de menos, falla con «unrecognized
        // configuration parameter "app.municipalidad_id"». Quien omite RLS es el superusuario
        // del cluster, no el dueno.
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            ContextoDeTenant.fijar(owner, municipalidadId);
            try (PreparedStatement sentencia =
                    owner.prepareStatement("DELETE FROM predio WHERE municipalidad_id = ?")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.executeUpdate();
            }
            owner.commit();
        }
    }

    private static long crearSector(long municipalidadId, String codigo, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO sector (municipalidad_id, codigo, nombre)"
                                    + " VALUES (?, ?, ?) RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, nombre);
                try (var resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
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
            try (var resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    /**
     * El proxy que obedece a la anotacion, como el contenedor.
     *
     * <p>Es lo que convierte esta prueba en una medida y no en un montaje: quitarle el
     * {@code @Transactional} a {@code ConsultaDelPlanoCatastral} deja al proxy sin nada que hacer y
     * la lectura se cae con el error de RLS de verdad. Un {@code TransactionTemplate} incondicional
     * la habria dejado pasando con la anotacion quitada.
     */
    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }
}
