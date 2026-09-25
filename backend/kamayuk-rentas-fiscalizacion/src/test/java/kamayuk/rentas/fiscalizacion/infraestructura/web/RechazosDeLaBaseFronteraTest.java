package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.aplicacion.DirectorioJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.FichaRepositoryJdbc;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeProgramas;
import kamayuk.rentas.fiscalizacion.aplicacion.RegistrarActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.aplicacion.RegistrarPrograma;
import kamayuk.rentas.fiscalizacion.infraestructura.ActaFiscalizacionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.ProgramaFiscalizacionRepositoryJdbc;
import kamayuk.rentas.nucleo.PadronVehicular;
import kamayuk.rentas.nucleo.aplicacion.PadronVehicularRentas;
import kamayuk.rentas.nucleo.infraestructura.VehiculoRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
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
 * #422 — Lo que la base rechaza no sale como averia: programas y actas, de HTTP a PostgreSQL.
 *
 * <h2>Por que hasta la base</h2>
 *
 * <p>Porque el defecto <b>es</b> la base: un indice unico, una clave foranea y el ancho de una
 * columna que ninguna comprobacion en Java miraba. Contra un doble del repositorio las tres
 * peticiones de abajo contestaban 201 —el doble no tiene ni indice ni clave ni ancho— y contra
 * PostgreSQL contestaban 500 {@code ERROR_INTERNO} con su incidencia, que es lo que el cliente
 * veia.
 *
 * <p>Cada prueba usa la siembra que la muestra de siempre no usa: el codigo que <b>ya existe</b>
 * para el 409, el identificador 999999 para el 404, y el ancho de la columna <b>+ 1</b> para el
 * 422. Y cada una afirma, ademas del estado, que <b>no se escribio ninguna linea ERROR</b>: un
 * error del usuario que deja incidencia entierra las averias de verdad aunque el estado ya fuera el
 * correcto.
 *
 * <p>La conexion es la de {@code kamayuk_app}, y los casos de uso van envueltos en el proxy que
 * obedece a su {@code @Transactional}, como en {@code ProgramarDesdeLaDeteccionFronteraTest}.
 */
@DisplayName("#422 — Programas y actas: lo que la base rechaza sale como 404, 409 o 422")
class RechazosDeLaBaseFronteraTest {

    private static final String PROGRAMAS = "/rentas/api/v1/fiscalizacion/programas";
    private static final String ACTAS_PREDIALES = "/rentas/api/v1/fiscalizacion/predial/actas";
    private static final String ACTAS_VEHICULARES = "/rentas/api/v1/fiscalizacion/vehicular";

    /** Un identificador que no existe en ninguna municipalidad de la prueba. */
    private static final long INEXISTENTE = 999_999L;

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long titular;
    private static long vehiculo;
    private static long programaPredial;
    private static long programaVehicular;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("242201", "Municipalidad que fiscaliza");
        titular =
                comoApp(
                        "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                + " tipo_documento, numero_documento, tipo_persona,"
                                + " nombre_razon_social, usuario_registro)"
                                + " VALUES (?, 'R-42201', 'DNI', '42200001', 'NATURAL',"
                                + " 'FISCALIZADO, ALGUIEN', 'siembra') RETURNING id",
                        municipalidad);
        vehiculo =
                comoApp(
                        "INSERT INTO vehiculo (municipalidad_id, placa, contribuyente_id, marca,"
                                + " modelo, categoria, anio_fabricacion, anio_inscripcion)"
                                + " VALUES (?, 'ABC-422', ?, 'MARCA', 'MODELO', 'M1', 2020, 2021)"
                                + " RETURNING id",
                        municipalidad,
                        titular);
        programaPredial = crearPrograma("PF-422-P", "PREDIAL");
        programaVehicular = crearPrograma("PF-422-V", "VEHICULAR");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        PlatformTransactionManager gestor = new TenantTransactionManager(pool);
        ProgramaFiscalizacionRepositoryJdbc programas =
                new ProgramaFiscalizacionRepositoryJdbc(jdbc);
        // Los dos puertos que el acta pregunta desde #422, con sus adaptadores DE VERDAD: el padron
        // de contribuyentes y el vehicular contestan contra la misma base y bajo la misma RLS que
        // la clave foranea que rechazaria la fila.
        DirectorioDeContribuyentes padron =
                envolver(
                        new DirectorioJdbc(
                                new ContribuyenteRepositoryJdbc(jdbc),
                                new FichaRepositoryJdbc(jdbc)),
                        gestor);
        PadronVehicular vehiculos =
                envolver(new PadronVehicularRentas(new VehiculoRepositoryJdbc(jdbc)), gestor);

