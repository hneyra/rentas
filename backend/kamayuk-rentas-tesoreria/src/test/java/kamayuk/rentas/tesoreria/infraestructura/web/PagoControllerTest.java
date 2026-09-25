package kamayuk.rentas.tesoreria.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistroDeAbonosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SinAcumulacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.tesoreria.infraestructura.PagoRecibidoRepositoryJdbc;
import kamayuk.rentas.tesoreria.pagos.ConciliacionDePagos;
import kamayuk.rentas.tesoreria.pagos.EstadoDelPagoRecibido;
import kamayuk.rentas.tesoreria.pagos.ImputacionDelPago;
import kamayuk.rentas.tesoreria.pagos.PagoRecibido;
import kamayuk.rentas.tesoreria.pagos.PagoRecibidoRepository;
import kamayuk.rentas.tesoreria.pagos.RechazoDelPago;
import kamayuk.rentas.tesoreria.pagos.RecibirPago;
import kamayuk.rentas.tesoreria.pagos.ReferenciaDeObligacion;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * El borde por el que la caja entrega sus pagos: quien puede llamarlo (#429) y lo que contesta a
 * una anulacion adelantada (#428).
 *
 * <p>Las pruebas de #428 van con un doble del caso de uso y sin base: lo que se mide es <b>el
 * codigo de estado</b>, porque es lo unico que el publicador de la caja lee. Para el, 201 y 409 son
 * exito —marca el evento {@code ENTREGADO} y no lo vuelve a mandar— y cualquier 5xx es «reintenta».
 * Que una anulacion que llego antes que su cobro conteste 201 es el defecto de #428 entero; que
 * conteste 503 es lo que hace que la vuelta siguiente la encuentre con su cobro ya imputado.
 *
 * <p>Las de #429 van contra PostgreSQL y con el caso de uso de verdad: ver {@link
 * SoloLaCuentaDeServicioDeLaCaja}.
 */
@DisplayName("El borde del buzon de pagos: quien lo llama (#429) y la anulacion adelantada (#428)")
class PagoControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(
                    LocalDate.of(2026, 3, 16).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    ZoneOffset.UTC);

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);

    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                    .build();

    /** La municipalidad de la prueba. Sullana, que es la de la marcha blanca. */
    private static final String UBIGEO = "200601";

    /**
     * El cliente con que entra la interfaz: el {@code azp} de un token de usuario. Es el valor por
     * omision de {@code oidcCliente} en {@code frontend/src/api/configuracion.ts}.
     */
    private static final String AZP_DE_LA_INTERFAZ = "kamayuk-backoffice";

    /** El cliente confidencial con que la caja pide su token (ADR-0028 §2). */
    private static final String AZP_DE_LA_CAJA = "kamayuk-caja-servicio-" + UBIGEO;

    /** Otro sistema, con una cuenta de servicio igual de legitima: la de catastro. */
    private static final String AZP_DE_CATASTRO = "kamayuk-catastro-servicio-" + UBIGEO;

    /** Una persona con la caja entera, como {@code jperez} en la captura de la instalacion. */
    private static final String CAJERO = "jperez";

    private static final String CUENTA_DE_LA_CAJA = "service-account-" + AZP_DE_LA_CAJA;
    private static final String CUENTA_DE_CATASTRO = "service-account-" + AZP_DE_CATASTRO;

    /**
     * La siembra que distingue (#429): <b>los tres tienen el permiso</b>.
     *
     * <p>El cajero humano tiene {@code caja_tributaria} con los siete privilegios —como {@code
     * jperez} en {@code frontend/src/datos/seguridadMedida.ts}— y la cuenta de catastro tambien
     * tiene {@code REGISTRO} y {@code LECTURA}. Con un usuario SIN el permiso, el 403 saldria hoy
     * igual y la prueba no mediria nada: lo unico que separa a los tres es <b>quien</b> pidio el
     * token.
     */
    private static final Map<String, Set<Privilegio>> CAJA_TRIBUTARIA =
            Map.of(
                    CAJERO, EnumSet.allOf(Privilegio.class),
                    CUENTA_DE_LA_CAJA, EnumSet.of(Privilegio.REGISTRO, Privilegio.LECTURA),
                    CUENTA_DE_CATASTRO, EnumSet.of(Privilegio.REGISTRO, Privilegio.LECTURA));

    /** El caso de uso, contestando lo que cada prueba le diga. */
    private final CasoDeUsoDeMentira caso = new CasoDeUsoDeMentira();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new PagoController(caso, new ConciliacionDePagos(null), JSON, RELOJ))
                    .addInterceptors(new GuardiaDeAcceso(new TodoAutorizado(), RELOJ))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(new JacksonJsonHttpMessageConverter(JSON))
                    .build();

    /** Por omision entra la caja, que es quien llama a este borde en produccion. */
    @BeforeEach
    void entraLaCaja() {
        como(AZP_DE_LA_CAJA, "publicador.caja");
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName(
            "#428 — la anulacion que llega antes que su cobro es 503, que la caja reintenta, y no"
                    + " 201")
    void laAnulacionAdelantadaEsQuinientosTres() throws Exception {
        caso.todaviaNo = true;

        MvcResult resultado = entregar(mvc, anulacion());

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "un 201 la caja lo marca ENTREGADO y no lo vuelve a mandar: la anulacion"
                                + " se perderia y el cobro que llega despues se imputaria")
                .isEqualTo(503);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("SERVICIO_NO_DISPONIBLE")
                .contains("todavia no esta en el buzon");
    }

    @Test
    @DisplayName("#428 — el contraste: la misma anulacion con su cobro ya imputado es 201")
    void conSuCobroImputadoEsDoscientosUno() throws Exception {
        caso.todaviaNo = false;

        MvcResult resultado = entregar(mvc, anulacion());

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"estado\":\"APLICADO\"");
    }

    /**
     * Las dos horas del acuse salen con el desfase de la zona del producto (#327).
     *
     * <p>El reloj de esta clase marca la medianoche UTC del 16 de marzo, que en el Peru son <b>las
     * 19:00 del 15</b>: la franja en que el dia de UTC y el local discrepan, asi que un fallo aqui
     * no cambia solo la hora, cambia la fecha. Hasta #327 los dos campos eran {@code String}
     * escritos con {@code Instant.toString()} y salian {@code 2026-03-16T00:00:00Z}: el contrato
     * los tipaba «texto» y la guarda de #188, que mira el tipo, no los veia.
     */
    @Test
    @DisplayName("#327 — recibidoEn y aplicadoEn salen con -05:00, no con una Z")
    void lasHorasDelAcuseLlevanSuDesfase() throws Exception {
        caso.todaviaNo = false;

        MvcResult resultado = entregar(mvc, anulacion());

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        JsonNode acuse = JSON.readTree(resultado.getResponse().getContentAsString());
        assertThat(acuse.get("recibidoEn").asString())
                .as("las 19:00 del 15 en el Peru, que en UTC ya son las 00:00 del 16")
                .isEqualTo("2026-03-15T19:00:00-05:00");
        assertThat(acuse.get("aplicadoEn").asString())
                .as("y la hora de aplicacion, igual: el mismo instante, con su desfase encima")
                .isEqualTo("2026-03-15T19:00:00-05:00");
    }

    /**
     * #429 — el buzon de pagos es de la cuenta de servicio de la caja, y de nadie mas.
     *
     * <p>Contra PostgreSQL y con {@link RecibirPago} de verdad —el buzon, la imputacion y el libro
     * de la cuenta corriente—, porque lo que el issue mide no es solo un codigo de estado sino
     * <b>lo que queda escrito</b>: que un 403 no deje ni una fila en {@code pago_recibido} ni un
     * abono en el libro, y que el 201 de la caja si los deje. Con un doble del caso de uso, «no se
     * llamo» seria una afirmacion sobre el doble.
     *
     * <p>El guardia es el de produccion y el comprobador de permisos siembra {@link
     * #CAJA_TRIBUTARIA}: los tres llamadores tienen el permiso.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("#429 — el buzon de pagos es de la cuenta de servicio de la caja")
    class SoloLaCuentaDeServicioDeLaCaja {

        private static final Ejercicio EJERCICIO = new Ejercicio(2026);
        private static final String TRIBUTO = "PREDIAL";
        private static final String DEUDA = "500.00";

        private BaseDeDatosDePrueba base;
        private long municipalidad;
        private TenantTransactionManager gestor;
        private TransactionTemplate transaccion;
        private RegistrarAsiento registrarAsiento;
        private MockMvc buzonDePagos;
        private final AtomicInteger contribuyentes = new AtomicInteger();

        @BeforeAll
        void provisionar() throws SQLException, IOException {
            base = BaseDeDatosDePrueba.provisionar();
            municipalidad = crearMunicipalidad();

            DriverManagerDataSource pool = new DriverManagerDataSource();
            pool.setUrl(base.url());
            pool.setUsername(BaseDeDatosDePrueba.APP);
            pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

            JdbcClient jdbc = JdbcClient.create(pool);
            gestor = new TenantTransactionManager(pool);
            transaccion = new TransactionTemplate(gestor);

            Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
            AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
            SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
            registrarAsiento = envolver(new RegistrarAsiento(asientos, saldos, auditoria, RELOJ));
            RegistroDeAbonos abonos =
                    envolver(
                            new RegistroDeAbonosCuentaCorriente(
                                    asientos,
                                    saldos,
                                    registrarAsiento,
                                    new CalculoDeDeuda(new SinAcumulacion()),
                                    new PoliticaDeRedondeo(2, RoundingMode.HALF_UP)));
            PagoRecibidoRepository buzon = new PagoRecibidoRepositoryJdbc(jdbc);
            // Cada objeto con su proxy, como en PagoInyectadoDosVecesTest: la auto-invocacion no
            // pasa por el proxy y la anotacion no se aplicaria (#536, #430).
            RecibirPago recibir =
                    new RecibirPago(
                            envolver(new ImputacionDelPago(buzon, abonos, RELOJ)),
                            envolver(new RechazoDelPago(buzon)));

            buzonDePagos =
                    MockMvcBuilders.standaloneSetup(
                                    new PagoController(
                                            recibir,
                                            envolver(new ConciliacionDePagos(buzon)),
                                            JSON,
                                            RELOJ))
                            .addInterceptors(new GuardiaDeAcceso(new PermisosSembrados(), RELOJ))
                            .setControllerAdvice(new ManejadorDeErrores())
                            .setMessageConverters(new JacksonJsonHttpMessageConverter(JSON))
                            .build();
        }

        @AfterAll
        void cerrarBase() {
            if (base != null) {
                base.close();
            }
        }

        @BeforeEach
        void fijarMunicipalidad() {
            TenantContext.fijar(new MunicipalidadId(municipalidad));
        }

        @AfterEach
        void limpiarMunicipalidad() {
            TenantContext.limpiar();
        }

        @Test
        @DisplayName(
                "un cajero con caja_tributaria y los siete privilegios, desde la interfaz: 403"
                        + " SIN_IDENTIDAD_DE_SERVICIO, y ni una fila en pago_recibido")
        void elCajeroDesdeLaInterfazNoExtingueDeuda() throws Exception {
            long contribuyente = sembrarContribuyenteConDeuda();
            UUID pagoId = UUID.randomUUID();
            como(AZP_DE_LA_INTERFAZ, CAJERO);

            MvcResult resultado = entregar(buzonDePagos, cobro(pagoId, contribuyente, "INT"));

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "el cajero TIENE caja_tributaria/REGISTRO: con el guardia de antes"
                                    + " de #429 esto era 201 y la deuda quedaba extinguida sin un"
                                    + " solo recibo en la caja")
                    .isEqualTo(403);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("SIN_IDENTIDAD_DE_SERVICIO");
            assertThat(filasDelBuzonCon(pagoId)).as("un 403 no deja nada en el buzon").isZero();
            assertThat(abonosDe(contribuyente)).as("ni un abono en el libro").isZero();
        }

        @Test
        @DisplayName("la cuenta de servicio de la caja: 201, APLICADO, con su fila y su abono")
        void laCajaSiEntrega() throws Exception {
            long contribuyente = sembrarContribuyenteConDeuda();
            UUID pagoId = UUID.randomUUID();
            como(AZP_DE_LA_CAJA, CUENTA_DE_LA_CAJA);

            MvcResult resultado = entregar(buzonDePagos, cobro(pagoId, contribuyente, "CAJ"));

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "el contraste: sin el, el 403 de arriba podria ser un buzon cerrado"
                                    + " para todos")
                    .isEqualTo(201);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"estado\":\"" + EstadoDelPagoRecibido.APLICADO + "\"");
            assertThat(filasDelBuzonCon(pagoId)).isEqualTo(1);
            assertThat(abonosDe(contribuyente)).isEqualTo(1);
        }

        @Test
        @DisplayName(
                "la cuenta de servicio de OTRO sistema, con el mismo permiso: 403"
                        + " SIN_IDENTIDAD_DE_SERVICIO")
        void otroSistemaNoEntrega() throws Exception {
            long contribuyente = sembrarContribuyenteConDeuda();
            UUID pagoId = UUID.randomUUID();
            como(AZP_DE_CATASTRO, CUENTA_DE_CATASTRO);

            MvcResult resultado = entregar(buzonDePagos, cobro(pagoId, contribuyente, "CAT"));

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "una cuenta de servicio no basta: tiene que ser la de la caja. Si la"
                                    + " regla fuera «cualquier kamayuk-*-servicio», esto pasaria")
                    .isEqualTo(403);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("SIN_IDENTIDAD_DE_SERVICIO")
                    .contains("catastro");
            assertThat(filasDelBuzonCon(pagoId)).isZero();
            assertThat(abonosDe(contribuyente)).isZero();
        }

        @Test
        @DisplayName(
                "el cajero desde la interfaz tampoco anula un cobro real: 403 y la deuda sigue"
                        + " pagada")
        void elCajeroNoReviveDeudaPagada() throws Exception {
            long contribuyente = sembrarContribuyenteConDeuda();
            UUID cobro = UUID.randomUUID();
            como(AZP_DE_LA_CAJA, CUENTA_DE_LA_CAJA);
            assertThat(
                            entregar(buzonDePagos, cobro(cobro, contribuyente, "ANU"))
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(201);
            UUID anulacion = UUID.randomUUID();
            como(AZP_DE_LA_INTERFAZ, CAJERO);

            MvcResult resultado =
                    entregar(buzonDePagos, anulacionDe(anulacion, cobro, contribuyente, "ANU"));

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "con el guardia de antes de #429, esto reversaba los abonos del cobro"
                                    + " y la deuda pagada volvia a estar viva")
                    .isEqualTo(403);
            assertThat(filasDelBuzonCon(anulacion)).isZero();
            assertThat(reversionesDe(contribuyente)).isZero();
        }

        @Test
        @DisplayName(
                "el permiso sigue siendo la segunda condicion: una caja sin caja_tributaria es 403"
                        + " SIN_PRIVILEGIO")
        void elPermisoSigueContando() throws Exception {
            long contribuyente = sembrarContribuyenteConDeuda();
            UUID pagoId = UUID.randomUUID();
            // La cuenta de servicio de la caja de OTRA municipalidad, que en esta siembra no
            // tiene el permiso: la forma del `azp` es buena y lo que falta es el privilegio.
            String otraCaja = "kamayuk-caja-servicio-200602";
            como(otraCaja, "service-account-" + otraCaja);

            MvcResult resultado = entregar(buzonDePagos, cobro(pagoId, contribuyente, "SPR"));

            assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("SIN_PRIVILEGIO")
                    .doesNotContain("SIN_IDENTIDAD_DE_SERVICIO");
            assertThat(filasDelBuzonCon(pagoId)).isZero();
        }

        @Test
        @DisplayName(
                "la conciliacion, que es la unica lectura de la caja: 403 al cajero y 200 a la"
                        + " caja")
        void laConciliacionTambien() throws Exception {
            como(AZP_DE_LA_INTERFAZ, CAJERO);
            MvcResult delCajero = conciliar();

            como(AZP_DE_LA_CAJA, CUENTA_DE_LA_CAJA);
            MvcResult deLaCaja = conciliar();

            assertThat(delCajero.getResponse().getStatus())
                    .as("el cajero tiene LECTURA sobre caja_tributaria")
                    .isEqualTo(403);
            assertThat(delCajero.getResponse().getContentAsString())
                    .contains("SIN_IDENTIDAD_DE_SERVICIO");
            assertThat(deLaCaja.getResponse().getStatus()).isEqualTo(200);
        }

        // ------------------------------------------------------------------

        private MvcResult conciliar() throws Exception {
            return buzonDePagos
                    .perform(
                            get("/rentas/api/v1/pagos/conciliacion").param("fecha", HOY.toString()))
                    .andReturn();
        }

        /** Lo que la caja publica al cobrar: la deuda exacta, a la fecha del cobro. */
        private String cobro(UUID pagoId, long contribuyente, String sufijo) {
            ReferenciaDeObligacion referencia =
                    new ReferenciaDeObligacion(TRIBUTO, EJERCICIO, contribuyente, null, null, HOY);
            return "{\"pagoId\":\""
                    + pagoId
                    + "\",\"tipo\":\"PAGO_REGISTRADO\",\"sistemaOrigen\":\"rentas\","
                    + "\"recibo\":{\"numero\":\"001-"
                    + sufijo
                    + contribuyente
                    + "\",\"fechaDePago\":\""
                    + HOY
                    + "\"},\"pagador\":{\"idExterno\":"
                    + contribuyente
                    + "},\"total\":\""
                    + DEUDA
                    + "\",\"ordenes\":[{\"referenciaExterna\":\""
                    + referencia.texto()
                    + "\"}]}";
        }

        private String anulacionDe(UUID pagoId, UUID original, long contribuyente, String sufijo) {
            return "{\"pagoId\":\""
                    + pagoId
                    + "\",\"tipo\":\"PAGO_ANULADO\",\"pagoOriginalId\":\""
                    + original
                    + "\",\"sistemaOrigen\":\"rentas\",\"total\":\""
                    + DEUDA
                    + "\",\"motivo\":\"ANULADO DESDE EL NAVEGADOR\",\"fecha\":\""
                    + HOY
                    + "\",\"recibo\":{\"numero\":\"001-"
                    + sufijo
                    + contribuyente
                    + "\",\"fechaDePago\":\""
                    + HOY
                    + "\"},\"pagador\":{\"idExterno\":"
                    + contribuyente
                    + "}}";
        }

        /**
         * Un contribuyente propio por prueba, con su cargo de {@link #DEUDA}.
         *
         * <p>Propio y no compartido: un cobro de PREDIAL 2026 de una prueba encontraria la deuda de
         * otra, y la prueba mediria el orden de ejecucion en vez de la autorizacion.
         */
        private long sembrarContribuyenteConDeuda() throws SQLException {
            int n = contribuyentes.incrementAndGet();
            long contribuyente;
            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                ContextoDeTenant.fijar(app, municipalidad);
                contribuyente =
                        insertar(
                                app,
                                "INSERT INTO contribuyente (municipalidad_id,"
                                        + " codigo_contribuyente, tipo_persona, tipo_documento,"
                                        + " numero_documento, nombre_razon_social, activo,"
                                        + " usuario_registro)"
                                        + " VALUES (?, ?, 'NATURAL', 'DNI', ?, 'FULANO DE TAL',"
                                        + " true, 'prueba') RETURNING id",
                                municipalidad,
                                "C-4290" + n,
                                "7042900" + n);
                app.commit();
            }
            transaccion.executeWithoutResult(
                    estado ->
                            registrarAsiento.asentar(
                                    Asiento.nuevo(
                                            EJERCICIO,
                                            contribuyente,
                                            TRIBUTO,
                                            Concepto.INSOLUTO,
                                            TipoAsiento.CARGO,
                                            Fase.ORDINARIA,
                                            null,
                                            null,
                                            null,
                                            null,
                                            Dinero.de(DEUDA),
                                            HOY.minusMonths(1),
                                            "EMISION 429-" + n),
                                    Observacion.de("emision de la prueba de #429")));
            return contribuyente;
        }

        private long crearMunicipalidad() throws SQLException {
            try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
                long id =
                        insertar(
                                owner,
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES (?, 'Municipalidad del buzon de pagos',"
                                        + " 'DISTRITAL') RETURNING id",
                                UBIGEO);
                owner.commit();
                return id;
            }
        }

        private int filasDelBuzonCon(UUID pagoId) {
            return contar(
                    "SELECT count(*) FROM pago_recibido WHERE pago_id = CAST(? AS uuid)",
                    pagoId.toString());
        }

        private int abonosDe(long contribuyente) {
            return contar(
                    "SELECT count(*) FROM cuenta_corriente_asiento"
                            + " WHERE contribuyente_id = ? AND tipo = 'ABONO'",
                    contribuyente);
        }

        /** Los asientos de una anulacion: los que llevan {@code documentoDeLaAnulacion()}. */
        private int reversionesDe(long contribuyente) {
            return contar(
                    "SELECT count(*) FROM cuenta_corriente_asiento"
                            + " WHERE contribuyente_id = ? AND documento_origen LIKE 'ANULACION %'",
                    contribuyente);
        }

        private int contar(String sql, Object... valores) {
            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                ContextoDeTenant.fijar(app, municipalidad);
                try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                    for (int i = 0; i < valores.length; i++) {
                        sentencia.setObject(i + 1, valores[i]);
                    }
                    try (ResultSet fila = sentencia.executeQuery()) {
                        fila.next();
                        return fila.getInt(1);
                    }
                }
            } catch (SQLException noSePudo) {
                throw new IllegalStateException("No se pudo contar", noSePudo);
            }
        }

        private long insertar(Connection conexion, String sql, Object... valores)
                throws SQLException {
            try (PreparedStatement sentencia = conexion.prepareStatement(sql)) {
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setObject(i + 1, valores[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    if (!resultado.next()) {
                        throw new IllegalStateException("La sentencia no devolvio ninguna fila");
                    }
                    return resultado.getLong(1);
                }
            }
        }

        @SuppressWarnings("unchecked")
        private <T> T envolver(T objetivo) {
            ProxyFactory fabrica = new ProxyFactory(objetivo);
            fabrica.setProxyTargetClass(true);
            fabrica.addAdvice(
                    new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
            return (T) fabrica.getProxy();
        }
    }

    // ------------------------------------------------------------------

    /**
     * Quien llama: el token ya validado, como lo deja Spring Security, y el origen, como lo deja
     * {@code OrigenContextFilter} a partir de su {@code preferred_username}.
     */
    private static void como(String azp, String cuenta) {
        Jwt token =
                Jwt.withTokenValue("prueba")
                        .header("alg", "none")
                        .claim("azp", azp)
                        .claim("preferred_username", cuenta)
                        .build();
        // Con la lista de autoridades, aunque este vacia: es el constructor que marca la
        // autenticacion como hecha, que es lo que deja el conversor de Spring Security. Sin ella
        // el guardia ve un token sin autenticar y no lee su `azp`.
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(token, List.of()));
        OrigenContext.fijar(new Origen(cuenta, null, null));
    }

    private static MvcResult entregar(MockMvc mvc, String cuerpo) throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/pagos")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }

    private static String anulacion() {
        return "{\"pagoId\":\""
                + UUID.randomUUID()
                + "\",\"tipo\":\"PAGO_ANULADO\",\"pagoOriginalId\":\""
                + UUID.randomUUID()
                + "\",\"sistemaOrigen\":\"rentas\",\"total\":\"500.00\","
                + "\"motivo\":\"ERROR EN EL IMPORTE COBRADO\",\"fecha\":\"2026-03-16\","
                + "\"recibo\":{\"numero\":\"001-0000123\",\"fechaDePago\":\"2026-03-16\"}}";
    }

    /**
     * El caso de uso sin base: o salta «todavia no», o devuelve la anulacion aplicada.
     *
     * <p>Una subclase y no un simulador: {@code RecibirPago} es una clase concreta, y lo unico que
     * el borde le pide es {@code recibir}.
     */
    private static final class CasoDeUsoDeMentira extends RecibirPago {
        private boolean todaviaNo;

        CasoDeUsoDeMentira() {
            super(null, null);
        }

        @Override
        public Recibido recibir(PagoRecibido pago) {
            if (todaviaNo) {
                throw new AnulacionAntesQueSuCobro(
                        "La anulacion "
                                + pago.pagoId()
                                + " nombra el pago "
                                + pago.pagoOriginalId()
                                + ", que todavia no esta en el buzon");
            }
            return new Recibido(
                    new PagoRecibido(
                            1L,
                            pago.pagoId(),
                            pago.tipo(),
                            pago.pagoOriginalId(),
                            pago.sistemaCaja(),
                            pago.reciboNumero(),
                            pago.contribuyenteId(),
                            pago.fechaDePago(),
                            pago.motivoDeLaAnulacion(),
                            pago.fechaDeAnulacion(),
                            pago.total(),
                            List.of(),
                            pago.cuerpo(),
                            EstadoDelPagoRecibido.APLICADO,
                            1,
                            null,
                            pago.recibidoEn(),
                            RELOJ.instant()),
                    true);
        }
    }

    private static final class TodoAutorizado implements ComprobadorDeAcceso {
        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            return true;
        }
    }

    /** {@link #CAJA_TRIBUTARIA} y nada mas: ninguna otra opcion del catalogo. */
    private static final class PermisosSembrados implements ComprobadorDeAcceso {
        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            return "caja_tributaria".equals(acceso)
                    && CAJA_TRIBUTARIA.getOrDefault(usuario, Set.of()).contains(privilegio);
        }
    }
}
