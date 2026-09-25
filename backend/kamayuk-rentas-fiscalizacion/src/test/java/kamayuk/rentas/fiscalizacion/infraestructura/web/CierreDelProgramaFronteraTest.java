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
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.fiscalizacion.aplicacion.CerrarProgramaFiscalizacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeMuestra;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeProgramas;
import kamayuk.rentas.fiscalizacion.aplicacion.DeteccionDeOmisos;
import kamayuk.rentas.fiscalizacion.aplicacion.GenerarMuestra;
import kamayuk.rentas.fiscalizacion.aplicacion.RegistrarPrograma;
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
import org.junit.jupiter.api.Test;
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
 * Un programa de fiscalización se cierra, y el predio que sorteó vuelve a poder sortearse (#341).
 *
 * <h2>Lo que se midió</h2>
 *
 * <p>La exclusión de #481 —«no sortees un predio que otro programa ABIERTO o EN_PROCESO ya se
 * llevó»— dependía de un estado que <b>ningún camino de producción cambiaba</b>: el programa nacía
 * {@code ABIERTO}, su tipo no tenía ningún método que moviera {@code estado}, en {@code src/main}
 * no había un solo {@code UPDATE programa_fiscalizacion} y ninguna ruta lo cerraba. La prueba que
 * decía lo contrario lo cerraba con SQL crudo, conectada como el dueño. Así que un omiso crónico
 * sorteado una vez salía en {@code excluidosPorOtroPrograma} de todo programa futuro, y nadie podía
 * corregirlo desde la aplicación.
 *
 * <h2>La siembra que distingue</h2>
 *
 * <p>Dos recorridos iguales sobre dos sectores distintos, cada uno con un solo omiso —«el 42»—
 * detectable en 2025 y en 2026. En los dos, PF-2025-OMI sortea al 42 y después PF-2026-OMI, del
 * mismo criterio y otro ejercicio, lo vuelve a detectar. Lo único que cambia es que en uno el
 * primer programa se <b>cierra por la ruta de producción</b> —no por SQL— y en el otro no:
 *
 * <ul>
 *   <li>cerrado, el 42 <b>entra</b>: {@code predios = 1}, {@code excluidosPorOtroPrograma = 0};
 *   <li>sin cerrar, el 42 sale en {@code excluidosPorOtroPrograma = 1} y la muestra queda vacía.
 * </ul>
 *
 * <p>Las dos respuestas afirman además {@code detectados = 1}: sin eso, «el 42 no entra» no se
 * distinguiría de «el 42 ya no es omiso».
 *
 * <h2>Por qué de HTTP a PostgreSQL</h2>
 *
 * <p>Porque lo que hay que demostrar es que la fila cambia <b>de verdad</b> y que la consulta de la
 * exclusión la ve: un doble en memoria del repositorio pasaría con un {@code cerrar} que no
 * escribiera nada, que es exactamente la rotura que esta prueba tiene que poner roja. La conexión
 * es la de {@code kamayuk_app}, con su privilegio de columna ({@code V30}); los casos de uso van
 * envueltos obedeciendo a su {@code @Transactional}, como en {@link
 * ProgramarDesdeLaDeteccionFronteraTest}.
 */
@DisplayName("#341 — Un programa se cierra y deja de excluir, de HTTP a PostgreSQL")
class CierreDelProgramaFronteraTest {

    /** El día en que se sortea el segundo programa: enero de 2027, como en el escenario. */
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2027-01-15T10:00:00Z"), ZoneOffset.UTC);

    private static final String SECTOR_QUE_SE_CIERRA = "CI-01";
    private static final String SECTOR_DE_CONTROL = "CI-02";
    private static final String SECTOR_DE_LOS_RECHAZOS = "CI-03";

    private static final int PRIMER_CODIGO = 3410;

    /** El omiso crónico de cada recorrido: sin declaración ni en 2025 ni en 2026. */
    private static final String EL_42 = codigoDe(42);

    private static final String EL_42_DE_CONTROL = codigoDe(142);

    private static final Pattern CODIGO_EN_LA_RESPUESTA =
            Pattern.compile("\"codRefCatastral\":\"(\\d+)\"");

    private static final Pattern ID_DEL_PROGRAMA = Pattern.compile("\"id\":(\\d+)");

    private static final AtomicInteger SIGUIENTE_VERSION = new AtomicInteger(1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static JdbcClient jdbc;
    private static MockMvc mvc;

    private static final List<RegistroDeAuditoria> AUDITADOS = new ArrayList<>();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("234101", "Municipalidad que cierra programas");

        crearSector(municipalidad, SECTOR_QUE_SE_CIERRA);
        crearSector(municipalidad, SECTOR_DE_CONTROL);
        crearSector(municipalidad, SECTOR_DE_LOS_RECHAZOS);
        sembrarOmiso(municipalidad, EL_42, SECTOR_QUE_SE_CIERRA);
        sembrarOmiso(municipalidad, EL_42_DE_CONTROL, SECTOR_DE_CONTROL);

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
                                        new PadronVacio()),
                                new CierreDelProgramaController(
                                        envolver(
                                                new CerrarProgramaFiscalizacion(
                                                        programas, AUDITADOS::add, RELOJ),
                                                gestor)))
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
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("fiscalizador.jefe", "PC-34", "10.0.0.34"));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("cerrado por su ruta, el programa de 2025 suelta al 42 y el de 2026 lo sortea")
    void cerradoElProgramaElOmisoCronicoVuelveASortearse() throws Exception {
        long de2025 = registrarPrograma("PF-2025-OMI", "2025", SECTOR_QUE_SE_CIERRA);
        assertThat(sortear(de2025))
                .as("el primero sortea al 42: es la premisa del escenario")
                .contains("\"detectados\":1")
                .contains("\"predios\":1");

        MvcResult cierre = cerrarPrograma(de2025, "2026-12-31");
        assertThat(cierre.getResponse().getStatus())
                .as(
                        "el cierre es un acto de la administracion y tiene ruta propia; hasta #341"
                                + " no existia ninguna y el programa se quedaba ABIERTO para siempre."
                                + " Respuesta: "
                                + cierre.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(cierre.getResponse().getContentAsString())
                .contains("\"codigo\":\"PF-2025-OMI\"")
                .contains("\"estado\":\"CERRADO\"");

        long de2026 = registrarPrograma("PF-2026-OMI", "2026", SECTOR_QUE_SE_CIERRA);
        assertThat(sortear(de2026))
                .as(
                        "el 42 sigue omiso en 2026 —detectados 1— y el unico programa que lo tenia"
                                + " esta CERRADO: entra en la muestra y nadie lo excluye")
                .contains("\"detectados\":1")
                .contains("\"excluidosPorOtroPrograma\":0")
                .contains("\"predios\":1");
        assertThat(codigosDe(muestraDe(de2026))).containsExactly(EL_42);

        assertThat(estadoLeidoDe("PF-2025-OMI"))
                .as("y la grilla lo lee de la fila, no de lo que devolvio el acto")
                .isEqualTo("CERRADO");
    }

    @Test
    @DisplayName("el control: el mismo recorrido sin cerrar deja al 42 fuera, por otro programa")
    void sinCerrarElOmisoCronicoQuedaExcluido() throws Exception {
        long de2025 = registrarPrograma("PF-2025-CTL", "2025", SECTOR_DE_CONTROL);
        assertThat(sortear(de2025)).contains("\"detectados\":1").contains("\"predios\":1");

        long de2026 = registrarPrograma("PF-2026-CTL", "2026", SECTOR_DE_CONTROL);
        assertThat(sortear(de2026))
                .as(
                        "sin el cierre la exclusion de #481 se aplica: el 42 se detecta y otro"
                                + " programa ABIERTO lo retiene. Es lo que el recorrido cerrado tiene"
                                + " que desmentir, y lo que hace que su verde signifique algo")
                .contains("\"detectados\":1")
                .contains("\"excluidosPorOtroPrograma\":1")
                .contains("\"predios\":0");
        assertThat(codigosDe(muestraDe(de2026))).isEmpty();
    }

    @Test
    @DisplayName("cerrar un programa ya cerrado es 409, y el segundo intento no deja auditoria")
    void cerrarDosVecesEs409() throws Exception {
        long programa = registrarPrograma("PF-341-DOS", "2026", SECTOR_DE_LOS_RECHAZOS);

        assertThat(cerrarPrograma(programa, "2026-12-31").getResponse().getStatus()).isEqualTo(201);
        long auditadosTrasElPrimero = cierresAuditadosDe(programa);

        MvcResult segundo = cerrarPrograma(programa, "2027-01-02");
        assertThat(segundo.getResponse().getStatus())
                .as(
                        "409 y no 422: la peticion esta bien escrita, lo que no la admite es el"
                                + " estado del programa")
                .isEqualTo(409);
        assertThat(segundo.getResponse().getContentAsString()).contains("CERRADO");
        assertThat(auditadosTrasElPrimero)
                .as("el primer cierre queda en la auditoria con su observacion (regla 10)")
                .isEqualTo(1);
        assertThat(cierresAuditadosDe(programa))
                .as("el que no cerro nada no se asienta como si lo hubiera hecho")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("el cierre lleva el dia del acto en la auditoria, no el de su registro")
    void elCierreAuditaSuFecha() throws Exception {
        long programa = registrarPrograma("PF-341-FEC", "2026", SECTOR_DE_LOS_RECHAZOS);

        assertThat(cerrarPrograma(programa, "2026-11-30").getResponse().getStatus()).isEqualTo(201);

        RegistroDeAuditoria asiento =
                AUDITADOS.stream()
                        .filter(r -> r.tabla().equals("programa_fiscalizacion"))
                        .filter(r -> r.clave().equals(String.valueOf(programa)))
                        .filter(r -> r.operacion() == Operacion.MODIFICACION)
                        .findFirst()
                        .orElseThrow();
        assertThat(asiento.datosAnteriores()).contains("\"estado\":\"ABIERTO\"");
        assertThat(asiento.datosNuevos())
                .contains("\"estado\":\"CERRADO\"")
                .contains("\"fechaCierre\":\"2026-11-30\"");
    }

    @Test
    @DisplayName("un programa que no existe es 404, no 500")
    void unProgramaQueNoExisteEs404() throws Exception {
        assertThat(cerrarPrograma(987654L, "2026-12-31").getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("sin observacion, 422 y el programa sigue ABIERTO")
    void sinObservacionNoSeCierra() throws Exception {
        registrarPrograma("PF-341-OBS", "2026", SECTOR_DE_LOS_RECHAZOS);
        long programa = idDe("PF-341-OBS");

        MvcResult resultado =
                mvc.perform(
                                post(rutaDelCierre(programa))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"fecha\":\"2026-12-31\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(estadoLeidoDe("PF-341-OBS")).isEqualTo("ABIERTO");
    }

    @Test
    @DisplayName("ni antes de que el programa empiece ni despues de hoy: 422, y sigue ABIERTO")
    void laFechaDelCierreVaEnOrden() throws Exception {
        long programa = registrarPrograma("PF-341-ORD", "2026", SECTOR_DE_LOS_RECHAZOS);

        MvcResult antes = cerrarPrograma(programa, "2025-02-28");
        assertThat(antes.getResponse().getStatus())
                .as("el programa empieza el 2025-03-01: no se cierra lo que no habia empezado")
                .isEqualTo(422);
        assertThat(antes.getResponse().getContentAsString()).contains("2025-03-01");

        MvcResult despues = cerrarPrograma(programa, "2027-01-16");
        assertThat(despues.getResponse().getStatus())
                .as("hoy es 2027-01-15 (#402: un acto no se fecha en el futuro)")
                .isEqualTo(422);

        assertThat(estadoLeidoDe("PF-341-ORD")).isEqualTo("ABIERTO");
    }

    @Test
    @DisplayName("la prueba se conecta como kamayuk_app, no como superusuario ni como el dueno")
    void seConectaComoKamayukApp() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                .as(
                        "el cierre escribe UNA columna, y lo que lo permite es el privilegio de"
                                + " columna de V30: con el dueno la prueba pasaria aunque"
                                + " kamayuk_app no pudiera escribirla")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    // ------------------------------------------------------------------

    private static String rutaDelCierre(long programaId) {
        return "/rentas/api/v1/fiscalizacion/programas/" + programaId + "/cierre";
    }

    private static MvcResult cerrarPrograma(long programaId, String fecha) throws Exception {
        return mvc.perform(
                        post(rutaDelCierre(programaId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"observacion\":\"Se cierra: el programa termino su"
                                                + " campana\",\"fecha\":\""
                                                + fecha
                                                + "\"}"))
                .andReturn();
    }

    private static long cierresAuditadosDe(long programaId) {
        return AUDITADOS.stream()
                .filter(r -> r.tabla().equals("programa_fiscalizacion"))
                .filter(r -> r.clave().equals(String.valueOf(programaId)))
                .filter(r -> r.operacion() == Operacion.MODIFICACION)
                .count();
    }

    /** Registra un programa de omisos sobre un sector, empezado el 2025-03-01. */
    private static long registrarPrograma(String codigo, String ejercicio, String sector)
            throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/programas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"observacion\":\"Programa de la prueba\","
                                                        + "\"codigo\":\""
                                                        + codigo
                                                        + "\",\"descripcion\":\"Omisos\","
                                                        + "\"tipo\":\"PREDIAL\",\"fechaInicio\":"
                                                        + "\"2025-03-01\",\"ejercicio\":\""
                                                        + ejercicio
                                                        + "\",\"sector\":\""
                                                        + sector
                                                        + "\",\"criterio\":\"OMISO\","
                                                        + "\"fiscalizador\":\"R. MENDOZA CRUZ\"}"))
                        .andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as(resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        Matcher id = ID_DEL_PROGRAMA.matcher(resultado.getResponse().getContentAsString());
        assertThat(id.find()).isTrue();
        return Long.parseLong(id.group(1));
    }

    private static String sortear(long programaId) throws Exception {
        ProyeccionDeLaPrueba.ingestar(base, municipalidad);
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
        return resultado.getResponse().getContentAsString();
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

    /** El programa leido por la grilla: lo que dice la FILA, no lo que devolvio el acto. */
    private static String programaLeido(String codigo) throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/fiscalizacion/programas")
                                        .param("nDePrograma", codigo))
                        .andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as(resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        return resultado.getResponse().getContentAsString();
    }

    private static String estadoLeidoDe(String codigo) throws Exception {
        Matcher estado = Pattern.compile("\"estado\":\"([A-Z_]+)\"").matcher(programaLeido(codigo));
        assertThat(estado.find()).as("la grilla trae el programa " + codigo).isTrue();
        return estado.group(1);
    }

    private static long idDe(String codigo) throws Exception {
        Matcher id = ID_DEL_PROGRAMA.matcher(programaLeido(codigo));
        assertThat(id.find()).as("la grilla trae el programa " + codigo).isTrue();
        return Long.parseLong(id.group(1));
    }

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

    /**
     * Un predio con ficha vigente desde 2020 y SIN declaración jurada de ningún ejercicio: OMISO en
     * 2025 y en 2026, que es lo que hace de él un omiso crónico.
     */
    private static void sembrarOmiso(long municipalidadId, String codigo, String sector) {
        long predioId = crearPredio(municipalidadId, codigo, sector);
        crearFicha(municipalidadId, predioId, "300.00");
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

    private static void crearSector(long municipalidadId, String codigo) {
        comoApp(
                municipalidadId,
                "INSERT INTO sector_de_prueba (municipalidad_id, codigo, nombre)"
                        + " VALUES (?, ?, 'Sector de prueba') RETURNING id",
                municipalidadId,
                codigo);
    }

    private static long crearPredio(long municipalidadId, String codigo, String sectorCodigo) {
        return comoApp(
                municipalidadId,
                "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo, direccion,"
                        + " sector_id)"
                        + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba',"
                        + "  (SELECT id FROM sector_de_prueba WHERE municipalidad_id = ? AND codigo = ?))"
                        + " RETURNING id",
                municipalidadId,
                codigo,
                municipalidadId,
                sectorCodigo);
    }

    private static long crearFicha(long municipalidadId, long predioId, String area) {
        return comoApp(
                municipalidadId,
                "INSERT INTO ficha_catastral_de_prueba (municipalidad_id, predio_id, tipo, version,"
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

    /** Hace correr al ingestor de la proyección de catastro antes de sortear (P5C). */
    private static final class ProyeccionDeLaPrueba {
        private ProyeccionDeLaPrueba() {}

        static void ingestar(BaseDeDatosDePrueba base, long municipalidadId) {
            try {
                kamayuk.rentas.esquema.ProyeccionDeCatastro.proyectar(base, municipalidadId);
            } catch (SQLException noSePudo) {
                throw new IllegalStateException("No se pudo proyectar el catastro", noSePudo);
            }
        }
    }

    /** El padrón no interviene: lo que aquí se mide son los predios, no sus titulares. */
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
