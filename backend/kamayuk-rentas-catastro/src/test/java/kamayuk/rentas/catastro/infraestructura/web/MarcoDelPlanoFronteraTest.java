package kamayuk.rentas.catastro.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
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
 * Donde esta lo levantado de esta municipalidad, de HTTP a PostgreSQL con PostGIS (#612).
 *
 * <h2>Que problema mide</h2>
 *
 * <p>{@code GET /catastro/predios/plano} exige {@code bbox} y <b>ninguna operacion del contrato
 * decia donde esta la municipalidad</b>: ni su extension, ni la de un sector, ni un centroide, ni
 * un ubigeo resoluble a coordenadas. El visor abria por eso sobre un marco declarado —el Peru
 * continental— y el dia que se cargue el primer plano ese marco contiene mas lotes que el tope: la
 * respuesta pasa a ser «acercate», que es correcta y no se puede obedecer.
 *
 * <h2>Por que va hasta la base, como su hermana</h2>
 *
 * <p>Porque lo que hay que demostrar no se puede demostrar de otro modo. El marco lo <b>calcula el
 * motor</b> agregando las cuatro columnas que el propio motor deriva del poligono ({@code V65}), y
 * el aislamiento lo sostiene la politica RLS, que ningun doble tiene. La conexion es la de {@code
 * sgtm_app}: el superusuario del cluster omite RLS incluso con {@code FORCE ROW LEVEL SECURITY},
 * asi que una prueba escrita sobre el no verificaria ningun aislamiento — y escribirla con {@code
 * sgtm_owner} tampoco, porque el dueno de la tabla SI queda sujeto a la politica (#537, #545).
 */
@DisplayName("#612 — El marco de lo levantado, de HTTP a PostgreSQL")
class MarcoDelPlanoFronteraTest {

    private static final String RUTA = "/rentas/api/v1/catastro/predios/plano/marco";
    private static final String RUTA_DEL_PLANO = "/rentas/api/v1/catastro/predios/plano";

    /**
     * Dos lotes en extremos opuestos del distrito, y el rectangulo que los envuelve.
     *
     * <p>Un decimo de grado los separa —unos once kilometros—: lejos de cualquier redondeo y cerca
     * de lo que separa dos sectores de un distrito. Se escriben con pocos decimales a proposito,
     * para que la comparacion contra lo que devuelve el motor sea exacta.
     */
    private static final String LOTE_SUROESTE =
            "MULTIPOLYGON(((-80.68 -5.26, -80.679 -5.26, -80.679 -5.259, -80.68 -5.259,"
                    + " -80.68 -5.26)))";

    private static final String LOTE_NORESTE =
            "MULTIPOLYGON(((-80.58 -5.16, -80.579 -5.16, -80.579 -5.159, -80.58 -5.159,"
                    + " -80.58 -5.16)))";

    /**
     * Un lote de area cero, con sus cuatro vertices sobre el mismo meridiano.
     *
     * <p>PostGIS lo <b>acepta</b> —medido contra 3.4: {@code ST_GeogFromText} lo lee y {@code
     * ST_XMin} y {@code ST_XMax} salen iguales—, y el unico camino que escribe geometria en este
     * sistema es la carga cartografica, que no comprueba el area (ADR-0021). Asi que un marco
     * degenerado es alcanzable, y publicarlo seria publicar un {@code bbox} que la operacion del
     * plano rechaza con 422.
     */
    private static final String LOTE_DEGENERADO =
            "MULTIPOLYGON(((-80.68 -5.26, -80.68 -5.25, -80.68 -5.24, -80.68 -5.26)))";

    /** Un lote de la municipalidad vecina, a mas de mil kilometros: el Titicaca. */
    private static final String LOTE_DE_LA_VECINA =
            "MULTIPOLYGON(((-69.5 -16.2, -69.499 -16.2, -69.499 -16.199, -69.5 -16.199,"
                    + " -69.5 -16.2)))";

    /** Margen de comparacion de una coordenada: seis ordenes por debajo de un milimetro. */
    private static final double MARGEN = 1e-9;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static JdbcClient jdbc;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("200107", "Municipalidad del marco");
        municipalidadB = crearMunicipalidad("200108", "Municipalidad vecina del marco");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
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
    // El centinela: sin el, cambiar el rol del pool dejaria de medirse
    // ------------------------------------------------------------------

    @Test
    @DisplayName("la prueba se conecta como sgtm_app, que es lo unico que hace medible el resto")
    void seConectaComoSgtmApp() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                .as(
                        "con sgtm_owner las pruebas de aislamiento pasan en verde sin medir nada:"
                                + " FORCE ROW LEVEL SECURITY sujeta tambien al dueno (#537, #545)")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    // ------------------------------------------------------------------
    // AC 1 — el marco sale de la geometria cargada, no de una constante
    // ------------------------------------------------------------------

    @Test
    @DisplayName("dos lotes en extremos opuestos: el marco los envuelve a los dos")
    void elMarcoEnvuelveLosDosLotes() throws Exception {
        sembrar(municipalidadA, "20010700000000000000001", "CALLE SUROESTE 1", LOTE_SUROESTE, null);
        sembrar(municipalidadA, "20010700000000000000002", "CALLE NORESTE 1", LOTE_NORESTE, null);

        JsonNode respuesta = pedir(null, null);
        JsonNode marco = respuesta.get("marco");

        assertThat(marco)
                .as("con geometria cargada tiene que haber marco: %s", respuesta)
                .isNotNull();
        assertThat(marco.get("oeste").asDouble())
                .as("el oeste es el MINIMO de los dos, no el del primero ni el maximo")
                .isCloseTo(-80.68, within(MARGEN));
        assertThat(marco.get("sur").asDouble()).isCloseTo(-5.26, within(MARGEN));
        assertThat(marco.get("este").asDouble())
                .as("el este es el MAXIMO: con el minimo, el lote del noreste queda fuera")
                .isCloseTo(-80.579, within(MARGEN));
        assertThat(marco.get("norte").asDouble()).isCloseTo(-5.159, within(MARGEN));

        assertThat(respuesta.get("lotes").asLong())
                .as("cuantos lotes levantados componen el marco")
                .isEqualTo(2);
        assertThat(respuesta.get("notaDelMarco").isNull())
                .as("con marco no hay nada que explicar")
                .isTrue();
    }

    @Test
    @DisplayName("al filtrar por el sector de uno, el marco es el suyo y no el de los dos")
    void alFiltrarPorSectorDevuelveElSuyo() throws Exception {
        long sectorSuroeste = crearSector(municipalidadA, "SC-1", "Sector del suroeste");
        long sectorNoreste = crearSector(municipalidadA, "SC-2", "Sector del noreste");
        sembrar(
                municipalidadA,
                "20010700000000000000001",
                "CALLE SUROESTE 1",
                LOTE_SUROESTE,
                sectorSuroeste);
        sembrar(
                municipalidadA,
                "20010700000000000000002",
                "CALLE NORESTE 1",
                LOTE_NORESTE,
                sectorNoreste);

        JsonNode delSector = pedir("SC-1", null);
        JsonNode marco = delSector.get("marco");

        assertThat(delSector.get("lotes").asLong())
                .as("el sector tiene uno de los dos")
                .isEqualTo(1);
        assertThat(marco.get("oeste").asDouble()).isCloseTo(-80.68, within(MARGEN));
        assertThat(marco.get("este").asDouble())
                .as(
                        "sin el filtro el este seria -80.579, el del lote del OTRO sector: el visor"
                                + " abriria sobre once kilometros de territorio que no va a dibujar")
                .isCloseTo(-80.679, within(MARGEN));
        assertThat(marco.get("norte").asDouble()).isCloseTo(-5.259, within(MARGEN));
    }

    @Test
    @DisplayName("el marco que devuelve sirve de 'bbox' y el plano dibuja dentro de el sus lotes")
    void elMarcoDevueltoSirveDeBbox() throws Exception {
        sembrar(municipalidadA, "20010700000000000000001", "CALLE SUROESTE 1", LOTE_SUROESTE, null);
        sembrar(municipalidadA, "20010700000000000000002", "CALLE NORESTE 1", LOTE_NORESTE, null);

        JsonNode marco = pedir(null, null).get("marco");
        String bbox =
                marco.get("oeste").asString()
                        + ","
                        + marco.get("sur").asString()
                        + ","
                        + marco.get("este").asString()
                        + ","
                        + marco.get("norte").asString();

        MvcResult plano = mvc.perform(get(RUTA_DEL_PLANO).param("bbox", bbox)).andReturn();

        assertThat(plano.getResponse().getStatus())
                .as(
                        "es la mitad del issue: lo que esta lectura publica tiene que poder volver"
                                + " tal cual como el marco del plano. La respuesta: %s",
                        plano.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(JSON.readTree(plano.getResponse().getContentAsString()).get("lotes").size())
                .as("y contener todo lo levantado, que es para lo que se calculo")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // AC 2 — sin ningun poligono, dice que no hay marco
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sin un solo poligono no hay marco, y se dice por que: es el estado de hoy")
    void sinNingunPoligonoDiceQueNoHayMarco() throws Exception {
        sembrar(municipalidadA, "20010700000000000000001", "CALLE 1", null, null);
        sembrar(municipalidadA, "20010700000000000000002", "CALLE 2", null, null);

        JsonNode respuesta = pedir(null, null);

        assertThat(respuesta.get("marco").isNull())
                .as(
                        "devolver 0,0,0,0 encuadraria el visor sobre el golfo de Guinea, y sobre un"
                                + " plano sin base cartografica eso no se ve. Ademas ni se puede"
                                + " construir: MarcoGeografico rechaza el rectangulo degenerado")
                .isTrue();
        assertThat(respuesta.get("lotes").asLong())
                .as("cero lotes levantados, que es lo que separa esta ausencia de la otra")
                .isZero();
        assertThat(respuesta.get("notaDelMarco").asString())
                .as("una ausencia sin motivo se lee como «esta municipalidad no existe»")
                .contains("carga cartografica");
    }

    @Test
    @DisplayName("un sector sin lotes levantados tampoco tiene marco, y no es un 404")
    void unSectorSinLevantamientoNoTieneMarco() throws Exception {
        long conLote = crearSector(municipalidadA, "SC-1", "Sector levantado");
        long sinLote = crearSector(municipalidadA, "SC-2", "Sector sin levantar");
        sembrar(municipalidadA, "20010700000000000000001", "CALLE 1", LOTE_SUROESTE, conLote);
        sembrar(municipalidadA, "20010700000000000000002", "CALLE 2", null, sinLote);

        JsonNode respuesta = pedir("SC-2", null);

        assertThat(respuesta.get("marco").isNull()).isTrue();
        assertThat(respuesta.get("lotes").asLong()).isZero();
    }

    @Test
    @DisplayName("todo lo levantado sobre la misma linea no es un rectangulo, y se dice")
    void unLevantamientoDegeneradoNoSeDaComoMarco() throws Exception {
        sembrar(municipalidadA, "20010700000000000000001", "CALLE 1", LOTE_DEGENERADO, null);

        JsonNode respuesta = pedir(null, null);

        assertThat(respuesta.get("marco").isNull())
                .as(
                        "MarcoGeografico exige oeste < este; sin esta rama la lectura revienta con"
                                + " 500 sobre un padron que el propio sistema acepto")
                .isTrue();
        assertThat(respuesta.get("lotes").asLong())
                .as("y la cuenta lo separa del caso de arriba: aqui SI hay geometria")
                .isEqualTo(1);
        assertThat(respuesta.get("notaDelMarco").asString()).contains("misma linea");
    }

    // ------------------------------------------------------------------
    // AC 4 — no es una via de fuga del padron
    // ------------------------------------------------------------------

    @Test
    @DisplayName("un rectangulo y una cuenta: ni un identificador, ni un codigo, ni una direccion")
    void laListaDeCamposEsCerrada() throws Exception {
        sembrar(municipalidadA, "20010700000000000000001", "CALLE SUROESTE 1", LOTE_SUROESTE, null);

        JsonNode respuesta = pedir(null, null);

        assertThat(respuesta.propertyNames())
                .as(
                        "anadirle el predioId del lote mas al norte la convertiria en una forma de"
                                + " recorrer el padron sin pedir el padron")
                .containsExactlyInAnyOrderElementsOf(Set.of("marco", "lotes", "notaDelMarco"));
        assertThat(respuesta.get("marco").propertyNames())
                .containsExactlyInAnyOrderElementsOf(Set.of("oeste", "sur", "este", "norte"));
        assertThat(respuesta.toString())
                .as("ni la direccion ni el codigo de referencia catastral del lote que lo compone")
                .doesNotContain("CALLE SUROESTE")
                .doesNotContain("20010700000000000000001");
    }

    @Test
    @DisplayName("dos municipalidades: el marco de cada una es el suyo, no el de las dos")
    void elAislamientoSeSostiene() throws Exception {
        sembrar(municipalidadA, "20010700000000000000001", "CALLE DE LA A 1", LOTE_SUROESTE, null);
        sembrar(
                municipalidadB,
                "20010800000000000000001",
                "CALLE DE LA B 1",
                LOTE_DE_LA_VECINA,
                null);

        JsonNode desdeA = pedir(null, null);

        assertThat(desdeA.get("lotes").asLong())
                .as(
                        "con el pool conectado como superusuario del cluster —que omite RLS incluso"
                                + " con FORCE ROW LEVEL SECURITY— saldrian los dos")
                .isEqualTo(1);
        assertThat(desdeA.get("marco").get("este").asDouble())
                .as(
                        "y el marco se estiraria mil kilometros hasta el Titicaca: un encuadre que"
                                + " no contiene nada de lo que despues se dibuja")
                .isCloseTo(-80.679, within(MARGEN));

        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        JsonNode desdeB = pedir(null, null);
        assertThat(desdeB.get("lotes").asLong()).isEqualTo(1);
        assertThat(desdeB.get("marco").get("oeste").asDouble()).isCloseTo(-69.5, within(MARGEN));
    }

    @Test
    @DisplayName("el marco exige LECTURA sobre consulta_fichas, el mismo permiso que el plano")
    void elMarcoExigeElPermisoDelPlano() throws NoSuchMethodException {
        RequiereAcceso deLaClase =
                PlanoCatastralController.class.getAnnotation(RequiereAcceso.class);
        RequiereAcceso delMetodo =
                PlanoCatastralController.class
                        .getMethod("marco", String.class, String.class)
                        .getAnnotation(RequiereAcceso.class);

        assertThat(delMetodo)
                .as(
                        "el metodo NO declara el suyo: hereda el de la clase, que es el correcto."
                                + " Declararle otro seria pedir un permiso distinto para el"
                                + " encuadre del mismo mapa")
                .isNull();
        assertThat(deLaClase.acceso()).isEqualTo("consulta_fichas");
        assertThat(deLaClase.privilegio()).isEqualTo(Privilegio.LECTURA);
    }

    // ------------------------------------------------------------------
    // La lectura ocurre dentro de una transaccion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("la lectura entra en transaccion: sin ella, RLS la tumba con un 500")
    void laLecturaOcurreEnTransaccion() throws Exception {
        sembrar(municipalidadA, "20010700000000000000001", "CALLE 1", LOTE_SUROESTE, null);

        MvcResult respuesta = mvc.perform(get(RUTA)).andReturn();

        assertThat(respuesta.getResponse().getStatus())
                .as(
                        "quitandole el @Transactional a ConsultaDelPlanoCatastral.marcoDe esto es"
                                + " 500 e «invalid input syntax for type bigint: \"\"»: sin SET"
                                + " LOCAL no hay contexto que la politica RLS pueda evaluar (#486)."
                                + " La respuesta: %s",
                        respuesta.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------

    private static JsonNode pedir(
            @org.jspecify.annotations.Nullable String sector,
            @org.jspecify.annotations.Nullable String manzana)
            throws Exception {
        var peticion = get(RUTA);
        if (sector != null) {
            peticion = peticion.param("codigoDeSector", sector);
        }
        if (manzana != null) {
            peticion = peticion.param("codigoDeManzana", manzana);
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

    /**
     * Un predio con —o sin— su poligono, por SQL directo.
     *
     * <p>Como en {@code PlanoCatastralFronteraTest}: {@code sgtm_app} no escribe la geometria por
     * HTTP (ADR-0021), entra por la carga cartografica, y lo que aqui se mide es la lectura.
     */
    private static long sembrar(
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

    private static void vaciarPredios(long municipalidadId) throws SQLException {
        // Como owner —sgtm_app no tiene DELETE en ninguna tabla (regla 4)— y CON su contexto de
        // tenant fijado: `predio` declara FORCE ROW LEVEL SECURITY, asi que el dueno tambien queda
        // sujeto a la politica y sin SET LOCAL la sentencia falla con «unrecognized configuration
        // parameter».
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            ContextoDeTenant.fijar(owner, municipalidadId);
            try (PreparedStatement predios =
                    owner.prepareStatement("DELETE FROM predio WHERE municipalidad_id = ?")) {
                predios.setLong(1, municipalidadId);
                predios.executeUpdate();
            }
            try (PreparedStatement sectores =
                    owner.prepareStatement("DELETE FROM sector WHERE municipalidad_id = ?")) {
                sectores.setLong(1, municipalidadId);
                sectores.executeUpdate();
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
     * {@code @Transactional} a {@code marcoDe} deja al proxy sin nada que hacer y la lectura se cae
     * con el error de RLS de verdad. Un {@code TransactionTemplate} incondicional la habria dejado
     * pasando con la anotacion quitada.
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
