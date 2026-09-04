package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeMuestra;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeProgramas;
import kamayuk.rentas.fiscalizacion.aplicacion.DeteccionDeOmisos;
import kamayuk.rentas.fiscalizacion.aplicacion.EstadoDeCuentaDeFiscalizacion;
import kamayuk.rentas.fiscalizacion.aplicacion.GenerarMuestra;
import kamayuk.rentas.fiscalizacion.aplicacion.RegistrarPrograma;
import kamayuk.rentas.fiscalizacion.dobles.LiquidacionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.TitularesDeMentira;
import kamayuk.rentas.fiscalizacion.infraestructura.ActaFiscalizacionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.DeteccionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.MuestraDelProgramaRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.ProgramaFiscalizacionRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * Lo que la detección aporta al programa son sus filtros, y llegan enteros (#550, ADR-0023).
 *
 * <h2>Qué se decidió, y qué había que demostrar</h2>
 *
 * <p>#550 preguntaba si «Programar fiscalización» debía mandar la <b>lista de predios marcados</b>
 * —salida (b)— o si la muestra se sigue <b>sorteando</b> a partir de los parámetros del programa
 * —salida (a)—. ADR-0023 eligió (a), y esa respuesta sólo vale si es cierta: lo que la detección
 * enseña con unos filtros tiene que ser lo que el programa sortea con esos mismos filtros. Si no lo
 * fuera, el funcionario que marcó dos predios tendría razón en querer mandarlos.
 *
 * <p>Aquí se recorre entero: se pide la detección con los tres filtros de la pantalla, se registra
 * el programa con esos mismos tres, se sortea, y se comparan <b>los códigos de referencia catastral
 * de las dos respuestas</b>. Ninguna de las dos mitades usa un doble.
 *
 * <h2>Y no llegaban</h2>
 *
 * <p>Hasta este issue, «Todos» —el literal del desplegable de sector, que {@code OmisosController}
 * lee como «sin filtro» desde siempre— se guardaba <b>literal</b> en {@code
 * programa_fiscalizacion.sector_codigo}, y el sorteo filtra {@code s.codigo = 'Todos'}: un programa
 * que no puede encontrar nunca ningún predio, y cuyo único síntoma es una muestra de cero —
 * indistinguible de «en ese sector no hay omisos». Y «Todas», el literal equivalente del
 * desplegable de condición, contestaba «Criterio de riesgo desconocido», que no es lo que es.
 *
 * <h2>Por qué de HTTP a PostgreSQL</h2>
 *
 * <p>Porque el defecto vive justo en la frontera que las dos familias de pruebas se reparten sin
 * cruzar (#486): la lectura del filtro es de la capa web y su consecuencia —cuántas filas trae el
 * {@code WHERE}— es del motor. La conexión es la de {@code sgtm_app}: un superusuario omite RLS
 * incluso con {@code FORCE ROW LEVEL SECURITY}, y {@code sgtm_owner} tampoco sirve, porque con
 * {@code FORCE} el dueño también queda sujeto a la política (#537, #545).
 *
 * <p>Los casos de uso van envueltos con {@link AnnotationTransactionAttributeSource}, o sea
 * <b>obedeciendo a la anotación</b> como el contenedor: un {@code TransactionTemplate}
 * incondicional dejaría la prueba pasando con el {@code @Transactional} quitado (#486).
 *
 * <p>El orden importa: una muestra se sortea <b>una vez</b> ({@code MuestraYaSorteada}, 409) y un
 * programa abierto se lleva sus predios, así que el segundo caso mide además la exclusión.
 */
@DisplayName("#550 — La deteccion programa con sus filtros, de HTTP a PostgreSQL")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProgramarDesdeLaDeteccionFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);

    private static final String EJERCICIO = "2026";

    /** Los dos literales que el manual escribe en sus desplegables para decir «sin filtro». */
    private static final String TODOS = "Todos";

    private static final String TODAS = "Todas";

    private static final String SECTOR_A = "PD-01";
    private static final String SECTOR_B = "PD-02";

    private static final int PRIMER_CODIGO = 910;

    /** Los dos omisos del sector A, el omiso del sector B, y uno que declaró bien. */
    private static final String OMISO_A1 = codigoDe(0);

    private static final String OMISO_A2 = codigoDe(1);
    private static final String OMISO_B = codigoDe(2);
    private static final String CONFORME_A = codigoDe(3);

    /** El omiso de la municipalidad vecina, en un sector que se llama igual. */
    private static final String DE_LA_VECINA = codigoDe(90);

    private static final Pattern CODIGO_EN_LA_RESPUESTA =
            Pattern.compile("\"codRefCatastral\":\"(\\d+)\"");

    private static final Pattern ID_DEL_PROGRAMA = Pattern.compile("\"id\":(\\d+)");

    private static final AtomicInteger SIGUIENTE_VERSION = new AtomicInteger(1);
    private static final AtomicInteger SIGUIENTE_DJ = new AtomicInteger(1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static JdbcClient jdbc;
    private static MockMvc mvc;

    private static final List<RegistroDeAuditoria> AUDITADOS = new ArrayList<>();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("260901", "Municipalidad que programa");
        municipalidadB = crearMunicipalidad("260902", "Municipalidad vecina");

        long titularA = crearContribuyente(municipalidadA, "PD-00001", "70900001");
        crearSector(municipalidadA, SECTOR_A);
        crearSector(municipalidadA, SECTOR_B);
        sembrarOmiso(municipalidadA, OMISO_A1, SECTOR_A);
        sembrarOmiso(municipalidadA, OMISO_A2, SECTOR_A);
        sembrarOmiso(municipalidadA, OMISO_B, SECTOR_B);
        sembrarConforme(municipalidadA, titularA, CONFORME_A, SECTOR_A);

        // La vecina, con un omiso en un sector que se llama IGUAL: sin RLS entraria en la muestra
        // del programa que acota por SECTOR_A, y ahi no lo distinguiria ninguna cifra.
        long titularB = crearContribuyente(municipalidadB, "PD-B0001", "70900002");
        crearSector(municipalidadB, SECTOR_A);
        sembrarOmiso(municipalidadB, DE_LA_VECINA, SECTOR_A);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        PlatformTransactionManager gestor = new TenantTransactionManager(pool);

        ProgramaFiscalizacionRepositoryJdbc programas =
                new ProgramaFiscalizacionRepositoryJdbc(jdbc);
        MuestraDelProgramaRepositoryJdbc muestras = new MuestraDelProgramaRepositoryJdbc(jdbc);
        ActaFiscalizacionRepositoryJdbc actas = new ActaFiscalizacionRepositoryJdbc(jdbc);

        DeteccionDeOmisos deteccion =
                envolver(
                        new DeteccionDeOmisos(
                                new DeteccionRepositoryJdbc(jdbc), new TitularesDeMentira()),
                        gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new OmisosController(
                                        deteccion,
                                        envolver(
                                                new EstadoDeCuentaDeFiscalizacion(
                                                        new LiquidacionesEnMemoria(),
                                                        (contribuyenteId, fecha) -> List.of()),
                                                gestor),
                                        new PadronVacio(),
                                        RELOJ),
                                new ProgramasController(
                                        envolver(
                                                new RegistrarPrograma(programas, AUDITADOS::add),
                                                gestor),
                                        envolver(new ConsultaDeProgramas(programas), gestor)),
                                new MuestraController(
                                        envolver(
                                                new GenerarMuestra(
                                                        programas,
                                                        muestras,
                                                        actas,
                                                        deteccion,
                                                        AUDITADOS::add,
                                                        RELOJ),
                                                gestor),
                                        envolver(
                                                new ConsultaDeMuestra(programas, muestras, actas),
                                                gestor),
                                        new PadronVacio()))
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
        OrigenContext.fijar(new Origen("fiscalizador.campo", "PC-09", "10.0.0.9"));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @Order(1)
    @DisplayName("los filtros de la deteccion sortean EXACTAMENTE los predios que la deteccion vio")
    void losFiltrosDeLaDeteccionSorteanLoMismo() throws Exception {
        List<String> detectados = codigosDe(omisos(SECTOR_A, "OMISO"));
        assertThat(detectados)
                .as("los dos omisos del sector, sin el que declaro bien ni el del otro sector")
                .containsExactly(OMISO_A1, OMISO_A2);

        long programa = registrarPrograma("PF-550-A", SECTOR_A, "OMISO");
        sortear(programa);

        assertThat(codigosDe(muestraDe(programa)))
                .as(
                        "es la salida (a) de ADR-0023 medida: lo que la pantalla marca no hace"
                                + " falta que viaje, porque los mismos filtros producen los mismos"
                                + " predios. Si esto dejara de ser cierto, el funcionario que quiere"
                                + " mandar su seleccion tendria razon")
                .containsExactlyElementsOf(detectados);
    }

    @Test
    @Order(2)
    @DisplayName(
            "«Todos» del desplegable de sector es TODO EL DISTRITO, no un sector con ese nombre")
    void todosEsTodoElDistrito() throws Exception {
        assertThat(codigosDe(omisos(TODOS, "OMISO")))
                .as("la deteccion ya lo leia asi: los tres omisos de los dos sectores")
                .containsExactly(OMISO_A1, OMISO_A2, OMISO_B);

        MvcResult registro = registrar("PF-550-B", TODOS, "OMISO");

        // La CONSECUENCIA primero, que es la que no se ve: hasta #550 «Todos» se guardaba
        // literal y el sorteo filtraba por un sector que no existe, asi que `detectados` salia
        // 0 — indistinguible de «en el distrito no hay omisos»—.
        MvcResult sorteo = sortear(idDe(registro));
        assertThat(sorteo.getResponse().getContentAsString())
                .as(
                        "detectados cuenta el padron examinado: tres, los del distrito entero. Y"
                                + " dos ya se los llevo el programa del sector, que es la exclusion de"
                                + " #481 intacta")
                .contains("\"detectados\":3")
                .contains("\"excluidosPorOtroPrograma\":2")
                .contains("\"predios\":1");

        assertThat(codigosDe(muestraDe(idDe(registro)))).containsExactly(OMISO_B);

        // Y la CAUSA, que es lo que hay que arreglar: el sector queda sin acotar, que es lo
        // que «todo el distrito» significa en `programa_fiscalizacion.sector_codigo` (V60).
        assertThat(registro.getResponse().getContentAsString())
                .as(
                        "hasta #550 se guardaba literal, y entonces el sorteo filtra por un sector"
                                + " que no existe: cero predios, indistinguible de «aqui no hay"
                                + " omisos»")
                .contains("\"sector\":null");
    }

    @Test
    @Order(3)
    @DisplayName("«Todas» no es un criterio, y el 422 dice que es la ausencia de criterio")
    void todasNoEsUnCriterio() throws Exception {
        MvcResult resultado = registrar("PF-550-C", SECTOR_A, TODAS);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "«Todas» es lo que la deteccion llama «sin filtro», no una palabra que no"
                                + " se conozca; y un programa sin criterio no puede sortear, asi que"
                                + " admitirlo aplazaria el fallo hasta el sorteo")
                .contains("no es un criterio de riesgo")
                .doesNotContain("desconocid");
    }

    @Test
    @Order(4)
    @DisplayName("y una condicion que de verdad no existe sigue siendo 422 nombrandola")
    void unaCondicionInventadaSigueSiendo422() throws Exception {
        MvcResult resultado = registrar("PF-550-D", SECTOR_A, "MOROSO");

        assertThat(resultado.getResponse().getStatus())
                .as("el contraste: sin el, «rechazar Todas» podria estar rechazandolo todo")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("MOROSO");
    }

    @Test
    @Order(5)
    @DisplayName("la prueba se conecta como sgtm_app, no como superusuario ni como el dueno")
    void seConectaComoSgtmApp() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                .as(
                        "con superusuario RLS se omite —incluso con FORCE ROW LEVEL SECURITY— y"
                                + " todo este archivo pasaria sin verificar nada. Con sgtm_owner NO"
                                + " basta: FORCE lo sujeta a la politica igual, asi que la rotura"
                                + " clasica escrita con el dueno sale VERDE (#537, #545)")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    @Test
    @Order(6)
    @DisplayName("el omiso de la vecina no entra en ninguna de las dos muestras")
    void elOmisoDeLaVecinaNoEntra() throws Exception {
        assertThat(codigosDe(omisos(SECTOR_A, "OMISO")))
                .as("su sector se llama igual: sin RLS entraria por el filtro y no por descuido")
                .doesNotContain(DE_LA_VECINA);
        assertThat(codigosDe(omisos(TODOS, "OMISO"))).doesNotContain(DE_LA_VECINA);
    }

    // ------------------------------------------------------------------

    private static MvcResult omisos(String sector, String condicion) throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/fiscalizacion/omisos")
                                        .param("ejercicio", EJERCICIO)
                                        .param("sector", sector)
                                        .param("condicion", condicion))
                        .andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as(resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        return resultado;
    }

    /** Registra el programa con los tres filtros que la deteccion tiene delante. */
    private static MvcResult registrar(String codigo, String sector, String criterio)
            throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/fiscalizacion/programas")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"observacion\":\"Programa nacido de la deteccion\","
                                                + "\"codigo\":\""
                                                + codigo
                                                + "\",\"descripcion\":\"Omisos del ejercicio\","
                                                + "\"tipo\":\"PREDIAL\",\"fechaInicio\":"
                                                + "\"2026-09-02\",\"ejercicio\":\""
                                                + EJERCICIO
                                                + "\",\"sector\":\""
                                                + sector
                                                + "\",\"criterio\":\""
                                                + criterio
                                                + "\",\"fiscalizador\":\"R. MENDOZA CRUZ\"}"))
                .andReturn();
    }

    private static long registrarPrograma(String codigo, String sector, String criterio)
            throws Exception {
        MvcResult resultado = registrar(codigo, sector, criterio);
        assertThat(resultado.getResponse().getStatus())
                .as(resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        return idDe(resultado);
    }

    private static long idDe(MvcResult resultado) throws Exception {
        Matcher id = ID_DEL_PROGRAMA.matcher(resultado.getResponse().getContentAsString());
        assertThat(id.find()).as("el 201 devuelve el programa con su identificador").isTrue();
        return Long.parseLong(id.group(1));
    }

    private static MvcResult sortear(long programaId) throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/programas/"
                                                + programaId
                                                + "/muestra")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"observacion\":\"Sorteo de la prueba\"}"))
                        .andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as(resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        return resultado;
    }

    private static MvcResult muestraDe(long programaId) throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get(
                                        "/rentas/api/v1/fiscalizacion/programas/"
                                                + programaId
                                                + "/muestra"))
                        .andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as(resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        return resultado;
    }

    /** Los códigos de referencia catastral de la respuesta, en el orden en que salieron. */
    private static List<String> codigosDe(MvcResult resultado) throws Exception {
        Matcher encontrados =
                CODIGO_EN_LA_RESPUESTA.matcher(resultado.getResponse().getContentAsString());
        List<String> codigos = new ArrayList<>();
        while (encontrados.find()) {
            codigos.add(encontrados.group(1));
        }
        return codigos;
    }

    /** Envuelve el objetivo en un proxy transaccional que OBEDECE a la anotacion. */
    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, PlatformTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    // ---------- Siembra ----------

    private static String codigoDe(int indice) {
        return String.format("%018d", PRIMER_CODIGO + indice);
    }

    /** Un predio con ficha vigente y SIN declaración jurada del ejercicio: OMISO. */
    private static void sembrarOmiso(long municipalidadId, String codigo, String sector) {
        long predioId = crearPredio(municipalidadId, codigo, sector);
        crearFicha(municipalidadId, predioId, "300.00");
    }

    /** Un predio que declaró exactamente la ficha que rige: CONFORME. */
    private static void sembrarConforme(
            long municipalidadId, long titular, String codigo, String sector) {
        long predioId = crearPredio(municipalidadId, codigo, sector);
        long ficha = crearFicha(municipalidadId, predioId, "300.00");
        crearDeclaracion(municipalidadId, predioId, titular, ficha);
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

    private static long crearContribuyente(long municipalidadId, String codigo, String dni) {
        return comoApp(
                municipalidadId,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA', 'siembra')"
                        + " RETURNING id",
                municipalidadId,
                codigo,
                dni);
    }

    private static void crearSector(long municipalidadId, String codigo) {
        comoApp(
                municipalidadId,
                "INSERT INTO sector (municipalidad_id, codigo, nombre)"
                        + " VALUES (?, ?, 'Sector de prueba') RETURNING id",
                municipalidadId,
                codigo);
    }

    private static long crearPredio(long municipalidadId, String codigo, String sectorCodigo) {
        return comoApp(
                municipalidadId,
                "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo, direccion,"
                        + " sector_id)"
                        + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba',"
                        + "  (SELECT id FROM sector WHERE municipalidad_id = ? AND codigo = ?))"
                        + " RETURNING id",
                municipalidadId,
                codigo,
                municipalidadId,
                sectorCodigo);
    }

    private static long crearFicha(long municipalidadId, long predioId, String area) {
        return comoApp(
                municipalidadId,
                "INSERT INTO ficha_catastral (municipalidad_id, predio_id, tipo, version,"
                        + " area_terreno, uso, vigencia_desde, origen, documento_origen,"
                        + " observacion, usuario_registro)"
                        + " VALUES (?, ?, 'UNICA', ?, ?, 'CASA_HABITACION', DATE '2020-01-01',"
                        + " 'MIGRACION', 'DOC-PRUEBA', 'Siembra de la prueba', 'siembra')"
                        + " RETURNING id",
                municipalidadId,
                predioId,
                SIGUIENTE_VERSION.getAndIncrement(),
                new BigDecimal(area));
    }

    private static void crearDeclaracion(
            long municipalidadId, long predioId, long contribuyenteId, long fichaId) {
        comoApp(
                municipalidadId,
                "INSERT INTO declaracion_jurada (municipalidad_id, numero, ejercicio,"
                        + " contribuyente_id, tipo, predio_id, ficha_catastral_id,"
                        + " fecha_presentacion, fecha_limite, fuera_de_plazo, estado,"
                        + " usuario_registro, observacion)"
                        + " VALUES (?, ?, "
                        + EJERCICIO
                        + ", ?, 'PU', ?, ?, DATE '2026-02-20', DATE '2026-02-28', false,"
                        + " 'PRESENTADA', 'siembra', 'Siembra de la prueba') RETURNING id",
                municipalidadId,
                "DJ-550-" + SIGUIENTE_DJ.getAndIncrement(),
                contribuyenteId,
                predioId,
                fichaId);
    }

    private static long comoApp(long municipalidadId, String sql, Object... valores) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
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

    /**
     * El padrón no interviene: los titulares los resuelve {@link TitularesDeMentira}, que no
     * devuelve ninguno. Lo que aquí se compara son los predios, y el titular no cambia cuáles son
     * (#545: un predio sin titular vigente se detecta, y desde {@code V73} también se sortea).
     */
    private static final class PadronVacio implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return Map.of();
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }
}
