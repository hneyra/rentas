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
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.aplicacion.ConsultaDeSectores;
import kamayuk.rentas.catastro.aplicacion.RegistrarManzana;
import kamayuk.rentas.catastro.aplicacion.RegistrarSector;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
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
 * Las manzanas de un sector, de HTTP a PostgreSQL y sin un doble por el camino (#537).
 *
 * <h2>Por que va hasta la base</h2>
 *
 * <p>Porque las cuatro cosas que este issue tiene que demostrar no se pueden demostrar de otro
 * modo. El <b>aislamiento</b> —que la lista solo trae las manzanas de la municipalidad del token—
 * lo sostiene la politica RLS, que un doble no tiene. Los <b>conteos</b> los hace la base sobre
 * {@code predio}, con su filtro de estado y su {@code count(DISTINCT lote)}: contarlos en Java
 * seria comprobar la cuenta de la prueba y no la del sistema. La <b>paginacion</b> ordena en el
 * motor, y lo que se quiere saber es que dos paginas consecutivas no repiten ni se saltan una fila.
 * Y la lectura entera <b>corre en una transaccion o no corre</b>: sin ella no hay {@code SET LOCAL
 * app.municipalidad_id} y la politica RLS no devuelve vacio —revienta con «invalid input syntax for
 * type bigint: ""», el defecto de #486—.
 *
 * <p>La conexion es la de {@code sgtm_app}. Un superusuario omite RLS incluso con {@code FORCE ROW
 * LEVEL SECURITY}, asi que una prueba escrita sobre el no verificaria ningun aislamiento.
 *
 * <p>El proxy transaccional se construye con {@link AnnotationTransactionAttributeSource}, o sea
 * <b>obedeciendo a la anotacion</b> como haria el contenedor: envolver el caso de uso en un {@code
 * TransactionTemplate} incondicional dejaria la prueba pasando con el {@code @Transactional}
 * quitado, que es justo el modo de fallo que existe para impedir.
 */
@DisplayName("RF-005 — Las manzanas de un sector, de HTTP a PostgreSQL (#537)")
class ManzanasDelSectorFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("260101", "Municipalidad del territorio");
        municipalidadB = crearMunicipalidad("260102", "Municipalidad vecina");

        // ── La municipalidad A ────────────────────────────────────────────
        // «SC-1» tiene tres manzanas, y la de en medio es la que mas dice: dos
        // predios activos del MISMO lote (dos departamentos), uno dado de baja
        // y uno sin lote. Asi «predios» y «lotes» no pueden salir iguales por
        // casualidad, y el dado de baja no puede colarse sin que se note.
        long sectorA = sembrarSector(municipalidadA, "SC-1", "Sector Centro");
        long m001 = sembrarManzana(municipalidadA, sectorA, "001");
        long m002 = sembrarManzana(municipalidadA, sectorA, "002");
        sembrarManzana(municipalidadA, sectorA, "003");

        sembrarPredio(municipalidadA, "26010100010001000100001", sectorA, m001, "001", "ACTIVO");
        sembrarPredio(municipalidadA, "26010100010001000100002", sectorA, m001, "002", "ACTIVO");

        sembrarPredio(municipalidadA, "26010100010002000100001", sectorA, m002, "007", "ACTIVO");
        sembrarPredio(municipalidadA, "26010100010002000100002", sectorA, m002, "007", "ACTIVO");
        sembrarPredio(municipalidadA, "26010100010002000100003", sectorA, m002, null, "ACTIVO");
        sembrarPredio(
                municipalidadA, "26010100010002000100004", sectorA, m002, "009", "DADO_DE_BAJA");

        // Un sector con cero manzanas: es la respuesta que NO puede confundirse
        // con el 404 de un sector que no existe.
        sembrarSector(municipalidadA, "SC-VACIO", "Sector sin manzanas");

        // ── La municipalidad B ────────────────────────────────────────────
        // El mismo codigo de sector y el mismo codigo de manzana: si el
        // aislamiento fallara, saldrian mezcladas y nada en la respuesta lo
        // diria —los codigos son iguales—.
        long sectorB = sembrarSector(municipalidadB, "SC-1", "Sector Centro de la vecina");
        long manzanaB = sembrarManzana(municipalidadB, sectorB, "001");
        sembrarPredio(
                municipalidadB, "26010200010001000100001", sectorB, manzanaB, "050", "ACTIVO");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        CatastroRepositoryJdbc catastro = new CatastroRepositoryJdbc(jdbc);
        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        ComprobadorDeAcceso todoPermitido = (usuario, acceso, privilegio, fecha) -> true;

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new SectorController(
                                        envolver(new ConsultaDeSectores(catastro), gestor),
                                        envolver(
                                                new RegistrarSector(catastro, auditoria, RELOJ),
                                                gestor),
                                        envolver(
                                                new RegistrarManzana(catastro, auditoria, RELOJ),
                                                gestor),
                                        todoPermitido,
                                        RELOJ))
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
        OrigenContext.fijar(new Origen("tecnico.catastro", "PC-05", "10.0.0.5"));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("lista las manzanas del sector, por codigo y con su sector dentro de cada fila")
    void listaLasManzanasDelSector() throws Exception {
        MvcResult resultado = manzanasDe("SC-1", "");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "sin el @Transactional del caso de uso, la politica RLS no devuelve vacio:"
                                + " falla con «invalid input syntax for type bigint: \"\"» y esto"
                                + " seria 500 (#486)")
                .isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"totalElementos\":3");
        assertThat(codigosDe(cuerpo))
                .as("por codigo, que es el orden en que se recorre una zona al sanearla")
                .containsExactly("001", "002", "003");
        assertThat(cuerpo)
                .as(
                        "cada fila dice de que sector cuelga, sin que la interfaz lo tenga que recordar")
                .contains("\"sectorCodigo\":\"SC-1\"");
    }

    @Test
    @DisplayName(
            "los conteos son de la base: el dado de baja no cuenta y dos unidades de un lote son un lote")
    void losConteosSonDeLaBase() throws Exception {
        String cuerpo = manzanasDe("SC-1", "").getResponse().getContentAsString();

        assertThat(unaManzana(cuerpo, "001"))
                .as("dos predios activos en dos lotes distintos")
                .contains("\"predios\":2")
                .contains("\"lotes\":2");
        assertThat(unaManzana(cuerpo, "002"))
                .as(
                        "tres activos —dos del lote 007 y uno sin lote— y uno dado de baja: 3"
                                + " predios y 1 lote. El de baja sigue en la base porque aparece en"
                                + " determinaciones ya emitidas (RNF-051), y la manzana ya no lo"
                                + " tiene")
                .contains("\"predios\":3")
                .contains("\"lotes\":1");
        assertThat(unaManzana(cuerpo, "003"))
                .as("una manzana de la pagina sin ningun predio es un cero contado, no un nulo")
                .contains("\"predios\":0")
                .contains("\"lotes\":0");
    }

    @Test
    @DisplayName("un sector que existe y no tiene manzanas es 200 con cero filas")
    void elSectorSinManzanasEsUnaPaginaVacia() throws Exception {
        MvcResult resultado = manzanasDe("SC-VACIO", "");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"totalElementos\":0")
                .contains("\"contenido\":[]");
    }

    @Test
    @DisplayName("un sector que no existe es 404 nombrandolo, no una pagina vacia")
    void elSectorInexistenteEs404() throws Exception {
        MvcResult resultado = manzanasDe("SC-NADA", "");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "cero filas significa «ese sector todavia no tiene manzanas», que es lo"
                                + " contrario de «ese codigo no esta en el catalogo»")
                .isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("SC-NADA")
                .doesNotContain("contenido");
    }

    @Test
    @DisplayName("pagina: dos paginas consecutivas no repiten una fila ni se saltan otra")
    void paginaSinRepetirNiSaltarse() throws Exception {
        String primera =
                manzanasDe("SC-1", "&pagina=0&tamano=2").getResponse().getContentAsString();
        String segunda =
                manzanasDe("SC-1", "&pagina=1&tamano=2").getResponse().getContentAsString();

        assertThat(codigosDe(primera)).containsExactly("001", "002");
        assertThat(codigosDe(segunda)).containsExactly("003");
        assertThat(primera).contains("\"totalPaginas\":2").contains("\"hayMas\":true");
        assertThat(segunda).contains("\"hayMas\":false");
    }

    @Test
    @DisplayName("la municipalidad vecina ve SUS manzanas del mismo codigo de sector, no las de A")
    void elAislamientoSeSostiene() throws Exception {
        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        String cuerpo = manzanasDe("SC-1", "").getResponse().getContentAsString();

        assertThat(cuerpo)
                .as(
                        "con el pool conectado como superusuario saldrian las cuatro, y los codigos"
                                + " son los mismos: nada en la respuesta lo diria")
                .contains("\"totalElementos\":1");
        assertThat(codigosDe(cuerpo)).containsExactly("001");
        assertThat(unaManzana(cuerpo, "001"))
                .as("y su conteo es el de SU predio, no el de los de la municipalidad A")
                .contains("\"predios\":1");
    }

    @Test
    @DisplayName("ordenar por un campo que no esta en la lista blanca es 422, no una inyeccion")
    void elOrdenSeValidaContraLaListaBlanca() throws Exception {
        MvcResult resultado = manzanasDe("SC-1", "&ordenarPor=(SELECT+1)");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("No se puede ordenar por");
    }

    @Test
    @DisplayName("la lectura hereda el LECTURA de la clase, y la clase lo declara")
    void laLecturaExigeLectura() throws Exception {
        // La regla de ArchUnit admite `@RequiereAcceso` «en la clase o en cada endpoint», asi que
        // no exige nada del metodo: si la clase declarara REGISTRO —como pasaba en #431 con
        // `fisc_programa`—, esta lectura pediria en silencio el privilegio de escribir y quien solo
        // consulta el territorio no podria abrir la pantalla. Se comprueba aqui porque alli no se
        // puede.
        assertThat(
                        SectorController.class
                                .getMethod(
                                        "listarManzanas",
                                        String.class,
                                        ParametrosDePaginacion.class)
                                .getAnnotation(RequiereAcceso.class))
                .as("no la declara: la hereda, y por eso hay que mirar la de la clase")
                .isNull();
        RequiereAcceso deLaClase = SectorController.class.getAnnotation(RequiereAcceso.class);
        assertThat(deLaClase.acceso()).isEqualTo("sectores");
        assertThat(deLaClase.privilegio()).isEqualTo(Privilegio.LECTURA);
    }

    // ------------------------------------------------------------------

    private static MvcResult manzanasDe(String sector, String extra) throws Exception {
        return mvc.perform(get("/rentas/api/v1/catastro/sectores/" + sector + "/manzanas?" + extra))
                .andReturn();
    }

    /** Los codigos de manzana del cuerpo, en el orden en que salieron. */
    private static java.util.List<String> codigosDe(String cuerpo) {
        java.util.regex.Matcher casa =
                java.util.regex.Pattern.compile("\"codigo\":\"([^\"]+)\"").matcher(cuerpo);
        java.util.List<String> codigos = new java.util.ArrayList<>();
        while (casa.find()) {
            codigos.add(casa.group(1));
        }
        return codigos;
    }

    /** El trozo del JSON de una manzana, para no leer los conteos de la fila de al lado. */
    private static String unaManzana(String cuerpo, String codigo) {
        java.util.regex.Matcher casa =
                java.util.regex.Pattern.compile("\\{[^{}]*\"codigo\":\"" + codigo + "\"[^{}]*\\}")
                        .matcher(cuerpo);
        assertThat(casa.find())
                .as("la manzana " + codigo + " tenia que estar en la pagina")
                .isTrue();
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

    private static long sembrarSector(long municipalidadId, String codigo, String nombre)
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
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long sembrarManzana(long municipalidadId, long sectorId, String codigo)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO manzana (municipalidad_id, sector_id, codigo)"
                                    + " VALUES (?, ?, ?) RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setLong(2, sectorId);
                sentencia.setString(3, codigo);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static void sembrarPredio(
            long municipalidadId,
            String codigo,
            long sectorId,
            long manzanaId,
            @Nullable String lote,
            String estado)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion, sector_id, manzana_id, lote, estado)"
                                    + " VALUES (?, ?, 'URBANO', ?, ?, ?, ?, ?)")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, "PREDIO " + codigo);
                sentencia.setLong(4, sectorId);
                sentencia.setLong(5, manzanaId);
                sentencia.setString(6, lote);
                sentencia.setString(7, estado);
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