        RegistrarActaFiscalizacion actas =
                envolver(
                        new RegistrarActaFiscalizacion(
                                new ActaFiscalizacionRepositoryJdbc(jdbc),
                                programas,
                                new SinFichas(),
                                padron,
                                vehiculos,
                                (RegistroDeAuditoria registro) -> {}),
                        gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new ProgramasController(
                                        envolver(
                                                new RegistrarPrograma(
                                                        programas,
                                                        (RegistroDeAuditoria registro) -> {}),
                                                gestor),
                                        envolver(new ConsultaDeProgramas(programas), gestor)),
                                new ActaPredialController(actas, padron),
                                new ActaVehicularController(actas, padron))
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
        OrigenContext.fijar(new Origen("fiscalizador.campo", "PC-42", "10.0.4.22"));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ── 1. El codigo de programa que ya existe ────────────────────────

    /**
     * El codigo repetido es 409 CONFLICTO, nombrando el codigo que el usuario escribio y nada del
     * esquema. Es tambien el caso del simple reintento: el segundo {@code POST} identico.
     */
    @Test
    @DisplayName("un programa con un codigo que ya existe es 409, y nombra el codigo")
    void elCodigoRepetidoEs409() throws Exception {
        String cuerpo = programa("PF-2026-01", null);
        assertThat(enviar(PROGRAMAS, cuerpo).getResponse().getStatus()).isEqualTo(201);

        Rechazo rechazo = rechazo(() -> enviar(PROGRAMAS, cuerpo));

        assertThat(rechazo.estado())
                .as("el mismo codigo otra vez: un reintento, o dos ventanillas")
                .isEqualTo(409);
        assertThat(rechazo.cuerpo())
                .contains("CONFLICTO")
                .contains("PF-2026-01")
                .doesNotContain("programa_codigo_uq")
                .doesNotContain("incidencia");
        assertThat(rechazo.errores())
                .as("un rechazo del usuario no es una incidencia del servidor")
                .isEmpty();
        assertThat(
                        contar(
                                "SELECT count(*) FROM programa_fiscalizacion WHERE codigo = 'PF-2026-01'"))
                .as("y el primero sigue siendo el unico")
                .isEqualTo(1);
    }

    // ── 12. El sector mas ancho que su columna ────────────────────────

    /**
     * {@code programa_fiscalizacion.sector_codigo} es {@code varchar(10)}: once caracteres son 422
     * y no el 22001 del motor. Y diez caben, que es lo que distingue un tope de una prohibicion.
     */
    @Test
    @DisplayName("un sector de 11 caracteres es 422, y uno de 10 se programa")
    void elSectorMasAnchoQueSuColumnaEs422() throws Exception {
        Rechazo rechazo = rechazo(() -> enviar(PROGRAMAS, programa("PF-422-S1", "SECTOR-NOR")));
        assertThat(rechazo.estado())
                .as("diez caracteres caben: %s", rechazo.cuerpo())
                .isEqualTo(201);

        Rechazo ancho = rechazo(() -> enviar(PROGRAMAS, programa("PF-422-S2", "SECTOR-NORT")));

        assertThat(ancho.estado()).as("once no caben en varchar(10)").isEqualTo(422);
        assertThat(ancho.cuerpo())
                .contains("VALIDACION")
                .contains("10")
                .doesNotContain("sector_codigo")
                .doesNotContain("incidencia");
        assertThat(ancho.errores()).isEmpty();
        assertThat(contar("SELECT count(*) FROM programa_fiscalizacion WHERE codigo = 'PF-422-S2'"))
                .isZero();
    }

    // ── 8. El acta de alguien que no esta en el padron ────────────────

    @Test
    @DisplayName("un acta predial de un contribuyente que no existe es 404, y no se guarda")
    void elActaPredialDeUnContribuyenteInexistenteEs404() throws Exception {
        Rechazo rechazo =
                rechazo(() -> enviar(ACTAS_PREDIALES, actaPredial(programaPredial, INEXISTENTE)));

        assertThat(rechazo.estado()).isEqualTo(404);
        assertThat(rechazo.cuerpo())
                .contains("NO_ENCONTRADO")
                .contains(String.valueOf(INEXISTENTE))
                .doesNotContain("acta_fisc_contribuyente_fk")
                .doesNotContain("incidencia");
        assertThat(rechazo.errores()).isEmpty();
        assertThat(actasDelContribuyente(INEXISTENTE)).isZero();
    }

    @Test
    @DisplayName("un acta vehicular de un vehiculo que no existe es 404, y no se guarda")
    void elActaVehicularDeUnVehiculoInexistenteEs404() throws Exception {
        Rechazo rechazo =
                rechazo(
                        () ->
                                enviar(
                                        ACTAS_VEHICULARES,
                                        actaVehicular(programaVehicular, titular, INEXISTENTE)));

        assertThat(rechazo.estado())
                .as("acta_fisc_vehiculo_fk es NOT VALID y aun asi obliga a las filas nuevas")
                .isEqualTo(404);
        assertThat(rechazo.cuerpo())
                .contains("NO_ENCONTRADO")
                .contains(String.valueOf(INEXISTENTE))
                .doesNotContain("acta_fisc_vehiculo_fk")
                .doesNotContain("incidencia");
        assertThat(rechazo.errores()).isEmpty();
        assertThat(actasDelContribuyente(titular)).isZero();
    }

