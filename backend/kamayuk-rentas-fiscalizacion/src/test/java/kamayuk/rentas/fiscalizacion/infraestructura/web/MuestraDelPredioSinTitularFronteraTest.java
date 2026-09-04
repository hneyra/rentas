package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
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
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.aplicacion.LectorDeFichasCatastro;
import kamayuk.rentas.catastro.aplicacion.TitularesDelPredioCatastro;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
import kamayuk.rentas.catastro.infraestructura.FichaCatastralRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeMuestra;
import kamayuk.rentas.fiscalizacion.aplicacion.DeteccionDeOmisos;
import kamayuk.rentas.fiscalizacion.aplicacion.GenerarMuestra;
import kamayuk.rentas.fiscalizacion.aplicacion.RegistrarActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.infraestructura.ActaFiscalizacionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.DeteccionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.MuestraDelProgramaRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.ProgramaFiscalizacionRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * El sorteo de la muestra de HTTP a PostgreSQL, sin un doble por el camino (#586).
 *
 * <h2>Qué estaba mal</h2>
 *
 * <p>#545 quitó el {@code JOIN} interno con {@code titularidad} de la detección, y con él entraron
 * los predios <b>sin titularidad vigente</b>: 4 977 de los 14 422 de Catacaos, el 34,5 % del
 * padrón. Son el predio que nadie reclama —no hay a quién notificarle, no hay quién declare—, o sea
 * el candidato de primer orden. Pero {@code programa_muestra.contribuyente_id} era {@code NOT NULL}
 * (V60), así que {@code GenerarMuestra} los apartaba <b>en silencio</b>: devolvía sólo cuántos
 * entraron. Una muestra de 100 sobre un padrón donde un tercio no podía entrar no es una muestra de
 * ese padrón, y nada en la respuesta permitía sospecharlo.
 *
 * <h2>Por qué hasta la base, y por qué por HTTP</h2>
 *
 * <p>Porque la exclusión no la decidía Java sino una columna, y con un doble en memoria la fila sin
 * titular «entraría» sin que nadie hubiera comprobado que la base la acepta. Y porque lo que el
 * issue exige que se vea —el recuento— sale por la respuesta del {@code POST}, no del caso de uso.
 *
 * <p>La conexión es la de {@code sgtm_app}: un superusuario omite RLS incluso con {@code FORCE ROW
 * LEVEL SECURITY} (DAT-01 §0, primer hallazgo), y {@code sgtm_owner} tampoco sirve —con {@code
 * FORCE} el dueño también queda sujeto a la política, que es lo que #537 y #545 midieron—.
 *
 * <p>Los casos de uso se envuelven con {@link AnnotationTransactionAttributeSource}, o sea
 * <b>obedeciendo a la anotación</b> como el contenedor: un {@code TransactionTemplate}
 * incondicional dejaría la prueba pasando con el {@code @Transactional} quitado (#486).
 *
 * <h2>Y el acta no cambia, que es la otra mitad de la decisión</h2>
 *
 * <p>{@code acta_fiscalizacion.contribuyente_id} sigue siendo {@code NOT NULL}. No hace falta
 * tocarlo: {@code RegistrarActaFiscalizacion} nunca lee {@code programa_muestra} y recibe el
 * contribuyente en el cuerpo, así que el acta ya sabe levantarse contra un predio cuyo titular el
 * padrón no conocía — la visita es justamente lo que resuelve quién ocupa. Aquí se ejercita ese
 * camino entero, y por eso el {@code POST} del acta está en esta prueba y no en otra.
 */
@DisplayName("#586 — La muestra admite el predio sin titular, y dice a quien dejo fuera")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MuestraDelPredioSinTitularFronteraTest {

    /** El sorteo pasa por aquí: la fecha del reparto sale de este reloj, no de la máquina. */
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-03-16T09:00:00Z"), ZoneOffset.UTC);

    private static final String SORTEO = "2026-03-16";

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long programaId;
    private static long predioSinTitular;
    private static long predioConTitular;
    private static long predioDeOtroPrograma;
    private static long titular;
    private static long ocupanteHallado;
    private static MockMvc mvc;

    private static final List<RegistroDeAuditoria> AUDITADOS = new ArrayList<>();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("280101", "Municipalidad del predio sin dueno");

        titular = sembrarContribuyente("SD-0001", "70300001");
        ocupanteHallado = sembrarContribuyente("SD-0002", "70300002");
        sembrarSector("SD");

        // El predio que este issue existe para que entre: ficha, sin declaracion jurada del
        // ejercicio —o sea OMISO— y SIN NI UNA FILA DE TITULARIDAD.
        predioSinTitular = sembrarOmiso("SD-A", null);
        predioConTitular = sembrarOmiso("SD-B", titular);
        predioDeOtroPrograma = sembrarOmiso("SD-C", titular);

        programaId = crearPrograma("PF-586");
        long otroPrograma = crearPrograma("PF-586-OTRO");
        sembrarMuestra(otroPrograma, predioDeOtroPrograma, titular);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        ProgramaFiscalizacionRepositoryJdbc programas =
                new ProgramaFiscalizacionRepositoryJdbc(jdbc);
        MuestraDelProgramaRepositoryJdbc muestras = new MuestraDelProgramaRepositoryJdbc(jdbc);
        ActaFiscalizacionRepositoryJdbc actas = new ActaFiscalizacionRepositoryJdbc(jdbc);

        DeteccionDeOmisos deteccion =
                new DeteccionDeOmisos(
                        new DeteccionRepositoryJdbc(jdbc),
                        envolver(
                                new TitularesDelPredioCatastro(new CatastroRepositoryJdbc(jdbc)),
                                gestor));

        GenerarMuestra sorteo =
                envolver(
                        new GenerarMuestra(
                                programas, muestras, actas, deteccion, AUDITADOS::add, RELOJ),
                        gestor);

        RegistrarActaFiscalizacion registroDeActas =
                envolver(
                        new RegistrarActaFiscalizacion(
                                actas,
                                programas,
                                envolver(
                                        new LectorDeFichasCatastro(
                                                new FichaCatastralRepositoryJdbc(jdbc)),
                                        gestor),
                                registro -> {}),
                        gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new MuestraController(
                                        sorteo,
                                        envolver(
                                                new ConsultaDeMuestra(programas, muestras, actas),
                                                gestor),
                                        new PadronDeLaPrueba()),
                                new ActaPredialController(registroDeActas))
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

    // El sorteo es un ACTO y no se repite: `MuestraYaSorteada` responde 409 al segundo intento.
    // Por eso las pruebas van en orden y la primera es la que lo dispara.

    @Test
    @Order(1)
    @DisplayName("AC 2 y 3 — el 201 dice sobre que padron se sorteo, y el recuento se reconstruye")
    void elSorteoDiceSobreQuePadronSeSorteo() throws Exception {
        ingestar(municipalidadA);
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/programas/"
                                                + programaId
                                                + "/muestra")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"observacion\":\"Sorteo anual de la"
                                                        + " prueba\"}"))
                        .andReturn();

        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(resultado.getResponse().getStatus()).as(cuerpo).isEqualTo(201);

        assertThat(cuerpo)
                .as(
                        "hasta #586 el cuerpo era {\"programaId\":N,\"predios\":M} y nada mas: una"
                                + " muestra de 100 sobre un padron de 4 977 candidatos era"
                                + " indistinguible de una sobre un padron de 100")
                .contains("\"detectados\":3")
                .contains("\"predios\":2")
                .contains("\"sinTitular\":1")
                .contains("\"excluidosPorOtroPrograma\":1")
                .contains("\"excluidosPorActaDelEjercicio\":0")
                .contains("\"fechaSorteo\":\"" + SORTEO + "\"");
    }

    @Test
    @Order(2)
    @DisplayName("AC 1 — el predio SIN titular esta en la muestra, con los tres campos nulos")
    void elPredioSinTitularEstaEnLaMuestra() throws Exception {
        String cuerpo = muestraDe(predioSinTitular);

        assertThat(cuerpo)
                .as(
                        "es el predio que nadie reclama, y hasta #586 la muestra lo apartaba sin"
                                + " decirlo")
                .contains("\"totalElementos\":1")
                .contains("\"contribuyenteId\":null")
                .contains("\"codContribuyente\":null")
                .contains("\"titular\":null");
    }

    @Test
    @Order(3)
    @DisplayName("y el que si lo tiene sigue saliendo con su titular resuelto del padron")
    void elPredioConTitularSigueSaliendoConSuTitular() throws Exception {
        String cuerpo = muestraDe(predioConTitular);

        assertThat(cuerpo)
                .contains("\"totalElementos\":1")
                .contains("\"contribuyenteId\":" + titular)
                .contains("\"codContribuyente\":\"SD-0001\"")
                .contains("\"titular\":\"TITULAR, PRUEBA\"");
    }

    @Test
    @Order(4)
    @DisplayName("el que se llevo otro programa abierto no esta, y por eso se contaba aparte")
    void elDeOtroProgramaNoEsta() throws Exception {
        assertThat(muestraDe(predioDeOtroPrograma)).contains("\"totalElementos\":0");
    }

    @Test
    @Order(5)
    @DisplayName("AC 1 — el acta se levanta contra ese predio nombrando a quien la visita hallo")
    void elActaSeLevantaContraElPredioSinTitular() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/predial/actas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"observacion\":\"Visita de campo\","
                                                        + "\"programaId\":"
                                                        + programaId
                                                        + ",\"contribuyenteId\":"
                                                        + ocupanteHallado
                                                        + ",\"predioId\":"
                                                        + predioSinTitular
                                                        + ",\"fechaVisita\":\"2026-03-20\","
                                                        + "\"fiscalizador\":\"R. MENDOZA CRUZ\","
                                                        + "\"hallazgo\":\"OMISO\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "el acta NO lee la muestra: recibe el contribuyente en el cuerpo, porque la"
                                + " visita es lo que resuelve quien ocupa. Por eso"
                                + " acta_fiscalizacion.contribuyente_id no se relaja: admitir un"
                                + " nulo daria un acta que se levanta y nunca se liquida")
                .isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"contribuyenteId\":" + ocupanteHallado)
                .contains("\"predioId\":" + predioSinTitular);
    }

    @Test
    @Order(6)
    @DisplayName("y entonces esa fila de la muestra pasa a estar visitada")
    void esaFilaPasaAEstarVisitada() throws Exception {
        assertThat(muestraDe(predioSinTitular))
                .as("el circuito entero: se detecta, se sortea, se visita y se registra")
                .contains("\"visitado\":true");
    }

    @Test
    @Order(7)
    @DisplayName("AC 3 — la bitacora se lleva el reparto entero, no solo cuantos entraron")
    void laBitacoraSeLlevaElRepartoEntero() {
        assertThat(AUDITADOS).hasSize(1);
        assertThat(AUDITADOS.get(0).datosNuevos())
                .as("quien audita meses despues tiene que poder saber sobre que padron se sorteo")
                .contains("\"detectados\":3")
                .contains("\"predios\":2")
                .contains("\"sinTitular\":1")
                .contains("\"excluidosPorOtroPrograma\":1");
    }

    // ------------------------------------------------------------------

    private static String muestraDe(long predioId) throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/fiscalizacion/programas/"
                                                + programaId
                                                + "/muestra")
                                        .param("predio", String.valueOf(predioId)))
                        .andReturn();
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        return resultado.getResponse().getContentAsString();
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

    /** El padron: los dos contribuyentes sembrados, resueltos por su identificador. */
    private static final class PadronDeLaPrueba implements DirectorioDeContribuyentes {

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
            return Map.of(
                    titular,
                    new ResumenDeContribuyente(titular, "SD-0001", "TITULAR, PRUEBA", "70300001"),
                    ocupanteHallado,
                    new ResumenDeContribuyente(
                            ocupanteHallado, "SD-0002", "OCUPANTE, HALLADO", "70300002"));
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    // ── Siembra ────────────────────────────────────────────────────────

    /** Un predio OMISO del ejercicio 2024: tiene ficha y no tiene declaracion jurada. */
    private static long sembrarOmiso(String sufijo, @Nullable Long conTitular) {
        long predioId = sembrarPredio(sufijo);
        sembrarFicha(predioId);
        if (conTitular != null) {
            sembrarTitularidad(predioId, conTitular);
        }
        return predioId;
    }

    private static void sembrarSector(String codigo) {
        ejecutarComoApp(
                "INSERT INTO sector (municipalidad_id, codigo, nombre)"
                        + " VALUES (?, ?, 'Sector de prueba') RETURNING id",
                municipalidadA,
                codigo);
    }

    private static long sembrarPredio(String sufijo) {
        return ejecutarComoApp(
                "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo, direccion,"
                        + " sector_id)"
                        + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba',"
                        + "  (SELECT id FROM sector WHERE municipalidad_id = ? AND codigo = 'SD'))"
                        + " RETURNING id",
                municipalidadA,
                codigoCatastralDe(sufijo),
                municipalidadA);
    }

    private static long sembrarFicha(long predioId) {
        return ejecutarComoApp(
                "INSERT INTO ficha_catastral (municipalidad_id, predio_id, tipo, version,"
                        + " area_terreno, uso, vigencia_desde, origen, documento_origen,"
                        + " observacion, usuario_registro)"
                        + " VALUES (?, ?, 'UNICA', ?, 300.00, 'CASA_HABITACION', DATE '2020-01-01',"
                        + " 'MIGRACION', 'DOC-PRUEBA', 'Siembra de la prueba', 'siembra')"
                        + " RETURNING id",
                municipalidadA,
                predioId,
                SIGUIENTE_VERSION.getAndIncrement());
    }

    private static long sembrarTitularidad(long predioId, long contribuyenteId) {
        return ejecutarComoApp(
                "INSERT INTO titularidad (municipalidad_id, predio_id, contribuyente_id, condicion,"
                        + " porcentaje, vigencia_desde, documento_origen)"
                        + " VALUES (?, ?, ?, 'PROPIETARIO_UNICO', 100.00, DATE '2020-01-01',"
                        + " 'DOC-PRUEBA') RETURNING id",
                municipalidadA,
                predioId,
                contribuyenteId);
    }

    private static long sembrarContribuyente(String codigo, String dni) {
        return ejecutarComoApp(
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA', 'siembra')"
                        + " RETURNING id",
                municipalidadA,
                codigo,
                dni);
    }

    private static long crearPrograma(String codigo) {
        return ejecutarComoApp(
                "INSERT INTO programa_fiscalizacion (municipalidad_id, codigo, descripcion, tipo,"
                        + " fecha_inicio, ejercicio, sector_codigo, criterio, fiscalizador)"
                        + " VALUES (?, ?, 'Programa de prueba', 'PREDIAL', ?, 2024, 'SD', 'OMISO',"
                        + "         'R. MENDOZA CRUZ') RETURNING id",
                municipalidadA,
                codigo,
                LocalDate.of(2026, 1, 1));
    }

    private static void sembrarMuestra(long programa, long predioId, long contribuyenteId) {
        ejecutarComoApp(
                "INSERT INTO programa_muestra (municipalidad_id, programa_id, predio_id,"
                        + " cod_ref_catastral, contribuyente_id, condicion, fecha_sorteo,"
                        + " observacion, usuario_registro, fecha_registro)"
                        + " VALUES (?, ?, ?, (SELECT codigo_ref_catastral FROM predio"
                        + "                    WHERE municipalidad_id = ? AND id = ?), ?, 'OMISO',"
                        + "         ?, 'siembra', 'siembra', now()) RETURNING id",
                municipalidadA,
                programa,
                predioId,
                municipalidadA,
                predioId,
                contribuyenteId,
                LocalDate.of(2026, 3, 15));
    }

    // ── Fontaneria ─────────────────────────────────────────────────────

    private static final AtomicInteger SIGUIENTE_CATASTRAL = new AtomicInteger(860000);
    private static final AtomicInteger SIGUIENTE_VERSION = new AtomicInteger(1);
    private static final java.util.concurrent.ConcurrentHashMap<String, String> CODIGOS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Codigo catastral de relleno: el dominio {@code cod_catastral} exige 18-25 digitos. */
    private static String codigoCatastralDe(String sufijo) {
        return CODIGOS.computeIfAbsent(
                sufijo, s -> String.format("%018d", SIGUIENTE_CATASTRAL.getAndIncrement()));
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

    private static long ejecutarComoApp(String sql, Object... valores) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
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
     * Hace correr al ingestor de la proyeccion de catastro antes de preguntar (P5C).
     *
     * <p>Desde la separacion, la deteccion de omisos —de la que sale la muestra— lee {@code
     * predio_ref} y {@code ficha_ref} y no las tablas del vecino (ADR-0029). Esta llamada es el
     * paso que en produccion dispara un evento.
     */
    private static void ingestar(long municipalidadId) {
        try {
            kamayuk.rentas.esquema.ProyeccionDeCatastro.proyectar(base, municipalidadId);
        } catch (java.sql.SQLException noSePudo) {
            throw new IllegalStateException("No se pudo proyectar el catastro", noSePudo);
        }
    }
}