    @Test
    @DisplayName("un acta vehicular de un contribuyente que no existe es 404, y no se guarda")
    void elActaVehicularDeUnContribuyenteInexistenteEs404() throws Exception {
        Rechazo rechazo =
                rechazo(
                        () ->
                                enviar(
                                        ACTAS_VEHICULARES,
                                        actaVehicular(programaVehicular, INEXISTENTE, vehiculo)));

        assertThat(rechazo.estado()).isEqualTo(404);
        assertThat(rechazo.cuerpo())
                .contains(String.valueOf(INEXISTENTE))
                .doesNotContain("acta_fisc_contribuyente_fk");
        assertThat(rechazo.errores()).isEmpty();
        assertThat(actasDelContribuyente(INEXISTENTE)).isZero();
    }

    // ------------------------------------------------------------------

    private static String programa(String codigo, @Nullable String sector) {
        return "{\"observacion\":\"Se programa para la prueba\",\"codigo\":\""
                + codigo
                + "\",\"descripcion\":\"Muestra de riesgo\",\"tipo\":\"PREDIAL\","
                + "\"fechaInicio\":\"2026-03-01\""
                + (sector == null ? "" : ",\"sector\":\"" + sector + "\"")
                + "}";
    }

    private static String actaPredial(long programaId, long contribuyenteId) {
        return "{\"observacion\":\"Visita de la prueba\",\"programaId\":"
                + programaId
                + ",\"contribuyenteId\":"
                + contribuyenteId
                + ",\"predioId\":1,\"fechaVisita\":\"2026-03-10\","
                + "\"fiscalizador\":\"INSPECTOR, UNO\",\"hallazgo\":\"CONFORME\"}";
    }

    private static String actaVehicular(long programaId, long contribuyenteId, long vehiculoId) {
        return "{\"observacion\":\"Inspeccion de la prueba\",\"programaId\":"
                + programaId
                + ",\"contribuyenteId\":"
                + contribuyenteId
                + ",\"vehiculoId\":"
                + vehiculoId
                + ",\"fechaVisita\":\"2026-03-10\","
                + "\"fiscalizador\":\"INSPECTOR, UNO\",\"hallazgo\":\"CONFORME\"}";
    }

    private static MvcResult enviar(String ruta, String cuerpo) throws Exception {
        return mvc.perform(post(ruta).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andReturn();
    }

    /** Lo que un rechazo contesta, y las lineas ERROR que dejo en el registro del manejador. */
    private record Rechazo(int estado, String cuerpo, List<String> errores) {}

    private static Rechazo rechazo(Callable<MvcResult> peticion) throws Exception {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ListAppender<ILoggingEvent> anotados = new ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        MvcResult resultado;
        try {
            resultado = peticion.call();
        } finally {
            registro.detachAppender(anotados);
        }
        return new Rechazo(
                resultado.getResponse().getStatus(),
                resultado.getResponse().getContentAsString(),
                anotados.list.stream()
                        .filter(e -> e.getLevel() == Level.ERROR)
                        .map(ILoggingEvent::getFormattedMessage)
                        .toList());
    }

    private static long actasDelContribuyente(long contribuyenteId) throws SQLException {
        return contar(
                "SELECT count(*) FROM acta_fiscalizacion WHERE contribuyente_id = "
                        + contribuyenteId);
    }

    /** Cuenta como superusuario, acotando a la municipalidad de la prueba. */
    private static long contar(String consulta) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                consulta
                                        + (consulta.contains("WHERE") ? " AND" : " WHERE")
                                        + " municipalidad_id = ?")) {
            sentencia.setLong(1, municipalidad);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                return resultado.getLong(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, PlatformTransactionManager gestor) {
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

    private static long crearPrograma(String codigo, String tipo) {
        return comoApp(
                "INSERT INTO programa_fiscalizacion (municipalidad_id, codigo, descripcion, tipo,"
                        + " fecha_inicio) VALUES (?, ?, 'Programa de la prueba', ?, ?)"
                        + " RETURNING id",
                municipalidad,
                codigo,
                tipo,
                LocalDate.of(2026, 1, 1));
    }

    private static long comoApp(String sql, Object... valores) {
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

    /** Ningun predio de la prueba tiene ficha: el acta no la necesita para rechazarse. */
    private static final class SinFichas implements LectorDeFichas {

        @Override
        public Optional<Long> fichaVigenteEn(long predioId, LocalDate fecha) {
            return Optional.empty();
        }

        @Override
        public Optional<AreaM2> areaDeLaVersion(long fichaId) {
            return Optional.empty();
        }
    }
}
