package kamayuk.rentas.sanciones.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ExtincionDeDeuda;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.ExtincionDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.GeneradorDeCargosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SinAcumulacion;
import kamayuk.rentas.documentos.DocumentoRepositoryJdbc;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.ActoFueraDeOrden;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.PadronVehicular;
import kamayuk.rentas.nucleo.aplicacion.PadronVehicularRentas;
import kamayuk.rentas.nucleo.infraestructura.VehiculoRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.sanciones.PapeletasSinNotificar;
import kamayuk.rentas.sanciones.aplicacion.AnularPapeleta;
import kamayuk.rentas.sanciones.aplicacion.CambiarNumeroDePapeleta;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeActosDeLaPapeleta;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeInternamientos;
import kamayuk.rentas.sanciones.aplicacion.DeclararAbandonoDeVehiculo;
import kamayuk.rentas.sanciones.aplicacion.EmitirConstanciaLibre;
import kamayuk.rentas.sanciones.aplicacion.LiberarVehiculoInternado;
import kamayuk.rentas.sanciones.aplicacion.NotificarResolucionDeGerencia;
import kamayuk.rentas.sanciones.aplicacion.ObligacionCompartidaConOtraPapeleta;
import kamayuk.rentas.sanciones.aplicacion.PapeletasSinNotificarSanciones;
import kamayuk.rentas.sanciones.aplicacion.PlazosDeSancionesParametrizados;
import kamayuk.rentas.sanciones.aplicacion.RegistrarDescargo;
import kamayuk.rentas.sanciones.aplicacion.RegistrarInternamiento;
import kamayuk.rentas.sanciones.aplicacion.RegistrarNotificacionAdministrativa;
import kamayuk.rentas.sanciones.aplicacion.RegistrarPapeleta;
import kamayuk.rentas.sanciones.aplicacion.ResolverConResolucionDeGerencia;
import kamayuk.rentas.sanciones.dobles.CobrosDeMentira;
import kamayuk.rentas.sanciones.dominio.ActoDeLaPapeleta;
import kamayuk.rentas.sanciones.dominio.AcuseDelActo;
import kamayuk.rentas.sanciones.dominio.AgrupacionDelResumen;
import kamayuk.rentas.sanciones.dominio.ConstanciaLibre;
import kamayuk.rentas.sanciones.dominio.CriterioDeConstancias;
import kamayuk.rentas.sanciones.dominio.CriterioDeInternamiento;
import kamayuk.rentas.sanciones.dominio.CriterioDePadron;
import kamayuk.rentas.sanciones.dominio.CriterioDePapeleta;
import kamayuk.rentas.sanciones.dominio.CriterioDelProcedimiento;
import kamayuk.rentas.sanciones.dominio.Descargo;
import kamayuk.rentas.sanciones.dominio.EfectoSobreLaMulta;
import kamayuk.rentas.sanciones.dominio.EstadoDeInternamiento;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.EstadoDelActoDeLaPapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.FaseDelProcedimiento;
import kamayuk.rentas.sanciones.dominio.InternamientoEnConsulta;
import kamayuk.rentas.sanciones.dominio.LineaDelResumen;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaDelPadron;
import kamayuk.rentas.sanciones.dominio.ProcedimientoSancionador;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerencia;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerenciaRepository;
import kamayuk.rentas.sanciones.dominio.SentidoDelFallo;
import kamayuk.rentas.sanciones.dominio.TipoDeRecurso;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import kamayuk.rentas.sanciones.infraestructura.web.CambioDeNumeroController;
import kamayuk.rentas.sanciones.infraestructura.web.ConstanciasLibresController;
import kamayuk.rentas.sanciones.infraestructura.web.DescargosController;
import kamayuk.rentas.sanciones.infraestructura.web.InternamientosController;
import kamayuk.rentas.sanciones.infraestructura.web.NotificacionAdministrativaController;
import kamayuk.rentas.tesoreria.infraestructura.AplicacionDeRecibosJdbc;
import kamayuk.rentas.valores.ValoresSobreUnaObligacion;
import kamayuk.rentas.valores.aplicacion.ValoresSobreUnaObligacionValores;
import kamayuk.rentas.valores.infraestructura.ValorRepositoryJdbc;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * #50 — Descargos, internamiento vehicular y resoluciones de gerencia contra PostgreSQL de verdad
 * (V41), conectado como {@code kamayuk_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>AC 1 — un descargo procedente no borra la papeleta.</b> La fila sigue ahí y lo que
 *       cambia es el <b>libro</b>: la baja se asienta con su motivo, y {@code deudaActualizadaA}
 *       vuelve a dar cero sin que nadie haya escrito esa cifra en ningún sitio. Contra un doble
 *       esto solo probaría que el doble recuerda lo que se le dijo.
 *   <li><b>AC 2 — no hay sancionadora sin ordinaria notificada y sin plazo vencido.</b> Las tres
 *       condiciones por separado, y la última <b>además por SQL directo</b>: {@code
 *       resolucion_gerencia_plazo_ck} es lo que queda cuando alguien se salta el caso de uso.
 *   <li><b>AC 3 — la liberación exige el pago de la custodia, verificado contra {@code
 *       tesoreria}.</b> Con un recibo que no existe, con uno anulado, con uno que cobró otro
 *       concepto, y con el bueno. La casilla del prototipo la marca quien entrega el vehículo; el
 *       recibo lo dice la caja.
 *   <li><b>AC 4 — todos los documentos emitidos por una papeleta, con su fecha y su acuse.</b>
 *       Resolución, acta de ingreso y acta de liberación en una sola secuencia, y los dos intentos
 *       de notificación, no solo el que encontró a alguien.
 *   <li><b>AC 5 — cada acto deja auditoría.</b> Se cuentan las filas de {@code auditoria}.
 *   <li><b>Que dos peticiones simultáneas no dicten dos ordinarias.</b> Un doble que consulta antes
 *       de insertar pasa la prueba y falla en producción: diez peticiones a la vez pasan las diez
 *       por el {@code if}. Aquí se lanzan diez hilos.
 *   <li><b>Que {@code kamayuk_app} no pueda editar una resolución ni un internamiento.</b> Es el
 *       {@code REVOKE} de V41, y se comprueba intentándolo.
 *   <li><b>Que RLS aísle</b>: desde otra municipalidad, la resolución no existe.
 * </ul>
 */
@DisplayName("#50 — Descargos, internamiento y resoluciones de gerencia contra PostgreSQL")
class SancionesJdbcTest {

    /** El día de la infracción: miércoles 4 de marzo de 2026. */
    private static final LocalDate INFRACCION = LocalDate.of(2026, 3, 4);

    /**
     * Las <b>10:00 del día de la infracción</b>, hora del Perú, como instante.
     *
     * <p>Es el ingreso al depósito de casi todas las pruebas de esta clase, y su hora está elegida
     * para <b>no</b> distinguir nada: a las 10:00 de Catacaos la fecha UTC y la local coinciden,
     * así que ninguna de esas pruebas cambia de color según la zona. Las dos que SÍ tienen que
     * distinguir usan {@link #INGRESO_NOCTURNO} y están en {@code ElInternamientoNocturno}.
     *
     * <p>Hasta #273 esto era {@code INFRACCION.atStartOfDay(ZoneOffset.UTC)}, que es la medianoche
     * UTC, o sea las <b>19:00 del día 3</b> en el Perú: sembraba dentro de la franja rota sin
     * decirlo, y con la zona puesta la cuenta de días habría salido en 29 en vez de 28. Está
     * escrito como instante UTC y no compuesto con {@code ZonaHoraria.DEL_PRODUCTO} a propósito:
     * una prueba que compusiera la hora con la misma constante que verifica no verificaría nada.
     */
    private static final Instant INGRESO_DE_DIA = Instant.parse("2026-03-04T15:00:00Z");

    /**
     * Las <b>20:00 del día de la infracción</b>, hora del Perú: la franja de #273.
     *
     * <p>El mismo instante en UTC ya es del <b>día 5</b>. Todo internamiento entre las 19:00 y la
     * medianoche caía ahí, y son cinco horas de cada día, no un caso de borde.
     */
    private static final Instant INGRESO_NOCTURNO = Instant.parse("2026-03-05T01:00:00Z");

    /**
     * Hasta cuándo se admite el descargo, con el plazo <b>parametrizado</b> de 5 días hábiles.
     *
     * <p>La cuenta, día a día: la papeleta es del miércoles 4; el cómputo empieza el jueves 5 (día
     * hábil siguiente); cinco días hábiles desde ahí son 6, 9, 10, 11 y 12. Está escrito aquí
     * porque una prueba que recalculara la fecha con el mismo código que verifica no verificaría
     * nada.
     */
    private static final LocalDate DESCARGO_HASTA = LocalDate.of(2026, 3, 12);

    /** El día en que se dicta la resolución ordinaria: miércoles 1 de abril. */
    private static final LocalDate ORDINARIA = LocalDate.of(2026, 4, 1);

    /** El día en que se diligencia la ordinaria: jueves 2 de abril. */
    private static final LocalDate DILIGENCIA = LocalDate.of(2026, 4, 2);

    /**
     * Desde cuándo cabe la sancionadora, con el plazo <b>parametrizado</b> de 7 días hábiles.
     *
     * <p>La diligencia es el jueves 2; surte efecto el viernes 3; siete días hábiles desde ahí son
     * 6, 7, 8, 9, 10, 13 y 14; el plazo vence el martes 14 y se puede sancionar desde el
     * <b>miércoles 15</b>.
     */
    private static final LocalDate SANCIONADORA_DESDE = LocalDate.of(2026, 4, 15);

    private static final Dinero MULTA = Dinero.de("428.00");
    private static final Dinero CUSTODIA = Dinero.de("198.00");
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-04-20T09:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static long conjuntoId;

    /**
     * Lo que `caja` contesta cuando se le pregunta por un cobro (P5D).
     *
     * <p>Hasta `V7` esto era la caja de verdad: `area`, `caja`, `cierre_caja`, `tasa`, el recibo y
     * su detalle, sembrados en esta misma base. Las diez tablas se fueron a `caja`, asi que lo que
     * queda —y lo unico que este sistema podia ver ya— es la respuesta del puerto.
     */
    private static CobrosDeMentira cobros;

    private static final AtomicInteger SIGUIENTE_RECIBO = new AtomicInteger(0);

    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static PapeletaRepositoryJdbc papeletas;
    private static CodigoInfraccionRepositoryJdbc codigos;
    private static DescargoRepositoryJdbc descargos;
    private static ResolucionDeGerenciaRepositoryJdbc resoluciones;
    private static NotificacionDeResolucionRepositoryJdbc diligencias;
    private static InternamientoRepositoryJdbc internamientos;

    private static RegistrarPapeleta registrarPapeleta;
    private static RegistrarDescargo registrarDescargo;
    private static AnularPapeleta anularPapeleta;

    /** #402: el dia en que se mide, con la infraccion del 4 de marzo muy atras. */
    private static final LocalDate HOY_DEL_402 = LocalDate.of(2026, 9, 23);

    /** #402: la misma anulacion con el reloj en {@link #HOY_DEL_402}. */
    private static AnularPapeleta anularAl23DeSetiembre;

    private static ResolverConResolucionDeGerencia resolver;
    private static NotificarResolucionDeGerencia notificar;
    private static RegistrarInternamiento internar;
    private static LiberarVehiculoInternado liberar;
    private static DeclararAbandonoDeVehiculo abandonar;
    private static ConsultaDeInternamientos consultaDeDeposito;
    private static ConsultaDeActosDeLaPapeleta consultaDeActos;
    private static ConsultaDeDeudaPublica deudas;

    /** Los cinco controladores de #422, sobre los mismos casos de uso y la misma base. */
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("250701", "Municipalidad de sanciones");
        otraMunicipalidad = crearMunicipalidad("250702", "Municipalidad vecina de #50");
        conjuntoId = crearConjuntoConLosPlazos(municipalidad);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        papeletas = new PapeletaRepositoryJdbc(jdbc);
        codigos = new CodigoInfraccionRepositoryJdbc(jdbc);
        descargos = new DescargoRepositoryJdbc(jdbc);
        resoluciones = new ResolucionDeGerenciaRepositoryJdbc(jdbc);
        diligencias = new NotificacionDeResolucionRepositoryJdbc(jdbc);
        internamientos = new InternamientoRepositoryJdbc(jdbc);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        RegistrarAsiento registrarAsiento =
                new RegistrarAsiento(asientos, saldos, auditoria, RELOJ);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

        GeneradorDeCargos cargos = envolver(new GeneradorDeCargosCuentaCorriente(registrarAsiento));
        deudas =
                envolver(
                        new ConsultaDeDeudaCuentaCorriente(
                                envolver(
                                        new ConsultarDeuda(
                                                asientos, saldos, calculo, redondeo, RELOJ))));
        ExtincionDeDeuda extincion =
                envolver(
                        new ExtincionDeDeudaCuentaCorriente(
                                asientos, saldos, registrarAsiento, calculo, redondeo));

        // P5D: el cobro se pregunta, no se lee. `CobrosDeTasasTesoreria` leia `recibo`,
        // `recibo_movimiento` y `recibo_detalle`, que `V7` retiro de esta base. Lo que el AC 3
        // mide sigue siendo lo mismo —que la liberacion dependa de lo que la caja conteste y no
        // de una casilla que marca quien entrega el vehiculo— porque `LiberarVehiculoInternado`
        // nunca leyo esas tablas: ARQ-01 §4 no se lo permitia.
        cobros = new CobrosDeMentira();

        EmitirDocumento documentos =
                envolver(
                        new EmitirDocumento(
                                new DocumentoRepositoryJdbc(
                                        jdbc,
                                        JsonMapper.builder()
                                                .addModule(
                                                        new kamayuk.rentas.web.ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()),
                                new GeneradorDeDocumentos(
                                        List.of(
                                                new RenderizadorPdf(),
                                                new RenderizadorXls(),
                                                new RenderizadorRtf()),
                                        RegimenDeLaInstalacion.REAL),
                                auditoria,
                                RELOJ));

        PlazosDeSancionesParametrizados plazos =
                new PlazosDeSancionesParametrizados(
                        envolver(
                                new kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados(
                                        new kamayuk.rentas.parametros.infraestructura
                                                .ParametrosRepositoryJdbc(jdbc))));

        DirectorioDeContribuyentes padron = new PadronDeLaPrueba();
        // #422: el padron vehicular de verdad, el de `nucleo`, contra esta misma base y su RLS.
        PadronVehicular vehiculos =
                envolver(new PadronVehicularRentas(new VehiculoRepositoryJdbc(jdbc)));

        registrarPapeleta = envolver(new RegistrarPapeleta(papeletas, codigos, cargos, auditoria));
        registrarDescargo =
                envolver(new RegistrarDescargo(papeletas, descargos, plazos, auditoria, RELOJ));
        ValoresSobreUnaObligacion valoresVivos =
                envolver(new ValoresSobreUnaObligacionValores(new ValorRepositoryJdbc(jdbc)));
        anularPapeleta =
                envolver(
                        new AnularPapeleta(
                                papeletas, valoresVivos, extincion, deudas, auditoria, RELOJ));
        anularAl23DeSetiembre =
                envolver(
                        new AnularPapeleta(
                                papeletas,
                                valoresVivos,
                                extincion,
                                deudas,
                                auditoria,
                                Clock.fixed(
                                        Instant.parse("2026-09-23T17:00:00Z"), ZoneOffset.UTC)));
        resolver =
                envolver(
                        new ResolverConResolucionDeGerencia(
                                papeletas,
                                descargos,
                                resoluciones,
                                diligencias,
                                padron,
                                valoresVivos,
                                deudas,
                                extincion,
                                plazos,
                                documentos,
                                auditoria,
                                RELOJ));
        notificar =
                envolver(
                        new NotificarResolucionDeGerencia(
                                resoluciones,
                                diligencias,
                                papeletas,
                                padron,
                                plazos,
                                auditoria,
                                RELOJ));
        internar =
                envolver(
                        new RegistrarInternamiento(
                                internamientos,
                                papeletas,
                                documentos,
                                vehiculos,
                                auditoria,
                                RELOJ));
        liberar =
                envolver(
                        new LiberarVehiculoInternado(
                                internamientos,
                                papeletas,
                                cobros,
                                // #383: el recibo se gasta contra `recibo_aplicado` de verdad, y
                                // el 409 lo da su indice unico, no un doble.
                                new AplicacionDeRecibosJdbc(jdbc, RELOJ),
                                documentos,
                                auditoria,
                                RELOJ));
        abandonar =
                envolver(
                        new DeclararAbandonoDeVehiculo(
                                internamientos, papeletas, documentos, auditoria, RELOJ));
        consultaDeDeposito = envolver(new ConsultaDeInternamientos(internamientos));
        consultaDeActos =
                envolver(
                        new ConsultaDeActosDeLaPapeleta(
                                papeletas, resoluciones, diligencias, internamientos, descargos));

        // #422: HTTP hasta PostgreSQL, con los mismos casos de uso de arriba. Lo que se mide son
        // rechazos que solo la base sabia dar —un indice unico, una clave foranea—, asi que un
        // doble del repositorio los habria dejado pasar con 201.
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new DescargosController(registrarDescargo),
                                new InternamientosController(
                                        consultaDeDeposito, internar, liberar, abandonar, RELOJ),
                                new ConstanciasLibresController(
                                        envolver(
                                                new EmitirConstanciaLibre(
                                                        new PadronDePapeletasRepositoryJdbc(jdbc),
                                                        new ConstanciaLibreRepositoryJdbc(jdbc),
                                                        documentos,
                                                        vehiculos,
                                                        padron,
                                                        auditoria,
                                                        RELOJ)),
                                        RELOJ),
                                new CambioDeNumeroController(
                                        envolver(
                                                new CambiarNumeroDePapeleta(papeletas, auditoria))),
                                new NotificacionAdministrativaController(
                                        envolver(
                                                new RegistrarNotificacionAdministrativa(
                                                        new NotificacionAdministrativaRepositoryJdbc(
                                                                jdbc),
                                                        padron,
                                                        auditoria))))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
                                new JacksonJsonHttpMessageConverter(
                                        JsonMapper.builder()
                                                .addModule(
                                                        new kamayuk.rentas.web.ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()),
                                new org.springframework.http.converter
                                        .ByteArrayHttpMessageConverter())
                        .build();
    }

    @AfterAll
    static void cerrarBase() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("inspector.transito", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 1 — el descargo fundado no borra la papeleta: asienta la baja")
    class ElDescargoFundado {

        @Test
        @DisplayName(
                "presentado en plazo, declarado fundado, la papeleta sigue y la deuda queda en"
                        + " cero")
        void elDescargoFundadoDejaLaMultaSinEfecto() {
            Papeleta papeleta = papeletaDeTransito("A01");
            assertThat(deudaDe(papeleta, ORDINARIA)).isEqualTo(MULTA);

            RegistrarDescargo.Registrado registrado =
                    enTransaccion(
                            () ->
                                    registrarDescargo.registrar(
                                            Familia.TRANSITO,
                                            papeleta.numero(),
                                            new RegistrarDescargo.Peticion(
                                                    "EXP-A01",
                                                    INFRACCION.plusDays(2),
                                                    TipoDeRecurso.DESCARGO,
                                                    "El vehiculo estaba en el taller"),
                                            PORQUE),
                            "mesa.partes");

            assertThat(registrado.descargo().presentadoHasta())
                    .as(
                            "los cinco dias habiles salen del conjunto sellado, no de un 5"
                                    + " compilado (regla 5)")
                    .isEqualTo(DESCARGO_HASTA);
            assertThat(registrado.descargo().conjuntoId())
                    .as("y queda dicho de que conjunto salieron (ARQ-09 §3)")
                    .isEqualTo(conjuntoId);
            assertThat(registrado.descargo().enPlazo()).isTrue();
            assertThat(registrado.plazo().toString()).isEqualTo("5 DIAS_HABILES");

            ResolverConResolucionDeGerencia.ResolucionDictada dictada =
                    dictar(
                            papeleta,
                            TipoDeResolucionDeGerencia.ORDINARIA,
                            ORDINARIA,
                            "EXP-A01",
                            SentidoDelFallo.FUNDADO,
                            EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO);

            assertThat(dictada.baja())
                    .as("la baja se asienta, no se edita la papeleta")
                    .isNotNull();
            assertThat(dictada.baja().importe()).isEqualTo(MULTA);
            assertThat(dictada.baja().asientos())
                    .as("un abono por cada parte del desglose con importe")
                    .isEqualTo(1);

            assertThat(
                            enTransaccion(
                                    () -> papeletas.porNumero(Familia.TRANSITO, papeleta.numero())))
                    .as("la papeleta NO se borra (regla 4, RNF-051): sigue ahi con su desglose")
                    .get()
                    .extracting(Papeleta::importeAPagar)
                    .isEqualTo(MULTA);

            assertThat(deudaDe(papeleta, ORDINARIA))
                    .as("y lo que cambia es el libro: a la fecha de la resolucion ya no debe nada")
                    .isEqualTo(Dinero.CERO);

            assertThat(motivoDelUltimoAbono(papeleta))
                    .as("el motivo del asiento es la observacion de quien resolvio (regla 10)")
                    .isEqualTo(PORQUE.texto());

            // Y la otra mitad de la misma fila (#684): el motivo es el RELATO de quien firma
            // y la causal es el SUSTENTO del acto, que aqui lo decide este caso de uso y no
            // quien atiende. Sin esta asercion nada sujeta cual de las seis se declara: una
            // resolucion que deja la multa sin efecto se asentaria como PRESCRIPCION_DECLARADA
            // —o como ERROR_MATERIAL— y la relacion de RF-045 la contaria bajo otra causal,
            // con el importe y el papel correctos.
            assertThat(causalDelUltimoAbono(papeleta))
                    .as("la causal de la baja la declara el acto que la produce, no el operador")
                    .isEqualTo(CausalDeBaja.RESOLUCION_QUE_DEJA_SIN_EFECTO.name());
        }

        @Test
        @DisplayName("un descargo tardio se registra igual, diciendo que llego fuera de plazo")
        void elDescargoTardioSeRegistraFueraDePlazo() {
            Papeleta papeleta = papeletaDeTransito("A02");

            RegistrarDescargo.Registrado registrado =
                    enTransaccion(
                            () ->
                                    registrarDescargo.registrar(
                                            Familia.TRANSITO,
                                            papeleta.numero(),
                                            new RegistrarDescargo.Peticion(
                                                    "EXP-A02",
                                                    DESCARGO_HASTA.plusDays(1),
                                                    TipoDeRecurso.RECONSIDERACION,
                                                    "Presentado tarde a proposito"),
                                            PORQUE),
                            "mesa.partes");

            assertThat(registrado.descargo().enPlazo())
                    .as(
                            "lo que corresponde es declararlo improcedente, y para eso hay que"
                                    + " poder registrarlo")
                    .isFalse();
        }

        @Test
        @DisplayName("la base impide que la fila mienta sobre si llego en plazo")
        void laBaseImpideQueLaFilaMientaSobreElPlazo() throws SQLException {
            Papeleta papeleta = papeletaDeTransito("A03");
            String estado =
                    estadoSqlDelFallo(
                            () ->
                                    ejecutarComoApp(
                                            "INSERT INTO descargo (municipalidad_id, papeleta_id,"
                                                    + " numero_expediente, fecha, tipo_recurso,"
                                                    + " sustento, presentado_hasta, conjunto_id,"
                                                    + " en_plazo, fecha_registro, usuario_registro,"
                                                    + " observacion) VALUES ("
                                                    + municipalidad
                                                    + ", "
                                                    + papeleta.identificador()
                                                    + ", 'EXP-A03-SQL', DATE '2026-03-20',"
                                                    + " 'DESCARGO', 'por sql directo', DATE"
                                                    + " '2026-03-12', "
                                                    + conjuntoId
                                                    + ", true, now(), 'sql', 'por sql')"));
            assertThat(estado)
                    .as(
                            "descargo_plazo_ck: un recurso tardio admitido como si hubiera llegado a"
                                    + " tiempo es lo que esta restriccion existe para impedir")
                    .isEqualTo("23514");
        }
    }

    @Nested
    @DisplayName("AC 2 — no hay sancionadora sin ordinaria notificada y sin plazo vencido")
    class LaSancionadora {

        @Test
        @DisplayName("sin ordinaria dictada, no procede")
        void sinOrdinariaNoProcede() {
            Papeleta papeleta = papeletaDeTransito("B01");

            assertThatThrownBy(
                            () ->
                                    dictar(
                                            papeleta,
                                            TipoDeResolucionDeGerencia.SANCIONADORA,
                                            SANCIONADORA_DESDE,
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(ResolverConResolucionDeGerencia.OrdinariaSinDictar.class)
                    .hasMessageContaining("no tiene resolucion de gerencia ordinaria");
        }

        @Test
        @DisplayName("con la ordinaria dictada pero sin notificar, tampoco")
        void sinNotificarTampoco() {
            Papeleta papeleta = papeletaDeTransito("B02");
            dictar(papeleta, TipoDeResolucionDeGerencia.ORDINARIA, ORDINARIA, null, null, null);

            assertThatThrownBy(
                            () ->
                                    dictar(
                                            papeleta,
                                            TipoDeResolucionDeGerencia.SANCIONADORA,
                                            SANCIONADORA_DESDE,
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(ResolverConResolucionDeGerencia.OrdinariaSinNotificar.class)
                    .hasMessageContaining("no esta notificada");
        }

        @Test
        @DisplayName("una diligencia no hallada no abre el plazo: se reintenta con otra fila")
        void unaDiligenciaNoHalladaNoAbreElPlazo() {
            Papeleta papeleta = papeletaDeTransito("B03");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    null,
                                    null,
                                    null)
                            .resolucion();

            NotificarResolucionDeGerencia.Diligencia fallida =
                    notificarResolucion(
                            ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NO_UBICADO);
            assertThat(fallida.notificacion().intento()).isEqualTo(1);
            assertThat(fallida.notificacion().exigibleDesde()).isNull();
            assertThat(fallida.abreElPlazoDeLaSancionadora()).isFalse();

            assertThatThrownBy(
                            () ->
                                    dictar(
                                            papeleta,
                                            TipoDeResolucionDeGerencia.SANCIONADORA,
                                            SANCIONADORA_DESDE,
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(ResolverConResolucionDeGerencia.OrdinariaSinNotificar.class);

            NotificarResolucionDeGerencia.Diligencia buena =
                    notificarResolucion(
                            ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NOTIFICADO);

            assertThat(buena.notificacion().intento())
                    .as("la anterior se queda donde estaba (notificacion_intento_uq, V28)")
                    .isEqualTo(2);
            assertThat(buena.notificacion().exigibleDesde())
                    .as("los siete dias habiles salen del conjunto sellado (regla 5)")
                    .isEqualTo(SANCIONADORA_DESDE);
            assertThat(buena.notificacion().conjuntoId()).isEqualTo(conjuntoId);
            assertThat(buena.abreElPlazoDeLaSancionadora()).isTrue();

            assertThat(enTransaccion(() -> diligencias.deResolucion(ordinaria.identificador())))
                    .as("las dos diligencias quedan: la que no encontro a nadie tambien")
                    .hasSize(2);
        }

        @Test
        @DisplayName("notificada pero con el plazo corriendo, todavia no")
        void conElPlazoCorriendoTodaviaNo() {
            Papeleta papeleta = papeletaDeTransito("B04");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    null,
                                    null,
                                    null)
                            .resolucion();
            notificarResolucion(ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NOTIFICADO);

            assertThatThrownBy(
                            () ->
                                    dictar(
                                            papeleta,
                                            TipoDeResolucionDeGerencia.SANCIONADORA,
                                            SANCIONADORA_DESDE.minusDays(1),
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(ResolverConResolucionDeGerencia.PlazoDeLaOrdinariaEnCurso.class)
                    .hasMessageContaining(SANCIONADORA_DESDE.toString());
        }

        @Test
        @DisplayName("vencido el plazo, la sancionadora copia su sustento y sale")
        void vencidoElPlazoLaSancionadoraSale() {
            Papeleta papeleta = papeletaDeTransito("B05");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    null,
                                    null,
                                    null)
                            .resolucion();
            NotificarResolucionDeGerencia.Diligencia acuse =
                    notificarResolucion(
                            ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NOTIFICADO);

            ResolucionDeGerencia sancionadora =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.SANCIONADORA,
                                    SANCIONADORA_DESDE,
                                    null,
                                    null,
                                    null)
                            .resolucion();

            assertThat(sancionadora.ordinariaExigibleDesde())
                    .as("copia su sustento, no lo vuelve a resolver (patron de V28 y V34)")
                    .isEqualTo(SANCIONADORA_DESDE);
            assertThat(sancionadora.ordinariaNotificacionId())
                    .isEqualTo(acuse.notificacion().identificador());
            assertThat(sancionadora.numero()).startsWith("RGS-2026-");
        }

        @Test
        @DisplayName("y la base lo impide aunque alguien se salte el caso de uso")
        void laBaseImpideLaSancionadoraPrematura() throws SQLException {
            Papeleta papeleta = papeletaDeTransito("B06");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    null,
                                    null,
                                    null)
                            .resolucion();
            NotificarResolucionDeGerencia.Diligencia acuse =
                    notificarResolucion(
                            ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NOTIFICADO);

            long documento = documentoSuelto("RGS", "SQL-B06");
            String estado =
                    estadoSqlDelFallo(
                            () ->
                                    ejecutarComoApp(
                                            "INSERT INTO resolucion_gerencia (municipalidad_id,"
                                                    + " papeleta_id, tipo, numero, documento_id, fecha,"
                                                    + " ordinaria_notificacion_id,"
                                                    + " ordinaria_exigible_desde, sustento,"
                                                    + " fecha_registro, usuario_registro, observacion)"
                                                    + " VALUES ("
                                                    + municipalidad
                                                    + ", "
                                                    + papeleta.identificador()
                                                    + ", 'SANCIONADORA', 'SQL-B06', "
                                                    + documento
                                                    + ", DATE '"
                                                    + SANCIONADORA_DESDE.minusDays(1)
                                                    + "', "
                                                    + acuse.notificacion().identificador()
                                                    + ", DATE '"
                                                    + SANCIONADORA_DESDE
                                                    + "', 'por sql', now(), 'sql', 'por sql')"));

            assertThat(estado)
                    .as(
                            "resolucion_gerencia_plazo_ck: misma forma y mismo motivo que"
                                    + " acto_rec2_plazo_ck (V34)")
                    .isEqualTo("23514");
        }

        @Test
        @DisplayName("y no hay dos ordinarias de la misma papeleta, ni con diez hilos a la vez")
        void noHayDosOrdinariasNiConDiezHilos() throws Exception {
            Papeleta papeleta = papeletaDeTransito("B07");
            int hilos = 10;
            CountDownLatch salida = new CountDownLatch(1);
            List<Callable<Boolean>> intentos = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                intentos.add(
                        () -> {
                            salida.await(10, TimeUnit.SECONDS);
                            try {
                                dictar(
                                        papeleta,
                                        TipoDeResolucionDeGerencia.ORDINARIA,
                                        ORDINARIA,
                                        null,
                                        null,
                                        null);
                                return true;
                            } catch (ResolucionDeGerenciaRepository.ResolucionDuplicada
                                    | org.springframework.dao.DataAccessException rechazado) {
                                // Las dos formas en que pierde el que llega segundo: la traducida
                                // -resolucion_gerencia_ordinaria_uq- y la que pueda venir del
                                // motor si dos transacciones se pisan. Cualquier otra sube y rompe
                                // la prueba.
                                return false;
                            } finally {
                                TenantContext.limpiar();
                                OrigenContext.limpiar();
                            }
                        });
            }

            ExecutorService piscina = Executors.newFixedThreadPool(hilos);
            try {
                List<Future<Boolean>> futuros = new ArrayList<>();
                for (Callable<Boolean> intento : intentos) {
                    futuros.add(piscina.submit(intento));
                }
                salida.countDown();
                long dictadas = 0;
                for (Future<Boolean> futuro : futuros) {
                    if (Boolean.TRUE.equals(futuro.get(30, TimeUnit.SECONDS))) {
                        dictadas++;
                    }
                }
                assertThat(dictadas)
                        .as(
                                "resolucion_gerencia_ordinaria_uq: dos resoluciones del mismo tipo"
                                        + " sobre la misma multa se contradicen en el expediente")
                        .isEqualTo(1);
            } finally {
                piscina.shutdownNow();
            }

            assertThat(cuantasResoluciones(papeleta, "ORDINARIA")).isEqualTo(1);
        }

        @Test
        @DisplayName("un descargo se resuelve una vez, y la que sobra la rechaza el indice")
        void unDescargoSeResuelveUnaVez() {
            Papeleta papeleta = papeletaDeTransito("B08");
            enTransaccion(
                    () ->
                            registrarDescargo.registrar(
                                    Familia.TRANSITO,
                                    papeleta.numero(),
                                    new RegistrarDescargo.Peticion(
                                            "EXP-B08",
                                            INFRACCION.plusDays(1),
                                            TipoDeRecurso.DESCARGO,
                                            "sustento de la prueba"),
                                    PORQUE),
                    "mesa.partes");

            dictar(
                    papeleta,
                    TipoDeResolucionDeGerencia.ORDINARIA,
                    ORDINARIA,
                    "EXP-B08",
                    SentidoDelFallo.INFUNDADO,
                    EfectoSobreLaMulta.SE_MANTIENE);

            Papeleta otra = papeletaDeTransito("B09");
            assertThatThrownBy(
                            () ->
                                    dictar(
                                            otra,
                                            TipoDeResolucionDeGerencia.ORDINARIA,
                                            ORDINARIA,
                                            "EXP-B08",
                                            SentidoDelFallo.FUNDADO,
                                            EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO))
                    .as("y ademas ese recurso impugna otra papeleta")
                    .isInstanceOf(ResolverConResolucionDeGerencia.DescargoDeOtraPapeleta.class);
        }
    }

    @Nested
    @DisplayName("AC 3 — la liberacion exige la custodia pagada, verificada contra tesoreria")
    class ElDeposito {

        @Test
        @DisplayName("sin recibo que la caja acredite, el vehiculo no sale")
        void sinReciboElVehiculoNoSale() {
            Papeleta papeleta = papeletaDeTransito("C01");
            internarVehiculo(papeleta, "T2G-401");

            assertThatThrownBy(() -> liberarVehiculo("T2G-401", "001-9999999"))
                    .isInstanceOf(LiberarVehiculoInternado.CustodiaSinPagar.class)
                    .hasMessageContaining("no acredita el pago del concepto CUSTODIA");
        }

        @Test
        @DisplayName("con un recibo anulado tampoco: un recibo anulado ya no acredita nada")
        void conUnReciboAnuladoTampoco() {
            Papeleta papeleta = papeletaDeTransito("C02");
            internarVehiculo(papeleta, "T2G-402");
            String recibo = cobrarCustodia(papeleta.obligadoId());
            anular(recibo);

            assertThatThrownBy(() -> liberarVehiculo("T2G-402", recibo))
                    .isInstanceOf(LiberarVehiculoInternado.CustodiaSinPagar.class);
        }

        @Test
        @DisplayName("con un recibo que cobro otro concepto, tampoco")
        void conUnReciboDeOtroConceptoTampoco() {
            Papeleta papeleta = papeletaDeTransito("C03");
            internarVehiculo(papeleta, "T2G-403");
            // El concepto no hace falta darlo de alta en ningun catalogo de esta base: `tasa`
            // se fue con `V7`, y lo que decide es que cobro EL RECIBO, que es lo que la caja
            // contesta.
            String recibo = cobrarTasa(papeleta.obligadoId(), "DUPLICADO", Dinero.de("12.00"));

            assertThatThrownBy(() -> liberarVehiculo("T2G-403", recibo))
                    .as(
                            "acreditar cualquier recibo dejaria salir un vehiculo con el recibo del"
                                    + " derecho de tramite de otra cosa")
                    .isInstanceOf(LiberarVehiculoInternado.CustodiaSinPagar.class);
        }

        @Test
        @DisplayName("con la custodia cancelada sale, con su acta, y el estado se deriva")
        void conLaCustodiaCanceladaSale() {
            Papeleta papeleta = papeletaDeTransito("C04");
            RegistrarInternamiento.Internado internado = internarVehiculo(papeleta, "T2G-404");
            assertThat(internado.internamiento().acta())
                    .as("el numero del acta ES el del documento emitido: no hay dos numeraciones")
                    .isEqualTo(internado.acta().registro().numero())
                    .startsWith("ACTA_INTERNAMIENTO-2026-");

            String recibo = cobrarCustodia(papeleta.obligadoId());
            LiberarVehiculoInternado.Liberado liberado = liberarVehiculo("T2G-404", recibo);

            assertThat(liberado.estado())
                    .as("el estado se DERIVA de los movimientos, no de una columna (V41 §5)")
                    .isEqualTo(EstadoDeInternamiento.LIBERADO);
            assertThat(liberado.custodia().importe()).isEqualTo(CUSTODIA);
            assertThat(liberado.movimiento().reciboCustodia()).isEqualTo(recibo);
            assertThat(new String(liberado.acta().contenido(), StandardCharsets.ISO_8859_1))
                    .as("el acta imprime el recibo con el que la caja acredito la custodia")
                    .contains(recibo);

            Pagina<InternamientoEnConsulta> grilla =
                    enTransaccion(
                            () ->
                                    consultaDeDeposito.listar(
                                            new CriterioDeInternamiento("T2G-404", null, null),
                                            SANCIONADORA_DESDE,
                                            Paginacion.de(0, 20, "fechaIngreso")));
            assertThat(grilla.contenido()).hasSize(1);
            InternamientoEnConsulta fila = grilla.contenido().get(0);
            assertThat(fila.estado()).isEqualTo(EstadoDeInternamiento.LIBERADO);
            assertThat(fila.fechaSalida()).isEqualTo(ORDINARIA);
            assertThat(fila.calculadoA())
                    .as("los dias se cuentan a una fecha, y la fila la dice (regla 9, RNF-075)")
                    .isEqualTo(SANCIONADORA_DESDE);
            assertThat(fila.dias())
                    .as("del 4 de marzo al 1 de abril: la salida corta la cuenta, no la consulta")
                    .isEqualTo(28);
        }

        @Test
        @DisplayName("y la base impide una liberacion sin recibo, sin quien retira ni sus dias")
        void laBaseImpideUnaLiberacionSinCustodia() throws SQLException {
            Papeleta papeleta = papeletaDeTransito("C05");
            RegistrarInternamiento.Internado internado = internarVehiculo(papeleta, "T2G-405");
            long documento = documentoSuelto("ACTA_LIBERACION", "SQL-C05");

            String estado =
                    estadoSqlDelFallo(
                            () ->
                                    ejecutarComoApp(
                                            "INSERT INTO internamiento_movimiento"
                                                    + " (municipalidad_id, internamiento_id, tipo,"
                                                    + " fecha, acta, documento_id, fecha_registro,"
                                                    + " usuario_registro, observacion) VALUES ("
                                                    + municipalidad
                                                    + ", "
                                                    + internado.internamiento().identificador()
                                                    + ", 'LIBERACION', DATE '2026-04-01',"
                                                    + " 'SQL-C05', "
                                                    + documento
                                                    + ", now(), 'sql', 'por sql')"));

            assertThat(estado)
                    .as(
                            "internamiento_liberacion_ck: la mitad de la guarda que un CHECK puede"
                                    + " expresar")
                    .isEqualTo("23514");
        }

        @Test
        @DisplayName("#185 — la grilla dice la clase de CADA vehiculo, y nula cuando no se sabe")
        void laGrillaDiceLaClaseDeCadaVehiculo() {
            // Tres filas y tres situaciones distintas: la ficha con categoria, la ficha SIN
            // categoria —`vehiculo.categoria` admite nulo— y el ingreso que no nombro ninguna
            // ficha —`internamiento.vehiculo_id` tambien—. Con una sola fila, cualquier columna
            // constante pasaria: es el mismo motivo por el que la pantalla no puede escribir la
            // categoria del vehiculo de la direccion en las veinte filas del deposito.
            Papeleta conCategoria = papeletaDeTransito("C07");
            long automovil = crearVehiculo(conCategoria.obligadoId(), "T2G-407", "AUTOMOVIL");
            internarVehiculo(conCategoria, "T2G-407", automovil);

            Papeleta sinCategoria = papeletaDeTransito("C08");
            long sinClase = crearVehiculo(sinCategoria.obligadoId(), "T2G-408", null);
            internarVehiculo(sinCategoria, "T2G-408", sinClase);

            Papeleta sinFicha = papeletaDeTransito("C09");
            internarVehiculo(sinFicha, "T2G-409");

            assertThat(claseEnLaGrillaDe("T2G-407"))
                    .as(
                            "la clase sale del padron por el `vehiculo_id` que el propio ingreso"
                                    + " guarda: es el JOIN que este issue anade (#185)")
                    .isEqualTo("AUTOMOVIL");
            assertThat(claseEnLaGrillaDe("T2G-408"))
                    .as("inscrito y sin categoria declarada: no se sabe, y se dice nulo")
                    .isNull();
            assertThat(claseEnLaGrillaDe("T2G-409"))
                    .as(
                            "sin ficha en el padron tampoco se sabe. Y el vehiculo NO desaparece"
                                    + " de la grilla: el JOIN es LEFT, porque se interna lo que se"
                                    + " interna, este o no inscrito")
                    .isNull();
        }

        /** La clase que la grilla publica para esa placa, o nulo. La fila tiene que existir. */
        private String claseEnLaGrillaDe(String placa) {
            Pagina<InternamientoEnConsulta> grilla =
                    enTransaccion(
                            () ->
                                    consultaDeDeposito.listar(
                                            new CriterioDeInternamiento(placa, null, null),
                                            SANCIONADORA_DESDE,
                                            Paginacion.de(0, 20, "fechaIngreso")));
            assertThat(grilla.contenido())
                    .as("la grilla tiene que traer el internamiento de " + placa)
                    .hasSize(1);
            return grilla.contenido().get(0).clase();
        }

        @Test
        @DisplayName("un vehiculo no entra dos veces sin haber salido")
        void unVehiculoNoEntraDosVecesSinHaberSalido() {
            Papeleta papeleta = papeletaDeTransito("C06");
            internarVehiculo(papeleta, "T2G-406");

            assertThatThrownBy(() -> internarVehiculo(papeleta, "T2G-406"))
                    .isInstanceOf(RegistrarInternamiento.VehiculoYaInternado.class);
        }
    }

    /**
     * #383 — La custodia se cuenta por dias, y un recibo no libera dos vehiculos.
     *
     * <p>El escenario del issue, letra por letra: el vehiculo entra el 4 de marzo y se libera el 15
     * de abril, <b>42 dias</b>. Hasta #383 {@code LiberarVehiculoInternado} pedia a la caja que
     * acreditara el recibo y nunca comparaba {@code TasaCobrada.cantidad} —«en la custodia, los
     * dias»— con los dias que el vehiculo llevaba; y el mismo recibo, que ya habia liberado uno,
     * liberaba el siguiente, porque sigue vigente y sigue siendo del concepto.
     *
     * <p>Por HTTP y contra PostgreSQL: el rechazo tiene que salir con su codigo —422 si el recibo
     * no alcanza, 409 si ya se gasto— y el segundo lo da {@code recibo_aplicado_uq}, que solo
     * existe en la base.
     */
    @Nested
    @DisplayName("#383 — la custodia se cuenta por dias, y un recibo no libera dos vehiculos")
    class LaCustodiaSeCuentaPorDias {

        private static final String INTERNAMIENTOS = "/rentas/api/v1/transito/internamientos";

        /** Del 4 de marzo, dia del ingreso, al 15 de abril. */
        private static final String LIBERACION = "2026-04-15";

        private static final int DIAS = 42;

        @Test
        @DisplayName("42 dias en deposito con un recibo de UN dia: 422, y dice los dos numeros")
        void unReciboDeUnDiaNoPagaCuarentaYDos() throws Exception {
            Papeleta papeleta = papeletaDeTransito("K01");
            internarVehiculo(papeleta, "ABC-111");
            String recibo = cobrarCustodia(papeleta.obligadoId(), 1);

            Rechazo liberacion = liberarPorHttp("ABC-111", recibo);

            assertThat(liberacion.estado())
                    .as(
                            "con 201 sale un vehiculo de 42 dias con la custodia de uno, y el acta"
                                    + " imprime «Dias en deposito 42» junto al importe de un dia: "
                                    + liberacion.cuerpo())
                    .isEqualTo(422);
            assertThat(liberacion.cuerpo())
                    .contains("VALIDACION")
                    .contains(recibo)
                    .contains("1 dia")
                    .contains("42 dias");
            assertThat(liberacion.errores()).isEmpty();
        }

        @Test
        @DisplayName("con un recibo de 42 dias sale; y ese recibo no libera otra placa: 409")
        void elMismoReciboNoLiberaDosVehiculos() throws Exception {
            Papeleta primera = papeletaDeTransito("K02");
            internarVehiculo(primera, "ABC-112");
            Papeleta segunda = papeletaDeTransito("K03");
            internarVehiculo(segunda, "XYZ-222");
            String recibo = cobrarCustodia(primera.obligadoId(), DIAS);

            Rechazo sale = liberarPorHttp("ABC-112", recibo);
            assertThat(sale.estado()).as(sale.cuerpo()).isEqualTo(201);

            Rechazo otra = liberarPorHttp("XYZ-222", recibo);

            assertThat(otra.estado())
                    .as(
                            "con 201 el recibo que en marzo ya libero ABC-112 libera XYZ-222: "
                                    + otra.cuerpo())
                    .isEqualTo(409);
            assertThat(otra.cuerpo()).contains("CONFLICTO").contains(recibo);
            assertThat(otra.errores()).isEmpty();
            assertThat(
                            contar(
                                    "SELECT count(*) FROM internamiento_movimiento"
                                            + " WHERE recibo_custodia = '"
                                            + recibo
                                            + "'"))
                    .as("y la segunda liberacion no deja fila: la transaccion entera se deshace")
                    .isEqualTo(1);
        }

        /**
         * El recibo se gasta ENTERO, no por los dias del vehiculo que libera (#383, revision del PR
         * #521). Las dos pruebas de arriba siembran un recibo de tantos dias como el vehiculo
         * lleva, y con esa muestra uniforme «gastar la cantidad del recibo» y «gastar los dias» son
         * la misma cifra: {@code Math.max(1, dias)} en lugar de {@code custodia.cantidad()}
         * sobreviviria a las dos. Aqui el recibo cobra 50 y el primer vehiculo lleva 42: si solo se
         * gastan los 42, quedan 8 que liberan un segundo vehiculo de 6 dias.
         */
        @Test
        @DisplayName(
                "un recibo de 50 dias libera un vehiculo de 42 y se gasta entero: otro de 6 dias,"
                        + " 409")
        void elReciboSeGastaEnteroAunqueSobrenDias() throws Exception {
            Papeleta primera = papeletaDeTransito("K04");
            internarVehiculo(primera, "ABC-113");
            Papeleta segunda = papeletaDeTransito("K05");
            internarVehiculo(segunda, "XYZ-223");
            String recibo = cobrarCustodia(primera.obligadoId(), RECIBO_QUE_SOBRA);

            Rechazo sale = liberarPorHttp("ABC-113", recibo);
            assertThat(sale.estado()).as(sale.cuerpo()).isEqualTo(201);
            assertThat(sale.cuerpo()).contains("\"dias\":" + DIAS);

            Rechazo otra = liberarPorHttp("XYZ-223", recibo, LIBERACION_A_LOS_SEIS_DIAS);

            assertThat(otra.estado())
                    .as(
                            "con 201 el recibo solo gasto los 42 dias de ABC-113 y los 8 que le"
                                    + " sobraron sacan a XYZ-223: "
                                    + otra.cuerpo())
                    .isEqualTo(409);
            assertThat(otra.cuerpo()).contains("CONFLICTO").contains(recibo);
            assertThat(
                            contar(
                                    "SELECT coalesce(sum(unidades), 0)::bigint FROM"
                                            + " recibo_aplicado WHERE numero_recibo = '"
                                            + recibo
                                            + "'"))
                    .as("la primera liberacion gasta las 50 unidades del recibo, no sus 42 dias")
                    .isEqualTo(RECIBO_QUE_SOBRA);
        }

        /** Un recibo que cobra mas dias de los que el primer vehiculo lleva. */
        private static final int RECIBO_QUE_SOBRA = 50;

        /** Del 4 de marzo al 10: seis dias, que caben en los 8 que el recibo no gastaria. */
        private static final String LIBERACION_A_LOS_SEIS_DIAS = "2026-03-10";

        private Rechazo liberarPorHttp(String placa, String recibo) throws Exception {
            return liberarPorHttp(placa, recibo, LIBERACION);
        }

        private Rechazo liberarPorHttp(String placa, String recibo, String fecha) throws Exception {
            return rechazo(
                    () ->
                            enviar(
                                    post(INTERNAMIENTOS + "/" + placa + "/liberacion"),
                                    "{\"observacion\":\"El titular retira el vehiculo\","
                                            + "\"fechaDeLiberacion\":\""
                                            + fecha
                                            + "\",\"reciboDeCustodia\":\""
                                            + recibo
                                            + "\",\"personaQueRetira\":\"DORIS\","
                                            + "\"documentoDeQuienRetira\":\"DNI 44218937\","
                                            + "\"soatVigenteAcreditado\":true}"));
        }
    }

    /**
     * #454 — El abandono de un vehiculo internado tiene su acto, y la grilla lo encuentra.
     *
     * <p>Hasta #454 {@code ?estado=EN_ABANDONO} contestaba siempre una pagina vacia: nadie escribia
     * la fila. La siembra no es uniforme: uno sin movimientos, uno declarado en abandono y uno en
     * abandono y despues liberado —la liberacion gana—. Con solo internados, el filtro da vacio con
     * el acto y sin el, y no distingue nada.
     */
    @Nested
    @DisplayName("#454 — El abandono se declara, y el filtro de la grilla lo encuentra")
    class ElAbandono {

        private static final LocalDate DECLARADO = LocalDate.of(2026, 3, 20);

        private static final String DEPOSITO = "/rentas/api/v1/transito/internamientos";

        @Test
        @DisplayName(
                "internado, abandonado y abandonado-despues-liberado: cada estado trae el suyo")
        void cadaEstadoTraeElSuyo() throws Exception {
            internarVehiculo(papeletaDeTransito("AB1"), "ABN-101");
            internarVehiculo(papeletaDeTransito("AB2"), "ABN-102");
            Papeleta tercera = papeletaDeTransito("AB3");
            internarVehiculo(tercera, "ABN-103");

            Rechazo declarado = abandonarPorHttp("ABN-102");
            assertThat(declarado.estado()).as(declarado.cuerpo()).isEqualTo(201);
            assertThat(declarado.cuerpo()).contains("\"estado\":\"EN_ABANDONO\"");
            assertThat(abandonarPorHttp("ABN-103").estado()).isEqualTo(201);
            liberarVehiculo("ABN-103", cobrarCustodia(tercera.obligadoId()));

            assertThat(
                            List.of(
                                    placasEn(EstadoDeInternamiento.INTERNADO),
                                    placasEn(EstadoDeInternamiento.EN_ABANDONO),
                                    placasEn(EstadoDeInternamiento.LIBERADO)))
                    .as("la liberacion gana sobre el abandono (EstadoDeInternamiento)")
                    .containsExactly(List.of("ABN-101"), List.of("ABN-102"), List.of("ABN-103"));
        }

        @Test
        @DisplayName("una segunda declaracion del mismo internamiento es 409, y no deja fila")
        void unaSegundaDeclaracionEs409() throws Exception {
            internarVehiculo(papeletaDeTransito("AB4"), "ABN-104");
            assertThat(abandonarPorHttp("ABN-104").estado()).isEqualTo(201);

            Rechazo otra = abandonarPorHttp("ABN-104");

            assertThat(otra.estado()).as(otra.cuerpo()).isEqualTo(409);
            assertThat(
                            contar(
                                    "SELECT count(*) FROM internamiento_movimiento m"
                                            + " JOIN internamiento i ON i.id = m.internamiento_id"
                                            + " WHERE i.placa = 'ABN-104' AND m.tipo = 'ABANDONO'"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("y por SQL directo la base tampoco admite un segundo abandono")
        void laBaseTampocoAdmiteUnSegundoAbandono() throws Exception {
            internarVehiculo(papeletaDeTransito("AB5"), "ABN-105");
            assertThat(abandonarPorHttp("ABN-105").estado()).isEqualTo(201);

            // La comprobacion del caso de uso no cubre la carrera ni el SQL directo: lo hace
            // `internamiento_abandono_uq` (V40), como `internamiento_liberacion_uq`.
            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    jdbc.sql(
                                                                    "INSERT INTO"
                                                                            + " internamiento_movimiento"
                                                                            + " (municipalidad_id,"
                                                                            + " internamiento_id, tipo,"
                                                                            + " fecha, acta,"
                                                                            + " documento_id,"
                                                                            + " dias_custodia,"
                                                                            + " soat_acreditado,"
                                                                            + " fecha_registro,"
                                                                            + " usuario_registro,"
                                                                            + " observacion)"
                                                                            + " SELECT m.municipalidad_id,"
                                                                            + " m.internamiento_id,"
                                                                            + " m.tipo, m.fecha,"
                                                                            + " m.acta || '-B',"
                                                                            + " i.documento_id,"
                                                                            + " m.dias_custodia,"
                                                                            + " m.soat_acreditado,"
                                                                            + " m.fecha_registro,"
                                                                            + " m.usuario_registro,"
                                                                            + " m.observacion"
                                                                            + " FROM internamiento_movimiento m"
                                                                            + " JOIN internamiento i"
                                                                            + " ON i.id = m.internamiento_id"
                                                                            + " WHERE i.placa = 'ABN-105'"
                                                                            + " AND m.tipo = 'ABANDONO'")
                                                            .update()))
                    .hasStackTraceContaining("internamiento_abandono_uq");
        }

        private List<String> placasEn(EstadoDeInternamiento estado) {
            return enTransaccion(
                            () ->
                                    consultaDeDeposito.listar(
                                            new CriterioDeInternamiento(null, null, estado),
                                            LocalDate.of(2026, 4, 15),
                                            Paginacion.de(0, 200, "fechaIngreso")))
                    .contenido()
                    .stream()
                    .map(InternamientoEnConsulta::placa)
                    .filter(List.of("ABN-101", "ABN-102", "ABN-103")::contains)
                    .sorted()
                    .toList();
        }

        private Rechazo abandonarPorHttp(String placa) throws Exception {
            return rechazo(
                    () ->
                            enviar(
                                    post(DEPOSITO + "/" + placa + "/abandono"),
                                    "{\"observacion\":\"Nadie lo reclama desde marzo\","
                                            + "\"fechaDeAbandono\":\""
                                            + DECLARADO
                                            + "\"}"));
        }
    }

    @Nested
    @DisplayName("#273 — el internamiento nocturno: lo que la zona del producto arregla")
    class ElInternamientoNocturno {

        /**
         * Las 20:00 del 4 de marzo. En UTC ese instante ya es del 5, y ESA es la trampa.
         *
         * <p>Esta escrito aqui y no recalculado: el dia local del ingreso es lo que la prueba
         * verifica, asi que derivarlo con la misma conversion que ejercita no verificaria nada.
         */
        private static final LocalDate DIA_DEL_INGRESO = LocalDate.of(2026, 3, 4);

        @Test
        @DisplayName("AC 1a — su custodia cuenta el dia entero, no uno de menos")
        void laCustodiaCuentaElDiaQueLeCorresponde() {
            Papeleta papeleta = papeletaDeTransito("Z01");
            internarVehiculo(papeleta, "T2G-701", null, INGRESO_NOCTURNO);

            Pagina<InternamientoEnConsulta> grilla =
                    enTransaccion(
                            () ->
                                    consultaDeDeposito.listar(
                                            new CriterioDeInternamiento("T2G-701", null, null),
                                            SANCIONADORA_DESDE,
                                            Paginacion.de(0, 20, "fechaIngreso")));

            assertThat(grilla.contenido()).hasSize(1);
            InternamientoEnConsulta fila = grilla.contenido().get(0);
            // Los dias PRIMERO, y a proposito: es la cifra que devenga la custodia, asi que es la
            // que tiene que aparecer en el rojo cuando alguien vuelva a truncar el ingreso en UTC.
            assertThat(fila.dias())
                    .as(
                            "del 4 de marzo al 15 de abril son 42 dias. Truncando el ingreso en"
                                    + " UTC salian 41: un dia de custodia que el deposito dejaba"
                                    + " de devengar en las cinco horas de cada dia entre las 19:00"
                                    + " y la medianoche")
                    .isEqualTo(42);
            assertThat(fila.fechaIngreso())
                    .as("entro el 4 a las 20:00 hora de Catacaos; en UTC ese instante es del 5")
                    .isEqualTo(DIA_DEL_INGRESO);
        }

        @Test
        @DisplayName("AC 1b — y se puede liberar esa misma noche, que es cuando el titular paga")
        void seLiberaEsaMismaNoche() {
            Papeleta papeleta = papeletaDeTransito("Z02");
            internarVehiculo(papeleta, "T2G-702", null, INGRESO_NOCTURNO);
            String recibo = cobrarCustodia(papeleta.obligadoId());

            LiberarVehiculoInternado.Liberado liberado =
                    liberarVehiculo("T2G-702", recibo, DIA_DEL_INGRESO);

            assertThat(liberado.estado())
                    .as(
                            "con el ingreso truncado en UTC, `ingreso` salia el 5 y la guarda"
                                    + " rechazaba una liberacion del 4 por «anterior al ingreso»:"
                                    + " el vehiculo se quedaba en el deposito hasta el dia"
                                    + " siguiente por un desfase de zona")
                    .isEqualTo(EstadoDeInternamiento.LIBERADO);
            assertThat(liberado.movimiento().diasCustodia())
                    .as("entro y salio el mismo dia: cero dias, no un dia negativo ni uno de mas")
                    .isZero();
        }

        @Test
        @DisplayName("y la guarda NO se retiro: una liberacion de VERDAD anterior sigue rota")
        void laGuardaSigueMordiendo() {
            Papeleta papeleta = papeletaDeTransito("Z03");
            internarVehiculo(papeleta, "T2G-703", null, INGRESO_NOCTURNO);
            String recibo = cobrarCustodia(papeleta.obligadoId());

            // Sin esta prueba, quitar el `isBefore` entero dejaria las dos de arriba en verde. Lo
            // que #273 arreglo es CON QUE dia se compara, no que se comparara.
            assertThatThrownBy(
                            () -> liberarVehiculo("T2G-703", recibo, DIA_DEL_INGRESO.minusDays(1)))
                    .isInstanceOf(LiberarVehiculoInternado.LiberacionAnteriorAlIngreso.class)
                    .hasMessageContaining("no se pudo liberar el 2026-03-03");
        }

        @Test
        @DisplayName(
                "el acta imprime el 4, y el expediente ensena el 4: los otros dos truncamientos")
        void elActaYElExpedienteDicenElMismoDiaLocal() {
            Papeleta papeleta = papeletaDeTransito("Z04");
            RegistrarInternamiento.Internado internado =
                    internarVehiculo(papeleta, "T2G-704", null, INGRESO_NOCTURNO);

            // `RegistrarInternamiento:96`: el dia que el acta imprime. El renderizador escribe
            // «Datos al <dia>» y el PDF es texto plano, que es como la prueba de la custodia
            // pagada comprueba el recibo unas lineas mas arriba.
            assertThat(new String(internado.acta().contenido(), StandardCharsets.ISO_8859_1))
                    .as("es la fecha que consta en un documento, no una casilla de pantalla")
                    .contains("Datos al " + DIA_DEL_INGRESO)
                    .doesNotContain("Datos al " + DIA_DEL_INGRESO.plusDays(1));

            // `ConsultaDeActosDeLaPapeleta:96`: el dia que la consulta ensena.
            ConsultaDeActosDeLaPapeleta.Expediente expediente =
                    enTransaccion(() -> consultaDeActos.de(Familia.TRANSITO, papeleta.numero()));

            ActoDeLaPapeleta acta =
                    expediente.actos().stream()
                            .filter(acto -> "INGRESO".equals(acto.tipo()))
                            .findFirst()
                            .orElseThrow();
            assertThat(acta.fecha())
                    .as("el expediente ensena el mismo dia que el acta imprimio: el 4, no el 5")
                    .isEqualTo(DIA_DEL_INGRESO);
        }

        /**
         * La «Fecha de ingreso» que las dos actas imprimen es la hora del Peru (#327).
         *
         * <p>La prueba de arriba mira «Datos al», que #273 arreglo; la «Fecha de ingreso» del mismo
         * documento se imprimia con {@code Instant.toString()}, o sea en UTC: un vehiculo que entra
         * a las 20:00 del 4 salia en el acta que se entrega al conductor con {@code
         * 2026-03-05T01:00:00Z}, junto a un «Datos al 2026-03-04». Y el acta de liberacion la
         * repite, leida de la base: son dos sitios, y cada uno se mira aparte.
         */
        @Test
        @DisplayName("#327 — y las dos actas imprimen la hora de ingreso del Peru, no la de UTC")
        void laFechaDeIngresoDeLasActasEsLaDelPeru() {
            Papeleta papeleta = papeletaDeTransito("Z05");
            RegistrarInternamiento.Internado internado =
                    internarVehiculo(papeleta, "T2G-705", null, INGRESO_NOCTURNO);
            String recibo = cobrarCustodia(papeleta.obligadoId());
            LiberarVehiculoInternado.Liberado liberado =
                    liberarVehiculo("T2G-705", recibo, DIA_DEL_INGRESO);

            assertThat(new String(internado.acta().contenido(), StandardCharsets.ISO_8859_1))
                    .as(
                            "el acta de ingreso: las 20:00 del 4 con su desfase, no la 01:00 del 5"
                                    + " en UTC")
                    .contains("2026-03-04T20:00:00-05:00")
                    .doesNotContain("2026-03-05T01:00");
            assertThat(new String(liberado.acta().contenido(), StandardCharsets.ISO_8859_1))
                    .as("y el acta de liberacion, que la lee de la base: la misma hora")
                    .contains("2026-03-04T20:00:00-05:00")
                    .doesNotContain("2026-03-05T01:00");
        }
    }

    @Nested
    @DisplayName("AC 4 y 5 — todos los documentos con su fecha y su acuse, y su auditoria")
    class ElExpedienteDeLaPapeleta {

        @Test
        @DisplayName("resolucion, acta de ingreso y acta de salida en una sola secuencia")
        void todosLosDocumentosEnUnaSolaSecuencia() {
            Papeleta papeleta = papeletaDeTransito("D01");
            enTransaccion(
                    () ->
                            registrarDescargo.registrar(
                                    Familia.TRANSITO,
                                    papeleta.numero(),
                                    new RegistrarDescargo.Peticion(
                                            "EXP-D01",
                                            INFRACCION.plusDays(1),
                                            TipoDeRecurso.DESCARGO,
                                            "sustento de la prueba"),
                                    PORQUE),
                    "mesa.partes");
            internarVehiculo(papeleta, "T2G-501");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    "EXP-D01",
                                    SentidoDelFallo.INFUNDADO,
                                    EfectoSobreLaMulta.SE_MANTIENE)
                            .resolucion();
            notificarResolucion(ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NO_UBICADO);
            notificarResolucion(ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NOTIFICADO);
            String recibo = cobrarCustodia(papeleta.obligadoId());
            liberarVehiculo("T2G-501", recibo);

            ConsultaDeActosDeLaPapeleta.Expediente expediente =
                    enTransaccion(() -> consultaDeActos.de(Familia.TRANSITO, papeleta.numero()));

            assertThat(expediente.descargos())
                    .extracting(Descargo::numeroExpediente)
                    .containsExactly("EXP-D01");
            assertThat(expediente.actos())
                    .as("la resolucion, el acta de ingreso y la de salida: los tres papeles")
                    .hasSize(3)
                    .extracting(ActoDeLaPapeleta::tipo)
                    .containsExactlyInAnyOrder("ORDINARIA", "INGRESO", "LIBERACION");
            assertThat(expediente.actos())
                    .allSatisfy(acto -> assertThat(acto.fecha()).isNotNull())
                    .allSatisfy(acto -> assertThat(acto.documentoId()).isPositive());

            ActoDeLaPapeleta resolucion =
                    expediente.actos().stream()
                            .filter(acto -> "ORDINARIA".equals(acto.tipo()))
                            .findFirst()
                            .orElseThrow();
            assertThat(resolucion.acuses())
                    .as("los DOS intentos, no solo el que encontro a alguien")
                    .hasSize(2)
                    .extracting(AcuseDelActo::resultado)
                    .containsExactly(
                            ResultadoDeNotificacion.NO_UBICADO, ResultadoDeNotificacion.NOTIFICADO);
            assertThat(resolucion.acuses().get(1).exigibleDesde()).isEqualTo(SANCIONADORA_DESDE);
        }

        @Test
        @DisplayName("#185 — la resolucion que nadie pudo notificar NO se lee como conforme")
        void laResolucionNoNotificadaNoSeLeeComoConforme() {
            Papeleta papeleta = papeletaDeTransito("D04");
            enTransaccion(
                    () ->
                            registrarDescargo.registrar(
                                    Familia.TRANSITO,
                                    papeleta.numero(),
                                    new RegistrarDescargo.Peticion(
                                            "EXP-D04",
                                            INFRACCION.plusDays(1),
                                            TipoDeRecurso.DESCARGO,
                                            "sustento de la prueba"),
                                    PORQUE),
                    "mesa.partes");
            internarVehiculo(papeleta, "T2G-504");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    "EXP-D04",
                                    SentidoDelFallo.INFUNDADO,
                                    EfectoSobreLaMulta.SE_MANTIENE)
                            .resolucion();
            // DOS intentos, y ninguno encontro a nadie. Con uno solo, «la ultima» y «alguna»
            // coincidirian y la prueba no distinguiria una cosa de la otra.
            notificarResolucion(ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NO_UBICADO);
            notificarResolucion(ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NO_UBICADO);

            ConsultaDeActosDeLaPapeleta.Expediente expediente =
                    enTransaccion(() -> consultaDeActos.de(Familia.TRANSITO, papeleta.numero()));

            ActoDeLaPapeleta resolucion =
                    expediente.actos().stream()
                            .filter(acto -> "ORDINARIA".equals(acto.tipo()))
                            .findFirst()
                            .orElseThrow();
            assertThat(resolucion.acuses())
                    .as("los dos intentos siguen ahi: el estado no los resume, se anade a ellos")
                    .hasSize(2);
            assertThat(resolucion.estado())
                    .as(
                            "un acto que nunca se notifico no abre plazo: escribir «Conforme»"
                                    + " sobre el afirmaria que la papeleta todavia se puede cobrar")
                    .isEqualTo(EstadoDelActoDeLaPapeleta.NO_NOTIFICADO);
            assertThat(resolucion.estado().surtioEfecto()).isFalse();

            ActoDeLaPapeleta acta =
                    expediente.actos().stream()
                            .filter(acto -> "INGRESO".equals(acto.tipo()))
                            .findFirst()
                            .orElseThrow();
            assertThat(acta.estado())
                    .as(
                            "el acta del deposito se entrega en mano con su firma en el papel:"
                                    + " decir que esta sin diligenciar seria decir que alguien"
                                    + " tiene pendiente notificarla")
                    .isEqualTo(EstadoDelActoDeLaPapeleta.SIN_NOTIFICACION);
        }

        @Test
        @DisplayName("cada acto deja su fila de auditoria, con la observacion de quien lo hizo")
        void cadaActoDejaAuditoria() {
            Papeleta papeleta = papeletaDeTransito("D02");
            long antes = cuantasFilasDeAuditoria();

            enTransaccion(
                    () ->
                            registrarDescargo.registrar(
                                    Familia.TRANSITO,
                                    papeleta.numero(),
                                    new RegistrarDescargo.Peticion(
                                            "EXP-D02",
                                            INFRACCION.plusDays(1),
                                            TipoDeRecurso.DESCARGO,
                                            "sustento de la prueba"),
                                    PORQUE),
                    "mesa.partes");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    "EXP-D02",
                                    SentidoDelFallo.INFUNDADO,
                                    EfectoSobreLaMulta.SE_MANTIENE)
                            .resolucion();
            notificarResolucion(ordinaria.numero(), DILIGENCIA, ResultadoDeNotificacion.NOTIFICADO);

            assertThat(cuantasFilasDeAuditoria() - antes)
                    .as("el descargo, el documento emitido, la resolucion y la diligencia")
                    .isGreaterThanOrEqualTo(4);
            assertThat(observacionesDeAuditoria())
                    .as("y todas con la observacion de quien lo hizo (regla 10, RNF-052)")
                    .contains(PORQUE.texto());
        }
    }

    @Nested
    @DisplayName("Privilegios y aislamiento")
    class PrivilegiosYAislamiento {

        @Test
        @DisplayName("kamayuk_app no puede editar ni borrar una resolucion de gerencia")
        void laResolucionNoSeEdita() {
            Papeleta papeleta = papeletaDeTransito("E01");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    null,
                                    null,
                                    null)
                            .resolucion();

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE resolucion_gerencia SET sustento ="
                                                            + " 'corregido' WHERE id = "
                                                            + ordinaria.identificador())))
                    .as(
                            "V41 no le concede UPDATE: la resolucion se notifica y el administrado se"
                                    + " lleva el papel")
                    .isEqualTo("42501");
            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "DELETE FROM resolucion_gerencia WHERE id = "
                                                            + ordinaria.identificador())))
                    .isEqualTo("42501");
        }

        @Test
        @DisplayName("kamayuk_app no puede rellenar la salida encima del ingreso")
        void elInternamientoNoSeEdita() {
            Papeleta papeleta = papeletaDeTransito("E02");
            RegistrarInternamiento.Internado internado = internarVehiculo(papeleta, "T2G-601");

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE internamiento SET deposito = 'otro'"
                                                            + " WHERE id = "
                                                            + internado
                                                                    .internamiento()
                                                                    .identificador())))
                    .as("V41 le retira el UPDATE: la salida es un acto con su acta")
                    .isEqualTo("42501");
        }

        @Test
        @DisplayName("desde otra municipalidad, la resolucion no existe")
        void desdeOtraMunicipalidadNoExiste() {
            Papeleta papeleta = papeletaDeTransito("E03");
            ResolucionDeGerencia ordinaria =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    null,
                                    null,
                                    null)
                            .resolucion();

            Optional<ResolucionDeGerencia> desdeB =
                    enTransaccionDe(
                            otraMunicipalidad,
                            () -> resoluciones.porNumero(ordinaria.numero()),
                            "gerente");

            assertThat(desdeB).as("RLS: no es que este vacia, es que no existe").isEmpty();
        }

        /**
         * Hasta #267 esta prueba fabricaba el estado con un {@code UPDATE papeleta SET estado =
         * 'ANULADA'} crudo, desde la propia prueba, porque <b>ningun camino de produccion podia
         * llevar una papeleta a ese estado</b>. Ahora lo hace el acto, que es lo que convierte la
         * guarda en real: si {@code AnularPapeleta} dejara de escribir la columna, esto se pondria
         * rojo en vez de seguir verde sobre un estado inventado.
         */
        @Test
        @DisplayName("y una resolucion sobre una papeleta anulada no se dicta")
        void noSeResuelveSobreUnaPapeletaAnulada() {
            Papeleta papeleta = papeletaDeTransito("E04");
            anular(papeleta);

            assertThatThrownBy(
                            () ->
                                    dictar(
                                            papeleta,
                                            TipoDeResolucionDeGerencia.ORDINARIA,
                                            ORDINARIA,
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(RegistrarDescargo.PapeletaSinNadaQueImpugnar.class);
        }
    }

    // ==================================================================
    //  #267 — anular la papeleta: la unica transicion que este sistema escribe
    // ==================================================================

    /**
     * Lo que esta seccion defiende, y ninguna prueba con dobles podia:
     *
     * <ul>
     *   <li>que el acto exista de verdad contra el motor, con el privilegio que {@code V20} dejo
     *       puesto —{@code GRANT UPDATE (numero, estado)}— y ni una columna mas;
     *   <li>que la guarda {@code PapeletaSinNadaQueImpugnar} <b>pueda morder</b>. Hasta #267 no
     *       podia: los dos valores que mira eran inalcanzables, y las pruebas que la cubrian se
     *       escribian a si mismas el estado que verificaban con un {@code UPDATE} crudo;
     *   <li>y que anular no deje a la papeleta <b>anulada y debiendo</b>, que es el defecto que
     *       {@code ObligacionDeLaPapeleta} nombra.
     * </ul>
     */
    @Nested
    @DisplayName("#267 — la papeleta se anula, y entonces las dos guardas descartan de verdad")
    class LaAnulacionDeLaPapeleta {

        @Test
        @DisplayName("anular mueve el estado, no borra nada y da de baja lo que la papeleta cargo")
        void anularDaDeBajaLoQueLaPapeletaCargo() {
            Papeleta papeleta = papeletaDeTransito("F01");
            assertThat(deudaDe(papeleta, ORDINARIA)).isEqualTo(MULTA);

            AnularPapeleta.Anulada anulada = anular(papeleta);

            assertThat(anulada.papeleta().estado()).isEqualTo(EstadoDePapeleta.ANULADA);
            assertThat(anulada.baja().importe()).isEqualTo(MULTA);
            assertThat(anulada.baja().asientos())
                    .as("un abono por cada parte del desglose con importe")
                    .isEqualTo(1);

            assertThat(
                            enTransaccion(
                                    () -> papeletas.porNumero(Familia.TRANSITO, papeleta.numero())))
                    .as("la fila sigue entera (regla 4, RNF-051): lo unico que cambia es el estado")
                    .get()
                    .satisfies(
                            releida -> {
                                assertThat(releida.estado()).isEqualTo(EstadoDePapeleta.ANULADA);
                                assertThat(releida.importeAPagar()).isEqualTo(MULTA);
                                assertThat(releida.placa()).isEqualTo(papeleta.placa());
                                assertThat(releida.lugar()).isEqualTo(papeleta.lugar());
                                assertThat(releida.fechaInfraccion())
                                        .isEqualTo(papeleta.fechaInfraccion());
                            });

            assertThat(deudaDe(papeleta, ORDINARIA))
                    .as("y sin esto quedaria ANULADA —o sea «no se debe»— y debiendo en el libro")
                    .isEqualTo(Dinero.CERO);

            assertThat(causalDelUltimoAbono(papeleta))
                    .as("«la baja que deshace un alta que no debio existir», y no la de #684")
                    .isEqualTo(CausalDeBaja.ERROR_MATERIAL.name());
            assertThat(motivoDelUltimoAbono(papeleta))
                    .as("el motivo del asiento es la observacion de quien anula (regla 10)")
                    .isEqualTo(PORQUE.texto());
        }

        /**
         * <b>La mitad mas valiosa de #267.</b> Anular de verdad y despues intentar descargar: no
         * hay ningun doble que fabrique el estado, y si el acto no escribiera la columna esta
         * prueba fallaria por no lanzar nada.
         */
        @Test
        @DisplayName("contra una papeleta anulada no se registra descargo: la guarda YA muerde")
        void laGuardaDelDescargoYaPuedeMorder() {
            Papeleta papeleta = papeletaDeTransito("F02");

            assertThat(
                            enTransaccion(
                                    () ->
                                            registrarDescargo.registrar(
                                                    Familia.TRANSITO,
                                                    papeleta.numero(),
                                                    new RegistrarDescargo.Peticion(
                                                            "EXP-F02",
                                                            INFRACCION.plusDays(2),
                                                            TipoDeRecurso.DESCARGO,
                                                            "Antes de anular si se admite"),
                                                    PORQUE),
                                    "mesa.partes"))
                    .as("viva, el recurso entra: si no, esta prueba no distinguiria nada")
                    .isNotNull();

            anular(papeleta);

            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    registrarDescargo.registrar(
                                                            Familia.TRANSITO,
                                                            papeleta.numero(),
                                                            new RegistrarDescargo.Peticion(
                                                                    "EXP-F02-B",
                                                                    INFRACCION.plusDays(3),
                                                                    TipoDeRecurso.RECONSIDERACION,
                                                                    "Contra una multa que ya no"
                                                                            + " existe"),
                                                            PORQUE),
                                            "mesa.partes"))
                    .isInstanceOf(RegistrarDescargo.PapeletaSinNadaQueImpugnar.class)
                    .hasMessageContaining("ANULADA");
        }

        @Test
        @DisplayName("una papeleta ya anulada no se vuelve a anular")
        void unaPapeletaYaAnuladaNoSeVuelveAAnular() {
            Papeleta papeleta = papeletaDeTransito("F03");
            anular(papeleta);

            assertThatThrownBy(() -> anular(papeleta))
                    .isInstanceOf(Papeleta.TransicionIlegal.class)
                    .hasMessageContaining("ANULADA");
        }

        @Test
        @DisplayName("el acto queda en auditoria con su antes y su despues")
        void elActoQuedaEnAuditoria() {
            Papeleta papeleta = papeletaDeTransito("F04");
            anular(papeleta);

            assertThat(
                            enTransaccion(
                                    () ->
                                            jdbc.sql(
                                                            "SELECT datos_anteriores::text || ' ->"
                                                                    + " ' || datos_nuevos::text FROM"
                                                                    + " auditoria WHERE tabla ="
                                                                    + " 'papeleta' AND clave = :clave"
                                                                    + " AND operacion = 'MODIFICACION'"
                                                                    + " ORDER BY id DESC LIMIT 1")
                                                    .param(
                                                            "clave",
                                                            String.valueOf(
                                                                    papeleta.identificador()))
                                                    .query(String.class)
                                                    .single()))
                    .as("el antes y el despues del acto, que es lo que la regla 10 exige guardar")
                    .isEqualTo("{\"estado\": \"IMPUESTA\"} -> {\"estado\": \"ANULADA\"}");
        }
    }

    // ==================================================================
    //  #402 — la anulacion no se fecha antes de la infraccion ni despues de hoy
    // ==================================================================

    /**
     * <b>La siembra que distingue (#402).</b> Hasta aqui toda anulacion de esta clase se fechaba en
     * {@link #ORDINARIA}, que esta entre la infraccion y el reloj por construccion: con esa
     * muestra, una anulacion que no comparara la fecha con nada pasaba igual. Aqui la infraccion es
     * del 4 de marzo, hoy es el 23 de setiembre, y se anula el 1 de marzo —un 1 tecleado en vez del
     * 4—, el mismo 4 de marzo —la frontera, que vale— y el 24 de setiembre.
     *
     * <p>Lo que se afirma de los rechazos no es solo la excepcion: es que <b>el libro sigue
     * debiendo lo mismo y la papeleta sigue sin anular</b>. Hasta #402 la anulacion al 1 de marzo
     * confirmaba con cero asientos —la baja releia la deuda a esa fecha, antes del cargo— y dejaba
     * una papeleta {@code ANULADA} que el libro seguia cobrando, sin vuelta atras: otra anulacion
     * choca con {@code TransicionIlegal}.
     */
    @Nested
    @DisplayName("#402 — la anulacion no se fecha antes de la infraccion ni despues de hoy")
    class LaFechaDeLaAnulacion {

        /** Despues de la baja fechada en el futuro, que es donde se veria su efecto. */
        private static final LocalDate FIN_DE_ANIO = LocalDate.of(2026, 12, 31);

        @Test
        @DisplayName("anular el 2026-03-01 una infraccion del 2026-03-04: rechazo, libro intacto")
        void anteriorALaInfraccion() {
            Papeleta papeleta = papeletaDeTransito("402A");
            assertThat(papeleta.fechaInfraccion()).isEqualTo(INFRACCION);

            Throwable rechazo = catchThrowable(() -> anularEl(papeleta, LocalDate.of(2026, 3, 1)));

            assertThat(rechazo)
                    .as("la fecha es anterior a la infraccion que la anulacion resuelve")
                    .isInstanceOf(ActoFueraDeOrden.class)
                    .hasMessageContaining("2026-03-04");
            assertThat(estadoDe(papeleta))
                    .as("la papeleta no queda ANULADA: si quedara, ya no se podria corregir")
                    .isEqualTo(EstadoDePapeleta.IMPUESTA);
            assertThat(deudaDe(papeleta, HOY_DEL_402))
                    .as("y el libro sigue debiendo lo mismo")
                    .isEqualTo(MULTA);
            assertThat(cuantosAbonos(papeleta)).isZero();
        }

        @Test
        @DisplayName("anular el mismo dia de la infraccion vale, y da de baja lo cargado")
        void elMismoDiaDeLaInfraccion() {
            Papeleta papeleta = papeletaDeTransito("402B");

            AnularPapeleta.Anulada anulada = anularEl(papeleta, INFRACCION);

            assertThat(anulada.papeleta().estado()).isEqualTo(EstadoDePapeleta.ANULADA);
            assertThat(anulada.baja().asientos())
                    .as("la frontera: el cargo nace con fecha valor = la de la infraccion")
                    .isEqualTo(1);
            assertThat(anulada.baja().importe()).isEqualTo(MULTA);
            assertThat(deudaDe(papeleta, HOY_DEL_402)).isEqualTo(Dinero.CERO);
        }

        @Test
        @DisplayName("anular el 2026-09-24 con el reloj en el 2026-09-23: rechazo, libro intacto")
        void posteriorAHoy() {
            Papeleta papeleta = papeletaDeTransito("402C");

            Throwable rechazo = catchThrowable(() -> anularEl(papeleta, HOY_DEL_402.plusDays(1)));

            assertThat(rechazo)
                    .as("una baja con fecha valor futura deja la deuda a la vista hasta ese dia")
                    .isInstanceOf(ActoFueraDeOrden.class)
                    .hasMessageContaining("posterior a hoy");
            assertThat(estadoDe(papeleta)).isEqualTo(EstadoDePapeleta.IMPUESTA);
            assertThat(deudaDe(papeleta, FIN_DE_ANIO))
                    .as("ni siquiera a fin de anio hay una baja que la extinga")
                    .isEqualTo(MULTA);
            assertThat(cuantosAbonos(papeleta)).isZero();
        }
    }

    /**
     * #402 — El descargo resuelve sobre la infraccion, y la resolucion sobre el descargo.
     *
     * <p>Las dos siembras son las del issue: un descargo del 20 de febrero contra una infraccion
     * del 4 de marzo —hasta #402 entraba con {@code enPlazo = true}, porque la fila solo exige que
     * {@code enPlazo} cuadre con {@code presentadoHasta}— y una resolucion del 5 de marzo que deja
     * sin efecto un recurso presentado el 10. El reloj de esta clase esta en el 20 de abril, y
     * «despues de hoy» es el 21.
     */
    @Nested
    @DisplayName("#402 — el descargo y la resolucion se fechan en orden")
    class LaFechaDelDescargoYDeLaResolucion {

        @Test
        @DisplayName("un descargo anterior a la infraccion no se registra")
        void descargoAnteriorALaInfraccion() {
            Papeleta papeleta = papeletaDeTransito("402D");

            Throwable rechazo =
                    catchThrowable(
                            () -> descargar(papeleta, "EXP-402D", LocalDate.of(2026, 2, 20)));

            assertThat(rechazo)
                    .isInstanceOf(ActoFueraDeOrden.class)
                    .hasMessageContaining("2026-03-04");
            assertThat(cuantosDescargos(papeleta)).isZero();
        }

        @Test
        @DisplayName("un descargo fechado despues de hoy no se registra; el de hoy si")
        void descargoPosteriorAHoy() {
            Papeleta papeleta = papeletaDeTransito("402E");

            Throwable rechazo =
                    catchThrowable(
                            () -> descargar(papeleta, "EXP-402E", LocalDate.of(2026, 4, 21)));

            assertThat(rechazo)
                    .isInstanceOf(ActoFueraDeOrden.class)
                    .hasMessageContaining("posterior a hoy");
            assertThat(cuantosDescargos(papeleta)).isZero();
            assertThat(descargar(papeleta, "EXP-402E-B", LocalDate.of(2026, 4, 20)))
                    .as("la frontera: el mismo dia del reloj vale")
                    .isNotNull();
        }

        @Test
        @DisplayName("una resolucion anterior al descargo que resuelve no se dicta, ni da de baja")
        void resolucionAnteriorAlDescargo() {
            Papeleta papeleta = papeletaDeTransito("402F");
            descargar(papeleta, "EXP-402F", LocalDate.of(2026, 3, 10));

            Throwable rechazo =
                    catchThrowable(
                            () ->
                                    dictar(
                                            papeleta,
                                            TipoDeResolucionDeGerencia.ORDINARIA,
                                            LocalDate.of(2026, 3, 5),
                                            "EXP-402F",
                                            SentidoDelFallo.FUNDADO,
                                            EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO));

            assertThat(rechazo)
                    .as("la fecha de la resolucion es la fecha valor de la baja")
                    .isInstanceOf(ActoFueraDeOrden.class)
                    .hasMessageContaining("EXP-402F")
                    .hasMessageContaining("2026-03-10");
            assertThat(cuantasResoluciones(papeleta, "ORDINARIA")).isZero();
            assertThat(deudaDe(papeleta, ORDINARIA)).isEqualTo(MULTA);
        }

        @Test
        @DisplayName("una resolucion fechada despues de hoy no se dicta")
        void resolucionPosteriorAHoy() {
            Papeleta papeleta = papeletaDeTransito("402G");
            descargar(papeleta, "EXP-402G", LocalDate.of(2026, 3, 10));

            Throwable rechazo =
                    catchThrowable(
                            () ->
                                    dictar(
                                            papeleta,
                                            TipoDeResolucionDeGerencia.ORDINARIA,
                                            LocalDate.of(2026, 4, 21),
                                            "EXP-402G",
                                            SentidoDelFallo.FUNDADO,
                                            EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO));

            assertThat(rechazo)
                    .isInstanceOf(ActoFueraDeOrden.class)
                    .hasMessageContaining("posterior a hoy");
            assertThat(cuantasResoluciones(papeleta, "ORDINARIA")).isZero();
            assertThat(deudaDe(papeleta, LocalDate.of(2026, 12, 31))).isEqualTo(MULTA);
        }

        /**
         * La diligencia ya tenia su cota inferior —{@code DiligenciaAnteriorALaResolucion}, una de
         * las cinco excepciones que #402 retira— y ahora tiene tambien la de hoy. Una diligencia
         * futura que surte efecto abre un plazo que todavia no corre, y de ahi sale el dia desde el
         * que cabe la sancionadora.
         */
        @Test
        @DisplayName("la diligencia: ni antes de la resolucion ni despues de hoy")
        void laDiligenciaDeLaResolucion() {
            Papeleta papeleta = papeletaDeTransito("402H");
            String numero =
                    dictar(
                                    papeleta,
                                    TipoDeResolucionDeGerencia.ORDINARIA,
                                    ORDINARIA,
                                    null,
                                    null,
                                    null)
                            .resolucion()
                            .numero();

            assertThat(
                            catchThrowable(
                                    () ->
                                            notificarResolucion(
                                                    numero,
                                                    ORDINARIA.minusDays(1),
                                                    ResultadoDeNotificacion.NOTIFICADO)))
                    .as("antes de la resolucion, con la misma excepcion que todas las demas")
                    .isInstanceOf(ActoFueraDeOrden.class)
                    .hasMessageContaining(numero);
            assertThat(
                            catchThrowable(
                                    () ->
                                            notificarResolucion(
                                                    numero,
                                                    LocalDate.of(2026, 4, 21),
                                                    ResultadoDeNotificacion.NOTIFICADO)))
                    .as("y despues de hoy")
                    .isInstanceOf(ActoFueraDeOrden.class)
                    .hasMessageContaining("posterior a hoy");
        }
    }

    // ==================================================================
    //  #371 — dos papeletas del mismo obligado, del mismo ejercicio y sin vehiculo del padron
    // ==================================================================

    /**
     * <b>La siembra que distingue (#371).</b> Hasta aqui cada papeleta de esta clase nacia con un
     * contribuyente propio —{@link #papeletaDeTransito(String)} crea uno por sufijo—, y con esa
     * muestra uniforme «dar de baja la obligacion de la papeleta» y «dar de baja la papeleta» son
     * la misma cifra. Aqui el obligado es UNO, el ejercicio el mismo y el vehiculo nulo, que es lo
     * corriente en una combi fuera del padron: T-001 por 440 y T-002 por 220 caen en la misma
     * obligacion del libro, {@code (obligado, MULTA_TRANSITO, 2026, sin unidad)}, con 660.
     */
    @Nested
    @DisplayName("#371 — anular o dejar sin efecto una papeleta no extingue la multa de otra")
    class LaObligacionCompartida {

        private static final Dinero T001 = Dinero.de("440.00");
        private static final Dinero T002 = Dinero.de("220.00");
        private static final Dinero LAS_DOS = Dinero.de("660.00");

        @Test
        @DisplayName("anular T-001 con T-002 en la misma obligacion: 409 que nombra T-002, y 660")
        void anularNoExtingueLaOtraMulta() {
            long obligado = crearContribuyente("OC1");
            Papeleta t001 = papeletaDeTransito("OC1-T001", obligado, T001);
            Papeleta t002 = papeletaDeTransito("OC1-T002", obligado, T002);
            assertThat(t001.vehiculoId()).as("la siembra: sin vehiculo del padron").isNull();
            assertThat(deudaDe(t001, ORDINARIA))
                    .as("las dos multas caen en la misma obligacion del libro")
                    .isEqualTo(LAS_DOS);

            Throwable rechazo = catchThrowable(() -> anular(t001));

            assertThat(deudaDe(t002, ORDINARIA))
                    .as(
                            "T-002 sigue debiendo lo suyo: anular T-001 no puede extinguir una"
                                    + " multa sin ningun acto que la sustente")
                    .isEqualTo(LAS_DOS);
            assertThat(rechazo)
                    .as("y se rechaza diciendo que otra papeleta comparte la obligacion")
                    .isInstanceOf(ObligacionCompartidaConOtraPapeleta.class)
                    .hasMessageContaining(t002.numero());
            assertThat(enTransaccion(() -> papeletas.porId(t001.identificador())))
                    .as("el rechazo deshace el acto entero: T-001 no queda ANULADA debiendo")
                    .get()
                    .extracting(Papeleta::estado)
                    .isEqualTo(EstadoDePapeleta.IMPUESTA);
        }

        @Test
        @DisplayName("dejar sin efecto T-002 por resolucion fundada tampoco se lleva T-001")
        void dejarSinEfectoNoExtingueLaOtraMulta() {
            long obligado = crearContribuyente("OC2");
            Papeleta t001 = papeletaDeTransito("OC2-T001", obligado, T001);
            Papeleta t002 = papeletaDeTransito("OC2-T002", obligado, T002);
            enTransaccion(
                    () ->
                            registrarDescargo.registrar(
                                    Familia.TRANSITO,
                                    t002.numero(),
                                    new RegistrarDescargo.Peticion(
                                            "EXP-OC2",
                                            INFRACCION.plusDays(2),
                                            TipoDeRecurso.DESCARGO,
                                            "El vehiculo estaba en el taller"),
                                    PORQUE),
                    "mesa.partes");

            Throwable rechazo =
                    catchThrowable(
                            () ->
                                    dictar(
                                            t002,
                                            TipoDeResolucionDeGerencia.ORDINARIA,
                                            ORDINARIA,
                                            "EXP-OC2",
                                            SentidoDelFallo.FUNDADO,
                                            EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO));

            assertThat(deudaDe(t001, ORDINARIA))
                    .as("T-001 sigue debiendo: la resolucion de T-002 no la dejo sin efecto")
                    .isEqualTo(LAS_DOS);
            assertThat(rechazo)
                    .as("y se rechaza nombrando la papeleta con la que comparte la obligacion")
                    .isInstanceOf(ObligacionCompartidaConOtraPapeleta.class)
                    .hasMessageContaining(t001.numero());
            assertThat(cuantasResoluciones(t002, "ORDINARIA"))
                    .as("el rechazo deshace la resolucion entera, con su papel y su numero")
                    .isZero();
        }

        @Test
        @DisplayName("con su obligacion para ella sola, la papeleta se sigue anulando entera")
        void sinCompartirSeAnulaComoSiempre() {
            long obligado = crearContribuyente("OC3");
            Papeleta sola = papeletaDeTransito("OC3-T001", obligado, T001);

            AnularPapeleta.Anulada anulada = anular(sola);

            assertThat(anulada.baja().importe())
                    .as("el control: la contencion no bloquea la obligacion de una sola papeleta")
                    .isEqualTo(T001);
            assertThat(deudaDe(sola, ORDINARIA)).isEqualTo(Dinero.CERO);
        }
    }

    /**
     * #422 — Lo que la base rechaza no sale como averia: de HTTP a PostgreSQL.
     *
     * <p>Cada prueba usa la siembra que la muestra de siempre no usa —el numero que <b>ya
     * existe</b>, el identificador 999999— y afirma dos cosas: el 4xx que el contrato promete, y
     * que <b>no se escribio ninguna linea ERROR</b>. Hasta #422 las seis contestaban 500 {@code
     * ERROR_INTERNO} con su incidencia, y en las dos emisiones despues de haber dibujado el papel.
     */
    @Nested
    @DisplayName("#422 — lo que la base rechaza sale como 404 o 409, sin incidencia")
    class LoQueLaBaseRechaza {

        private static final long INEXISTENTE = 999_999L;

        @Test
        @DisplayName("el mismo descargo dos veces es 409, y nombra el expediente")
        void elMismoDescargoDosVecesEs409() throws Exception {
            Papeleta papeleta = papeletaDeTransito("R01");
            String cuerpo =
                    "{\"observacion\":\"Descargo presentado en mesa de partes\","
                            + "\"papeleta\":\""
                            + papeleta.numero()
                            + "\",\"nDeExpediente\":\"EXP-R01\","
                            + "\"fechaDePresentacion\":\"2026-03-06\","
                            + "\"tipoDeRecurso\":\"DESCARGO\","
                            + "\"fundamento\":\"El vehiculo estaba en el taller\"}";
            Rechazo primero =
                    rechazo(() -> enviar(post("/rentas/api/v1/transito/descargos"), cuerpo));
            assertThat(primero.estado()).as(primero.cuerpo()).isEqualTo(201);

            Rechazo doble =
                    rechazo(() -> enviar(post("/rentas/api/v1/transito/descargos"), cuerpo));

            assertThat(doble.estado())
                    .as("el doble envio del mismo expediente: descargo_numero_uq")
                    .isEqualTo(409);
            assertThat(doble.cuerpo())
                    .contains("CONFLICTO")
                    .contains("EXP-R01")
                    .doesNotContain("descargo_numero_uq")
                    .doesNotContain("incidencia");
            assertThat(doble.errores())
                    .as("un rechazo del usuario no es una incidencia del servidor")
                    .isEmpty();
            assertThat(contar("SELECT count(*) FROM descargo WHERE numero_expediente = 'EXP-R01'"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("cambiar una papeleta a un numero que ya usa otra es 409, y lo nombra")
        void elNumeroEnUsoEs409() throws Exception {
            Papeleta que = papeletaDeTransito("R10");
            Papeleta otra = papeletaDeTransito("R11");

            Rechazo rechazo =
                    rechazo(
                            () ->
                                    enviar(
                                            patch(
                                                    "/rentas/api/v1/transito/papeletas/"
                                                            + que.numero()
                                                            + "/codigo"),
                                            "{\"observacion\":\"Error del operador al registrarla\","
                                                    + "\"numeroNuevo\":\""
                                                    + otra.numero()
                                                    + "\"}"));

            assertThat(rechazo.estado())
                    .as("reintentar un 500 no arreglaria nunca un numero que ya es de otra")
                    .isEqualTo(409);
            assertThat(rechazo.cuerpo())
                    .contains("CONFLICTO")
                    .contains(otra.numero())
                    .doesNotContain("papeleta_numero_uq")
                    .doesNotContain("incidencia");
            assertThat(rechazo.errores()).isEmpty();
            assertThat(
                            contar(
                                    "SELECT count(*) FROM papeleta WHERE numero = '"
                                            + que.numero()
                                            + "'"))
                    .as("y la papeleta conserva su numero")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("la misma notificacion administrativa dos veces es 409, y nombra el numero")
        void laMismaNotificacionDosVecesEs409() throws Exception {
            String cuerpo = notificacion("NA-R01", null);
            Rechazo primera = rechazo(() -> enviar(post(NOTIFICACIONES), cuerpo));
            assertThat(primera.estado()).as(primera.cuerpo()).isEqualTo(201);

            Rechazo doble = rechazo(() -> enviar(post(NOTIFICACIONES), cuerpo));

            assertThat(doble.estado()).as("notif_adm_numero_uq").isEqualTo(409);
            assertThat(doble.cuerpo())
                    .contains("CONFLICTO")
                    .contains("NA-R01")
                    .doesNotContain("notif_adm_numero_uq")
                    .doesNotContain("incidencia");
            assertThat(doble.errores()).isEmpty();
            assertThat(
                            contar(
                                    "SELECT count(*) FROM notificacion_administrativa WHERE numero = 'NA-R01'"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("una notificacion a un contribuyente que no existe es 404, y no se guarda")
        void laNotificacionAUnContribuyenteInexistenteEs404() throws Exception {
            Rechazo rechazo =
                    rechazo(
                            () ->
                                    enviar(
                                            post(NOTIFICACIONES),
                                            notificacion("NA-R02", INEXISTENTE)));

            assertThat(rechazo.estado()).as("notif_adm_contribuyente_fk").isEqualTo(404);
            assertThat(rechazo.cuerpo())
                    .contains("NO_ENCONTRADO")
                    .contains(String.valueOf(INEXISTENTE))
                    .doesNotContain("notif_adm_contribuyente_fk")
                    .doesNotContain("incidencia");
            assertThat(rechazo.errores()).isEmpty();
            assertThat(
                            contar(
                                    "SELECT count(*) FROM notificacion_administrativa WHERE numero = 'NA-R02'"))
                    .isZero();
        }

        @Test
        @DisplayName("una constancia de un vehiculo que no existe es 404, sin papel ni incidencia")
        void laConstanciaDeUnVehiculoInexistenteEs404() throws Exception {
            long documentosAntes = contar("SELECT count(*) FROM documento_emitido");

            Rechazo rechazo =
                    rechazo(
                            () ->
                                    enviar(
                                            post(CONSTANCIAS),
                                            constancia("ZZZ-422", INEXISTENTE, null)));

            assertThat(rechazo.estado()).as("constancia_libre_vehiculo_fk").isEqualTo(404);
            assertThat(rechazo.cuerpo())
                    .contains("NO_ENCONTRADO")
                    .contains(String.valueOf(INEXISTENTE))
                    .doesNotContain("constancia_libre_vehiculo_fk")
                    .doesNotContain("incidencia");
            assertThat(rechazo.errores()).isEmpty();
            assertThat(contar("SELECT count(*) FROM documento_emitido"))
                    .as("ningun papel se queda en la base")
                    .isEqualTo(documentosAntes);
        }

        @Test
        @DisplayName("una constancia pedida por un solicitante que no existe es 404")
        void laConstanciaDeUnSolicitanteInexistenteEs404() throws Exception {
            Rechazo rechazo =
                    rechazo(
                            () ->
                                    enviar(
                                            post(CONSTANCIAS),
                                            constancia("ZZZ-423", null, INEXISTENTE)));

            assertThat(rechazo.estado()).as("constancia_libre_solicitante_fk").isEqualTo(404);
            assertThat(rechazo.cuerpo())
                    .contains(String.valueOf(INEXISTENTE))
                    .doesNotContain("constancia_libre_solicitante_fk");
            assertThat(rechazo.errores()).isEmpty();
        }

        @Test
        @DisplayName("internar un vehiculo que no esta en el padron es 404, sin acta ni incidencia")
        void internarUnVehiculoInexistenteEs404() throws Exception {
            Papeleta papeleta = papeletaDeTransito("R20");

            Rechazo rechazo =
                    rechazo(
                            () ->
                                    enviar(
                                            post("/rentas/api/v1/transito/internamientos"),
                                            "{\"observacion\":\"Se interna para la prueba\","
                                                    + "\"placa\":\"ZZZ-424\",\"vehiculoId\":"
                                                    + INEXISTENTE
                                                    + ",\"papeleta\":\""
                                                    + papeleta.numero()
                                                    + "\",\"deposito\":\"DEPOSITO SULLANA NORTE\","
                                                    + "\"fechaDeIngreso\":\"2026-03-04T15:00:00Z\","
                                                    + "\"tasaDeCustodia\":\"CUSTODIA\","
                                                    + "\"motivo\":\"Conducir sin licencia vigente\"}"));

            assertThat(rechazo.estado()).as("internamiento_vehiculo_fk").isEqualTo(404);
            assertThat(rechazo.cuerpo())
                    .contains("NO_ENCONTRADO")
                    .contains(String.valueOf(INEXISTENTE))
                    .doesNotContain("internamiento_vehiculo_fk")
                    .doesNotContain("incidencia");
            assertThat(rechazo.errores()).isEmpty();
            assertThat(contar("SELECT count(*) FROM internamiento WHERE placa = 'ZZZ-424'"))
                    .isZero();
        }

        private static final String NOTIFICACIONES =
                "/rentas/api/v1/infracciones/administrativas/notificaciones";
        private static final String CONSTANCIAS = "/rentas/api/v1/transito/constancias-libres";

        private String notificacion(String numero, @Nullable Long contribuyenteId) {
            return "{\"observacion\":\"Notificacion previa de la prueba\",\"numero\":\""
                    + numero
                    + "\",\"fecha\":\"2026-03-10\","
                    + (contribuyenteId == null
                            ? ""
                            : "\"contribuyenteId\":" + contribuyenteId + ",")
                    + "\"direccion\":\"Av. Grau 100\",\"motivo\":\"Construccion sin licencia\"}";
        }

        private String constancia(
                String placa, @Nullable Long vehiculoId, @Nullable Long solicitanteId) {
            return "{\"observacion\":\"Constancia pedida en ventanilla\",\"placa\":\""
                    + placa
                    + "\""
                    + (vehiculoId == null ? "" : ",\"vehiculoId\":" + vehiculoId)
                    + (solicitanteId == null ? "" : ",\"solicitanteId\":" + solicitanteId)
                    + ",\"verificadaAl\":\"2026-04-20\"}";
        }
    }

    // ==================================================================

    /**
     * #423 — la placa se compara <b>sin su guion</b>, como la compara {@code Placa} y como la
     * compara {@code nucleo}.
     *
     * <p>La siembra es la que faltaba: <b>una grafia al guardar y la otra al pedir</b>. Hasta #423
     * las pruebas de este modulo escribian {@code "ABC-123"} a los dos lados, y con la misma grafia
     * a los dos lados un {@code p.placa = :placa} pasa aunque compare el texto crudo. Cada prueba
     * usa su placa, para que ninguna encuentre lo que sembro otra.
     */
    @Nested
    @DisplayName("#423 — la placa con y sin guion es el mismo vehiculo")
    class LaPlacaConYSinGuion {

        private static final String INTERNAMIENTOS = "/rentas/api/v1/transito/internamientos";
        private static final String CONSTANCIAS = "/rentas/api/v1/transito/constancias-libres";

        @Test
        @DisplayName("internado como «ZLG-701», volver a internarlo como «ZLG701» es 409")
        void noSeInternaDosVecesConOtraGrafia() throws Exception {
            internarVehiculo(papeletaDeTransito("P01"), "ZLG-701");

            Rechazo segundo = rechazo(() -> enviar(post(INTERNAMIENTOS), ingreso("ZLG701", "P02")));

            assertThat(segundo.estado())
                    .as(
                            "con 201 quedarian dos internamientos abiertos del mismo vehiculo,"
                                    + " cada uno acumulando su custodia: "
                                    + segundo.cuerpo())
                    .isEqualTo(409);
            assertThat(segundo.cuerpo()).contains("CONFLICTO").contains("ZLG-701");
            assertThat(
                            contar(
                                    "SELECT count(*) FROM internamiento"
                                            + " WHERE replace(placa, '-', '') = 'ZLG701'"))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("internado como «ZLG-702», se libera pidiendolo como «zlg702»")
        void seLiberaConOtraGrafia() throws Exception {
            Papeleta papeleta = papeletaDeTransito("P03");
            internarVehiculo(papeleta, "ZLG-702");
            String recibo = cobrarCustodia(papeleta.obligadoId());

            Rechazo liberacion =
                    rechazo(
                            () ->
                                    enviar(
                                            post(INTERNAMIENTOS + "/zlg702/liberacion"),
                                            "{\"observacion\":\"El titular retira el vehiculo\","
                                                    + "\"fechaDeLiberacion\":\"2026-04-01\","
                                                    + "\"reciboDeCustodia\":\""
                                                    + recibo
                                                    + "\",\"personaQueRetira\":\"DORIS\","
                                                    + "\"documentoDeQuienRetira\":\"DNI 44218937\","
                                                    + "\"soatVigenteAcreditado\":true}"));

            assertThat(liberacion.estado())
                    .as(
                            "con 404 «no esta internado» se bloquea una liberacion legitima: "
                                    + liberacion.cuerpo())
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("con una papeleta pendiente de «ZLG-703», la constancia de «ZLG703» es 409")
        void laConstanciaVeLaPapeletaConOtraGrafia() throws Exception {
            papeletaDeTransitoDe("P04", "ZLG-703");
            long documentosAntes = contar("SELECT count(*) FROM documento_emitido");

            Rechazo constancia = rechazo(() -> enviar(post(CONSTANCIAS), constanciaDe("ZLG703")));

            assertThat(constancia.estado())
                    .as(
                            "con 201 la constancia acreditaria que el vehiculo no tiene papeletas"
                                    + " pendientes, y tiene una")
                    .isEqualTo(409);
            assertThat(constancia.cuerpo()).contains("PT-P04");
            assertThat(contar("SELECT count(*) FROM documento_emitido"))
                    .as("ningun papel sale")
                    .isEqualTo(documentosAntes);
        }

        @Test
        @DisplayName("la grilla del deposito encuentra «ZLG-704» pidiendo «zlg 704»")
        void laGrillaDelDepositoLaEncuentra() {
            internarVehiculo(papeletaDeTransito("P05"), "ZLG-704");

            Pagina<InternamientoEnConsulta> grilla =
                    enTransaccion(
                            () ->
                                    consultaDeDeposito.listar(
                                            new CriterioDeInternamiento("zlg 704", null, null),
                                            SANCIONADORA_DESDE,
                                            Paginacion.de(0, 20, "fechaIngreso")));

            assertThat(grilla.contenido())
                    .extracting(InternamientoEnConsulta::placa)
                    .as("el guion y el espacio son de lectura, no del dato")
                    .containsExactly("ZLG-704");
        }

        @Test
        @DisplayName("la busqueda de papeletas encuentra «ZLG-705» pidiendo «ZLG705»")
        void laBusquedaDePapeletasLaEncuentra() {
            papeletaDeTransitoDe("P06", "ZLG-705");

            Pagina<Papeleta> encontradas =
                    enTransaccion(
                            () ->
                                    papeletas.buscar(
                                            new CriterioDePapeleta(
                                                    Familia.TRANSITO,
                                                    null,
                                                    "ZLG705",
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    false),
                                            Paginacion.de(0, 20, "numero")));

            assertThat(encontradas.contenido())
                    .extracting(Papeleta::numero)
                    .containsExactly("PT-P06");
        }

        @Test
        @DisplayName("la grilla de constancias encuentra «ZLG-706» pidiendo «zlg706»")
        void laGrillaDeConstanciasLaEncuentra() throws Exception {
            Rechazo emitida = rechazo(() -> enviar(post(CONSTANCIAS), constanciaDe("ZLG-706")));
            assertThat(emitida.estado()).as(emitida.cuerpo()).isEqualTo(201);

            Pagina<ConstanciaLibre> grilla =
                    enTransaccion(
                            () ->
                                    new ConstanciaLibreRepositoryJdbc(jdbc)
                                            .buscar(
                                                    new CriterioDeConstancias(
                                                            null, null, null, null, "zlg706"),
                                                    Paginacion.de(0, 20, "numero")));

            assertThat(grilla.contenido())
                    .extracting(ConstanciaLibre::placa)
                    .containsExactly("ZLG-706");
        }

        private String ingreso(String placa, String sufijoDeLaPapeleta) {
            Papeleta papeleta = papeletaDeTransito(sufijoDeLaPapeleta);
            return "{\"observacion\":\"Se interna para la prueba\",\"placa\":\""
                    + placa
                    + "\",\"papeleta\":\""
                    + papeleta.numero()
                    + "\",\"deposito\":\"DEPOSITO SULLANA NORTE\","
                    + "\"fechaDeIngreso\":\"2026-03-04T15:00:00Z\","
                    + "\"tasaDeCustodia\":\"CUSTODIA\","
                    + "\"motivo\":\"Conducir sin licencia vigente\"}";
        }

        private String constanciaDe(String placa) {
            return "{\"observacion\":\"Constancia pedida en ventanilla\",\"placa\":\""
                    + placa
                    + "\",\"verificadaAl\":\"2026-04-20\"}";
        }
    }

    // ==================================================================
    //  #385 — la multa dejada sin efecto: la situacion se deriva, y alguien la deriva
    // ==================================================================

    /**
     * {@code ResolverConResolucionDeGerencia} dice que el estado de la papeleta <b>no se toca</b>
     * cuando la resolución la deja sin efecto, porque «su situación se deriva de las resoluciones».
     * Hasta #385 ningún lector la derivaba: la fase miraba sólo si había una {@code
     * ADMINISTRATIVA}, y lo pendiente salía entero de {@code p.estado}, que se queda en {@code
     * IMPUESTA}.
     *
     * <p><b>La siembra que distingue son dos papeletas por familia, las dos con su descargo
     * resuelto</b>: una FUNDADO / {@code SE_DEJA_SIN_EFECTO} y otra INFUNDADO / {@code
     * SE_MANTIENE}. La segunda es la que separa «mira el efecto» de «cualquier resolución con
     * descargo»: un arreglo que excluyera por {@code descargo_id IS NOT NULL} pasaría la primera y
     * se pondría rojo aquí. Los importes son distintos a propósito, para que una suma que contara
     * la equivocada no cuadre por casualidad.
     *
     * <p>Y todo corre por los casos de uso de verdad —el descargo con su plazo parametrizado, la
     * resolución con su baja en el libro— y no por un {@code INSERT} en {@code
     * resolucion_gerencia}: lo que #385 mide es que lo que la resolución dicta llegue a la grilla.
     */
    @Nested
    @DisplayName(
            "#385 — la multa dejada sin efecto no tiene fase, no esta pendiente y no se impugna")
    class LaMultaDejadaSinEfecto {

        /** ACT-20 del issue: la que se deja sin efecto. */
        private static final Dinero ACT_20 = Dinero.de("2675.00");

        /**
         * ACT-21 del issue: la que se mantiene. Otro importe, para que ninguna suma cuadre sola.
         */
        private static final Dinero ACT_21 = Dinero.de("1070.00");

        private static final String CONSTANCIAS = "/rentas/api/v1/transito/constancias-libres";

        @Test
        @DisplayName("la fase de la dejada sin efecto es nula y el filtro SANCIONADA no la trae")
        void laFaseDeLaDejadaSinEfectoEsNula() {
            DosActas actas = dosActasResueltas("F");

            assertThat(faseDe(actas.sinEfecto()))
                    .as(
                            "una multa dejada sin efecto termino sin palabra del manual: con"
                                    + " SANCIONADA la grilla dibuja una fase plausible y equivocada")
                    .isNull();
            assertThat(faseDe(actas.mantenida()))
                    .as(
                            "la que se mantiene sigue SANCIONADA: la fase mira el EFECTO, no el descargo")
                    .isEqualTo(FaseDelProcedimiento.SANCIONADA);

            Pagina<ProcedimientoSancionador> sancionadas =
                    enTransaccion(
                            () ->
                                    new ProcedimientoSancionadorRepositoryJdbc(jdbc)
                                            .buscar(
                                                    new CriterioDelProcedimiento(
                                                            null,
                                                            null,
                                                            actas.codigo(),
                                                            FaseDelProcedimiento.SANCIONADA,
                                                            RESUELTA_EL),
                                                    Paginacion.de(0, 20, "numero")));
            assertThat(sancionadas.contenido())
                    .extracting(ProcedimientoSancionador::numeroActa)
                    .as("el WHERE usa la misma expresion que el SELECT")
                    .containsExactly(actas.mantenida().numero());
        }

        @Test
        @DisplayName(
                "la dejada sin efecto no esta pendiente: ni en el padron, ni en su filtro,"
                        + " ni en el resumen")
        void laDejadaSinEfectoNoEstaPendiente() {
            DosActas actas = dosActasResueltas("P");
            CriterioDePadron todas = padronDe(Familia.ADMINISTRATIVA, actas.codigo(), null, false);
            CriterioDePadron pendientes =
                    padronDe(Familia.ADMINISTRATIVA, actas.codigo(), null, true);

            Map<String, Boolean> pendientePorNumero =
                    enTransaccion(
                                    () ->
                                            new PadronDePapeletasRepositoryJdbc(jdbc)
                                                    .buscar(todas, Paginacion.de(0, 20, "numero")))
                            .contenido()
                            .stream()
                            .collect(
                                    java.util.stream.Collectors.toMap(
                                            PapeletaDelPadron::numero,
                                            PapeletaDelPadron::estaPendiente));
            assertThat(pendientePorNumero)
                    .as("lo que la API publica como `pendiente`, fila a fila")
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of(
                                    actas.sinEfecto().numero(), false,
                                    actas.mantenida().numero(), true));

            assertThat(
                            enTransaccion(
                                            () ->
                                                    new PadronDePapeletasRepositoryJdbc(jdbc)
                                                            .buscar(
                                                                    pendientes,
                                                                    Paginacion.de(0, 20, "numero")))
                                    .contenido())
                    .extracting(PapeletaDelPadron::numero)
                    .as("el filtro de pendientes dice lo mismo que la columna")
                    .containsExactly(actas.mantenida().numero());

            List<LineaDelResumen> resumen =
                    enTransaccion(
                            () ->
                                    new PadronDePapeletasRepositoryJdbc(jdbc)
                                            .resumir(todas, AgrupacionDelResumen.CODIGO));
            assertThat(resumen).hasSize(1);
            assertThat(resumen.get(0).cantidad()).isEqualTo(2);
            assertThat(resumen.get(0).pendientes())
                    .as(
                            "el resumen de recaudacion no cuenta como pendiente la que se dejo sin efecto")
                    .isEqualTo(1);
            assertThat(resumen.get(0).importeDeLasPendientes())
                    .as("y su importe es el de ACT-21, no la suma de las dos")
                    .isEqualTo(ACT_21);
        }

        @Test
        @DisplayName(
                "en transito, la dejada sin efecto sale del estado de cuenta y del panel, y la"
                        + " constancia se emite")
        void enTransitoLaConstanciaSeEmite() throws Exception {
            PapeletasSinNotificarSanciones panel =
                    envolver(
                            new PapeletasSinNotificarSanciones(
                                    new PadronDePapeletasRepositoryJdbc(jdbc)));

            Papeleta sinEfecto = papeletaDeTransitoDe("T010", "ZSE-010");
            Papeleta mantenida = papeletaDeTransitoDe("T011", "ZSE-011");
            PapeletasSinNotificar.PapeletasImpuestas antes = panel.sinNotificar();

            resolverConDescargo(
                    sinEfecto,
                    TipoDeResolucionDeGerencia.ORDINARIA,
                    SentidoDelFallo.FUNDADO,
                    EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO);
            resolverConDescargo(
                    mantenida,
                    TipoDeResolucionDeGerencia.ORDINARIA,
                    SentidoDelFallo.INFUNDADO,
                    EfectoSobreLaMulta.SE_MANTIENE);

            assertThat(deudaDe(sinEfecto, ORDINARIA))
                    .as("el libro ya dice que no debe nada; la prueba mira si alguien lo lee")
                    .isEqualTo(Dinero.CERO);

            PapeletasSinNotificar.PapeletasImpuestas despues = panel.sinNotificar();
            assertThat(despues.cuantas())
                    .as("el frente «sin notificar» del panel deja de contarla")
                    .isEqualTo(antes.cuantas() - 1);
            assertThat(despues.importe()).isEqualTo(antes.importe().menos(MULTA));

            assertThat(pendienteEnElPadron("ZSE-010")).isFalse();
            assertThat(pendienteEnElPadron("ZSE-011")).isTrue();

            assertThat(enElEstadoDeCuenta("ZSE-010"))
                    .as("el estado de cuenta de transito no lista la multa que se dejo sin efecto")
                    .isEmpty();
            assertThat(enElEstadoDeCuenta("ZSE-011")).containsExactly(mantenida.numero());

            Rechazo libre = rechazo(() -> enviar(post(CONSTANCIAS), constanciaDe("ZSE-010")));
            assertThat(libre.estado())
                    .as(
                            "al administrado al que se le dio la razon no se le niega la constancia:"
                                    + " "
                                    + libre.cuerpo())
                    .isEqualTo(201);
            Rechazo negada = rechazo(() -> enviar(post(CONSTANCIAS), constanciaDe("ZSE-011")));
            assertThat(negada.estado()).as(negada.cuerpo()).isEqualTo(409);
            assertThat(negada.cuerpo()).contains(mantenida.numero());
        }

        @Test
        @DisplayName("sobre la dejada sin efecto no se registra otro descargo ni se dicta otra RIS")
        void noSeImpugnaNiSeResuelveOtraVez() {
            DosActas actas = dosActasResueltas("G");

            assertThatThrownBy(() -> descargarLa(actas.sinEfecto(), "EXP-G-2"))
                    .as("un recurso contra una multa que ya no existe no tiene objeto")
                    .isInstanceOf(RegistrarDescargo.PapeletaSinNadaQueImpugnar.class)
                    .hasMessageContaining(actas.sinEfecto().numero())
                    .hasMessageContaining("sin efecto");
            assertThatThrownBy(
                            () ->
                                    dictar(
                                            actas.sinEfecto(),
                                            TipoDeResolucionDeGerencia.ADMINISTRATIVA,
                                            RESUELTA_EL,
                                            null,
                                            null,
                                            null))
                    .isInstanceOf(RegistrarDescargo.PapeletaSinNadaQueImpugnar.class);

            assertThat(descargarLa(actas.mantenida(), "EXP-G-3").descargo().numeroExpediente())
                    .as("la que se mantiene sigue admitiendo recurso: la guarda mira el efecto")
                    .isEqualTo("EXP-G-3");
        }

        // -------------------------------------------------------------- la siembra

        /**
         * El día de las dos resoluciones: el de la ordinaria de la clase, con el plazo cumplido.
         */
        private static final LocalDate RESUELTA_EL = ORDINARIA;

        private record DosActas(Papeleta sinEfecto, Papeleta mantenida, String codigo) {}

        /**
         * ACT-20 y ACT-21, del mismo código y de <b>dos obligados distintos</b>: con el mismo, la
         * baja de ACT-20 chocaría con la obligación que comparte con ACT-21 (#371), que es otro
         * issue y no este.
         */
        private DosActas dosActasResueltas(String sufijo) {
            String codigo = "ADM-385" + sufijo;
            insertar(
                    "INSERT INTO codigo_infraccion (municipalidad_id, familia, codigo, descripcion,"
                            + " porcentaje_uit, base_legal, vigencia_desde) VALUES ("
                            + municipalidad
                            + ", 'ADMINISTRATIVA', '"
                            + codigo
                            + "', 'Infraccion de la prueba', 8.0000, 'Ordenanza de la prueba',"
                            + " DATE '2026-01-01') RETURNING id");
            Papeleta sinEfecto = acta("ACT-20" + sufijo, codigo, ACT_20, "50");
            Papeleta mantenida = acta("ACT-21" + sufijo, codigo, ACT_21, "20");

            resolverConDescargo(
                    sinEfecto,
                    TipoDeResolucionDeGerencia.ADMINISTRATIVA,
                    SentidoDelFallo.FUNDADO,
                    EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO);
            resolverConDescargo(
                    mantenida,
                    TipoDeResolucionDeGerencia.ADMINISTRATIVA,
                    SentidoDelFallo.INFUNDADO,
                    EfectoSobreLaMulta.SE_MANTIENE);
            return new DosActas(sinEfecto, mantenida, codigo);
        }

        private Papeleta acta(String numero, String codigo, Dinero multa, String porcentaje) {
            long obligado = crearContribuyente(numero);
            return enTransaccion(
                    () ->
                            registrarPapeleta.registrarAdministrativa(
                                    numero,
                                    codigo,
                                    INFRACCION,
                                    null,
                                    "Av. Grau",
                                    obligado,
                                    null,
                                    null,
                                    obligado,
                                    Dinero.de("5350.00"),
                                    Alicuota.de(porcentaje),
                                    multa,
                                    Alicuota.de("100"),
                                    multa,
                                    null,
                                    PORQUE));
        }

        private void resolverConDescargo(
                Papeleta papeleta,
                TipoDeResolucionDeGerencia tipo,
                SentidoDelFallo sentido,
                EfectoSobreLaMulta efecto) {
            String expediente = "EXP-" + papeleta.numero();
            descargarLa(papeleta, expediente);
            dictar(papeleta, tipo, RESUELTA_EL, expediente, sentido, efecto);
        }

        private RegistrarDescargo.Registrado descargarLa(Papeleta papeleta, String expediente) {
            return enTransaccion(
                    () ->
                            registrarDescargo.registrar(
                                    papeleta.familia(),
                                    papeleta.numero(),
                                    new RegistrarDescargo.Peticion(
                                            expediente,
                                            INFRACCION.plusDays(2),
                                            TipoDeRecurso.DESCARGO,
                                            "El administrado acredita que no cometio la infraccion"),
                                    PORQUE),
                    "mesa.partes");
        }

        // -------------------------------------------------------------- las lecturas

        private @Nullable FaseDelProcedimiento faseDe(Papeleta acta) {
            List<ProcedimientoSancionador> filas =
                    enTransaccion(
                                    () ->
                                            new ProcedimientoSancionadorRepositoryJdbc(jdbc)
                                                    .buscar(
                                                            new CriterioDelProcedimiento(
                                                                    acta.numero(),
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    RESUELTA_EL),
                                                            Paginacion.de(0, 20, "numero")))
                            .contenido();
            assertThat(filas).hasSize(1);
            return filas.get(0).fase();
        }

        private boolean pendienteEnElPadron(String placa) {
            List<PapeletaDelPadron> filas =
                    enTransaccion(
                                    () ->
                                            new PadronDePapeletasRepositoryJdbc(jdbc)
                                                    .buscar(
                                                            padronDe(
                                                                    Familia.TRANSITO,
                                                                    null,
                                                                    placa,
                                                                    false),
                                                            Paginacion.de(0, 20, "numero")))
                            .contenido();
            assertThat(filas).hasSize(1);
            return filas.get(0).estaPendiente();
        }

        private List<String> enElEstadoDeCuenta(String placa) {
            return enTransaccion(
                            () ->
                                    papeletas.buscar(
                                            new CriterioDePapeleta(
                                                    Familia.TRANSITO,
                                                    null,
                                                    placa,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    true),
                                            Paginacion.de(0, 20, "numero")))
                    .contenido()
                    .stream()
                    .map(Papeleta::numero)
                    .toList();
        }

        private CriterioDePadron padronDe(
                Familia familia,
                @Nullable String codigo,
                @Nullable String placa,
                boolean soloPendientes) {
            return new CriterioDePadron(
                    familia,
                    null,
                    null,
                    null,
                    codigo,
                    placa,
                    null,
                    null,
                    null,
                    null,
                    soloPendientes);
        }

        private String constanciaDe(String placa) {
            return "{\"observacion\":\"Constancia pedida en ventanilla\",\"placa\":\""
                    + placa
                    + "\",\"verificadaAl\":\"2026-04-20\"}";
        }
    }

    /** Lo que un rechazo contesta, y las lineas ERROR que dejo en el registro del manejador. */
    private record Rechazo(int estado, String cuerpo, List<String> errores) {}

    private static Rechazo rechazo(Callable<MvcResult> peticion) throws Exception {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> anotados =
                new ch.qos.logback.core.read.ListAppender<>();
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
                        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .toList());
    }

    private static MvcResult enviar(MockHttpServletRequestBuilder peticion, String cuerpo)
            throws Exception {
        return mvc.perform(peticion.contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andReturn();
    }

    @Nested
    @DisplayName("#413 — el papel de la resolucion que deja la multa sin efecto")
    class ElPapelDeLaQueExtingue {

        @Test
        @DisplayName("el documento guardado no dice «TOTAL EXIGIBLE» ni concede plazo de pago")
        void elDocumentoGuardadoNoLaDeclaraExigible() {
            Papeleta papeleta = papeletaDeTransito("413A");
            descargar(papeleta, "EXP-413A", LocalDate.of(2026, 3, 10));

            ResolverConResolucionDeGerencia.ResolucionDictada dictada =
                    dictar(
                            papeleta,
                            TipoDeResolucionDeGerencia.ORDINARIA,
                            LocalDate.of(2026, 4, 1),
                            "EXP-413A",
                            SentidoDelFallo.FUNDADO,
                            EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO);
            String papel =
                    enTransaccion(
                            () ->
                                    jdbc.sql(
                                                    "SELECT datos::text FROM documento_emitido"
                                                            + " WHERE numero = :numero")
                                            .param("numero", dictada.resolucion().numero())
                                            .query(String.class)
                                            .single());

            assertThat(papel)
                    .as("lo que se sella y se reimprime diez años despues")
                    .doesNotContain("TOTAL EXIGIBLE")
                    .contains("TOTAL QUE SE DEJA SIN EFECTO")
                    .contains("SALDO EXIGIBLE TRAS ESTA RESOLUCION")
                    .doesNotContain("Plazo de pago");
            assertThat(dictada.baja()).as("y en la misma transaccion, la baja").isNotNull();
        }
    }

    private static long contar(String consulta) {
        Long cuantas = enTransaccion(() -> jdbc.sql(consulta).query(Long.class).single());
        return cuantas == null ? 0 : cuantas;
    }

    // ==================================================================
    //  Utilidades
    // ==================================================================

    /**
     * El contexto se fija <b>antes</b> de abrir la transacción, no dentro: {@code
     * TenantTransactionManager} lo lee al comenzarla para emitir el {@code SET LOCAL}.
     */
    private static <T> T enTransaccion(Supplier<T> accion) {
        return enTransaccionDe(municipalidad, accion, "inspector.transito");
    }

    private static <T> T enTransaccion(Supplier<T> accion, String usuario) {
        return enTransaccionDe(municipalidad, accion, usuario);
    }

    private static <T> T enTransaccionDe(long tenant, Supplier<T> accion, String usuario) {
        TenantContext.fijar(new MunicipalidadId(tenant));
        OrigenContext.fijar(new Origen(usuario, null, null));
        return transaccion.execute(
                estado -> {
                    TenantContext.fijar(new MunicipalidadId(tenant));
                    OrigenContext.fijar(new Origen(usuario, null, null));
                    return accion.get();
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    /** Una papeleta de tránsito con su cargo ya asentado en el libro. */
    private static Papeleta papeletaDeTransito(String sufijo) {
        return papeletaDeTransito(sufijo, crearContribuyente(sufijo), MULTA);
    }

    /**
     * Una papeleta de tránsito con <b>la placa que se pida</b> (#423): la de {@link
     * #papeletaDeTransito(String)} sale del sufijo, y lo que #423 mide es una grafia concreta.
     */
    private static Papeleta papeletaDeTransitoDe(String sufijo, String placa) {
        long obligado = crearContribuyente(sufijo);
        crearCodigo("G-" + sufijo);
        return enTransaccion(
                () ->
                        registrarPapeleta.registrarTransito(
                                "PT-" + sufijo,
                                "G-" + sufijo,
                                INFRACCION,
                                null,
                                "Av. Grau",
                                placa,
                                null,
                                null,
                                null,
                                obligado,
                                obligado,
                                Dinero.de("5350.00"),
                                Alicuota.de("8"),
                                MULTA,
                                Alicuota.de("100"),
                                MULTA,
                                null,
                                PORQUE));
    }

    /**
     * Una papeleta de tránsito <b>de un obligado que ya existe</b>, por el importe que se pida
     * (#371): es lo que deja sembrar dos papeletas en la misma obligación del libro.
     */
    private static Papeleta papeletaDeTransito(String sufijo, long obligado, Dinero multa) {
        crearCodigo("G-" + sufijo);
        return enTransaccion(
                () ->
                        registrarPapeleta.registrarTransito(
                                "PT-" + sufijo,
                                "G-" + sufijo,
                                INFRACCION,
                                null,
                                "Av. Grau",
                                "T2G-" + Math.abs(sufijo.hashCode() % 900 + 100),
                                null,
                                null,
                                null,
                                obligado,
                                obligado,
                                Dinero.de("5350.00"),
                                Alicuota.de("8"),
                                multa,
                                Alicuota.de("100"),
                                multa,
                                null,
                                PORQUE));
    }

    private static AnularPapeleta.Anulada anular(Papeleta papeleta) {
        return enTransaccion(
                () ->
                        anularPapeleta.anular(
                                papeleta.familia(), papeleta.numero(), ORDINARIA, PORQUE),
                "gerente");
    }

    /** #402: un descargo de la papeleta, presentado el dia que se pida. */
    private static RegistrarDescargo.Registrado descargar(
            Papeleta papeleta, String expediente, LocalDate presentado) {
        return enTransaccion(
                () ->
                        registrarDescargo.registrar(
                                Familia.TRANSITO,
                                papeleta.numero(),
                                new RegistrarDescargo.Peticion(
                                        expediente,
                                        presentado,
                                        TipoDeRecurso.DESCARGO,
                                        "El vehiculo estaba en el taller"),
                                PORQUE),
                "mesa.partes");
    }

    private static long cuantosDescargos(Papeleta papeleta) {
        return enTransaccion(
                () ->
                        jdbc.sql("SELECT count(*) FROM descargo WHERE papeleta_id = :papeleta")
                                .param("papeleta", papeleta.identificador())
                                .query(Long.class)
                                .single());
    }

    /** #402: anula con el reloj del 23 de setiembre y a la fecha que se pida. */
    private static AnularPapeleta.Anulada anularEl(Papeleta papeleta, LocalDate fecha) {
        return enTransaccion(
                () ->
                        anularAl23DeSetiembre.anular(
                                papeleta.familia(), papeleta.numero(), fecha, PORQUE),
                "gerente");
    }

    private static EstadoDePapeleta estadoDe(Papeleta papeleta) {
        return enTransaccion(() -> papeletas.porId(papeleta.identificador()))
                .orElseThrow()
                .estado();
    }

    private static long cuantosAbonos(Papeleta papeleta) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT count(*) FROM cuenta_corriente_asiento"
                                                + " WHERE contribuyente_id = :contribuyente"
                                                + "   AND tipo = 'ABONO'")
                                .param("contribuyente", papeleta.obligadoId())
                                .query(Long.class)
                                .single());
    }

    private static ResolverConResolucionDeGerencia.ResolucionDictada dictar(
            Papeleta papeleta,
            TipoDeResolucionDeGerencia tipo,
            LocalDate fecha,
            String expedienteDelDescargo,
            SentidoDelFallo sentido,
            EfectoSobreLaMulta efecto) {
        return enTransaccion(
                () ->
                        resolver.dictar(
                                new ResolverConResolucionDeGerencia.Peticion(
                                        papeleta.familia(),
                                        papeleta.numero(),
                                        tipo,
                                        fecha,
                                        expedienteDelDescargo,
                                        sentido,
                                        efecto,
                                        null,
                                        "Sustento de la prueba",
                                        null),
                                FormatoDeDocumento.PDF,
                                PORQUE),
                "gerente");
    }

    private static NotificarResolucionDeGerencia.Diligencia notificarResolucion(
            String numero, LocalDate fecha, ResultadoDeNotificacion resultado) {
        boolean recibio = resultado == ResultadoDeNotificacion.NOTIFICADO;
        return enTransaccion(
                () ->
                        notificar.registrar(
                                numero,
                                java.util.EnumSet.allOf(
                                        kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia
                                                .class),
                                new NotificarResolucionDeGerencia.Peticion(
                                        fecha,
                                        ModalidadDeNotificacion.PERSONAL,
                                        resultado,
                                        "V. RETO SANTOS",
                                        "AV. JOSE DE LAMA 1180 - SULLANA",
                                        recibio ? "RUIZ INGA, FERNANDO" : null,
                                        recibio ? "DNI 10027723" : null,
                                        recibio ? "REPRESENTANTE" : null,
                                        recibio ? "CARGO-RG" : null),
                                PORQUE),
                "notificador");
    }

    /**
     * Un vehiculo del padron, para que el internamiento pueda nombrarlo (#185).
     *
     * <p>{@code categoria} admite nulo en {@code vehiculo}, y por eso esta prueba siembra las dos
     * formas: la ficha con categoria y la ficha sin ella. Son dos ausencias distintas y la grilla
     * tiene que decirlas igual —nulo— sin confundir ninguna con un dato.
     */
    private static long crearVehiculo(
            long contribuyenteId, String placa, @Nullable String categoria) {
        return insertar(
                "INSERT INTO vehiculo (municipalidad_id, placa, contribuyente_id, marca, modelo,"
                        + " categoria, anio_fabricacion, anio_inscripcion) VALUES ("
                        + municipalidad
                        + ", '"
                        + placa
                        + "', "
                        + contribuyenteId
                        + ", 'TOYOTA', 'YARIS', "
                        + (categoria == null ? "NULL" : "'" + categoria + "'")
                        + ", 2018, 2019) RETURNING id");
    }

    private static RegistrarInternamiento.Internado internarVehiculo(
            Papeleta papeleta, String placa) {
        return internarVehiculo(papeleta, placa, null);
    }

    private static RegistrarInternamiento.Internado internarVehiculo(
            Papeleta papeleta, String placa, @Nullable Long vehiculoId) {
        return internarVehiculo(papeleta, placa, vehiculoId, INGRESO_DE_DIA);
    }

    private static RegistrarInternamiento.Internado internarVehiculo(
            Papeleta papeleta, String placa, @Nullable Long vehiculoId, Instant ingreso) {
        return enTransaccion(
                () ->
                        internar.internar(
                                new RegistrarInternamiento.Peticion(
                                        placa,
                                        vehiculoId,
                                        papeleta.numero(),
                                        "DEPOSITO SULLANA NORTE",
                                        ingreso,
                                        "CUSTODIA",
                                        "Conducir sin licencia vigente"),
                                FormatoDeDocumento.PDF,
                                PORQUE));
    }

    private static LiberarVehiculoInternado.Liberado liberarVehiculo(String placa, String recibo) {
        return liberarVehiculo(placa, recibo, ORDINARIA);
    }

    private static LiberarVehiculoInternado.Liberado liberarVehiculo(
            String placa, String recibo, LocalDate fecha) {
        return enTransaccion(
                () ->
                        liberar.liberar(
                                new LiberarVehiculoInternado.Peticion(
                                        placa,
                                        fecha,
                                        recibo,
                                        "SERNAQUE VILLEGAS, DORIS",
                                        "DNI 44218937",
                                        true),
                                FormatoDeDocumento.PDF,
                                PORQUE));
    }

    private static Dinero deudaDe(Papeleta papeleta, LocalDate fecha) {
        List<ObligacionPublica> obligaciones =
                enTransaccion(() -> deudas.todasDe(papeleta.obligadoId(), fecha));
        Dinero total = Dinero.CERO;
        for (ObligacionPublica obligacion : obligaciones) {
            if ("MULTA_TRANSITO".equals(obligacion.tributo())) {
                total = total.mas(obligacion.total());
            }
        }
        return total;
    }

    private static String motivoDelUltimoAbono(Papeleta papeleta) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT motivo FROM cuenta_corriente_asiento"
                                                + " WHERE contribuyente_id = :contribuyente"
                                                + "   AND tipo = 'ABONO' ORDER BY id DESC LIMIT 1")
                                .param("contribuyente", papeleta.obligadoId())
                                .query(String.class)
                                .single());
    }

    /** La causal de la ultima baja asentada contra el obligado de la papeleta (#684). */
    private static String causalDelUltimoAbono(Papeleta papeleta) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT causal FROM cuenta_corriente_asiento"
                                                + " WHERE contribuyente_id = :contribuyente"
                                                + "   AND tipo = 'ABONO' ORDER BY id DESC LIMIT 1")
                                .param("contribuyente", papeleta.obligadoId())
                                .query(String.class)
                                .single());
    }

    private static long cuantasFilasDeAuditoria() {
        Long cuantas =
                enTransaccion(
                        () ->
                                jdbc.sql("SELECT count(*) FROM auditoria")
                                        .query(Long.class)
                                        .single());
        return cuantas == null ? 0 : cuantas;
    }

    private static List<String> observacionesDeAuditoria() {
        return enTransaccion(
                () -> jdbc.sql("SELECT observacion FROM auditoria").query(String.class).list());
    }

    private static long cuantasResoluciones(Papeleta papeleta, String tipo) {
        Long cuantas =
                enTransaccion(
                        () ->
                                jdbc.sql(
                                                "SELECT count(*) FROM resolucion_gerencia"
                                                        + " WHERE papeleta_id = :papeleta AND tipo ="
                                                        + " :tipo")
                                        .param("papeleta", papeleta.identificador())
                                        .param("tipo", tipo)
                                        .query(Long.class)
                                        .single());
        return cuantas == null ? 0 : cuantas;
    }

    /**
     * Como si la caja hubiera cobrado la custodia, y devuelve el numero del recibo.
     *
     * <p>Hasta `V7` esto emitia el recibo de verdad en `recibo` y `recibo_detalle`. Esas tablas se
     * fueron a `caja`; lo que este sistema puede saber de un cobro es lo que el puerto conteste, y
     * eso es lo que el doble siembra.
     */
    private static String cobrarCustodia(long contribuyenteId) {
        return cobrarCustodia(contribuyenteId, DIAS_HASTA_LA_ORDINARIA);
    }

    /**
     * Los dias que cubre la custodia de {@link #cobrarCustodia(long)}: del 4 de marzo, dia del
     * ingreso, al 1 de abril, que es el dia en que {@link #liberarVehiculo(String, String)} libera.
     *
     * <p>Hasta #383 la custodia sembrada era siempre de UN dia y el vehiculo salia igual con 28: la
     * liberacion no comparaba la cantidad del recibo con los dias. Desde #383 los compara, y la
     * siembra tiene que pagar lo que el vehiculo lleva.
     */
    private static final int DIAS_HASTA_LA_ORDINARIA = 28;

    /** La custodia de {@code dias} dias en un solo recibo: {@code TasaCobrada.cantidad} (#383). */
    private static String cobrarCustodia(long contribuyenteId, int dias) {
        String numero = String.format("001-%07d", SIGUIENTE_RECIBO.incrementAndGet());
        cobros.con(numero, "CUSTODIA", CUSTODIA, ORDINARIA, dias);
        return numero;
    }

    private static String cobrarTasa(long contribuyenteId, String codigo, Dinero importe) {
        // El contribuyente no entra en la respuesta del puerto —`TasaCobrada` no lo publica— y aun
        // asi se recibe: es quien pago, y quitarlo del parametro haria que las llamadas dejaran de
        // decir a quien se le cobro, que es lo que la prueba esta montando.
        String numero = String.format("001-%07d", SIGUIENTE_RECIBO.incrementAndGet());
        cobros.con(numero, codigo, importe, ORDINARIA);
        return numero;
    }

    /** Como si `caja` hubiera registrado la anulacion: un recibo anulado deja de acreditar. */
    private static void anular(String numeroImpreso) {
        cobros.anular(numeroImpreso);
    }

    // ------------------------------------------------------------------
    //  Siembra
    // ------------------------------------------------------------------

    private static void ejecutarComoApp(String sql) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    /** El SQLSTATE del fallo, que es lo que distingue «no tiene privilegio» de «no cumple». */
    private static String estadoSqlDelFallo(SentenciaQueFalla sentencia) {
        try {
            sentencia.ejecutar();
        } catch (SQLException fallo) {
            return fallo.getSQLState();
        }
        return "no fallo";
    }

    @FunctionalInterface
    private interface SentenciaQueFalla {
        void ejecutar() throws SQLException;
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

    /**
     * El conjunto sellado de 2026 <b>con los tres plazos dentro</b>.
     *
     * <p>Los cinco días del descargo, los siete de la resolución ordinaria y los quince del recurso
     * contra la sancionadora entran como <b>dato</b>, no como constantes del programa (regla 5).
     * Que esta prueba tenga que sembrarlos es la demostración: sin ellos, registrar un descargo o
     * notificar una resolución falla; y desde #410 la sancionadora imprime el plazo para
     * impugnarla, así que sin el tercero tampoco se dicta. La cifra del recurso es distinta de la
     * de la ordinaria a propósito: con la misma, leer una por otra no se notaría.
     */
    private static long crearConjuntoConLosPlazos(long municipalidadId) throws SQLException {
        long descargo =
                cargarParametro(
                        "DESCARGO_PAPELETA",
                        "5 DIAS_HABILES",
                        "Reglamento Nacional de Transito, D.S. 016-2009-MTC");
        long ordinaria =
                cargarParametro(
                        "RG_ORDINARIA_CUMPLIMIENTO",
                        "7 DIAS_HABILES",
                        "TUO del Codigo Tributario, D.S. 133-2013-EF");
        long recurso =
                cargarParametro("RG_RECURSO", "15 DIAS_HABILES", "TUO de la Ley 27444, art. 218.2");

        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            long conjunto;
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio,"
                                    + " version) VALUES (?, 2026, 1) RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    conjunto = resultado.getLong(1);
                }
            }
            for (long parametro : new long[] {descargo, ordinaria, recurso}) {
                try (PreparedStatement sentencia =
                        app.prepareStatement(
                                "INSERT INTO conjunto_parametro_detalle_de_prueba (municipalidad_id,"
                                        + " conjunto_id, parametro_id) VALUES (?, ?, ?)")) {
                    sentencia.setLong(1, municipalidadId);
                    sentencia.setLong(2, conjunto);
                    sentencia.setLong(3, parametro);
                    sentencia.executeUpdate();
                }
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE conjunto_parametros_de_prueba SET estado = 'SELLADO',"
                                    + " fecha_sellado = now(), usuario_sellado = 'siembra'"
                                    + " WHERE id = ?")) {
                sentencia.setLong(1, conjunto);
                sentencia.executeUpdate();
            }
            app.commit();
            return conjunto;
        }
    }

    /**
     * El catálogo normativo lo carga <b>su propio rol</b>, no la aplicación ni el dueño del esquema
     * (SoD-1 de REQ-03, política {@code parametro_escritura} de V6). Y va con {@code
     * municipalidad_id NULL}: los dos plazos son norma nacional, no una ordenanza.
     */
    private static long cargarParametro(String clave, String valor, String fuente)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_texto, vigencia_desde, documento_fuente, sellado,"
                                        + " usuario_carga) VALUES (NULL, 'PLAZO', ?, ?,"
                                        + " DATE '2026-01-01', ?, true, 'siembra') RETURNING id")) {
            sentencia.setString(1, clave);
            sentencia.setString(2, valor);
            sentencia.setString(3, fuente);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static long crearContribuyente(String sufijo) {
        return insertar(
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro) VALUES ("
                        + municipalidad
                        + ", 'C-"
                        + sufijo
                        + "', 'DNI', '"
                        + dniDe(sufijo)
                        + "', 'NATURAL', 'SERNAQUE VILLEGAS, DORIS', 'siembra') RETURNING id");
    }

    private static String dniDe(String codigo) {
        return "44" + Math.abs(codigo.hashCode() % 1000000 + 1000000);
    }

    private static void crearCodigo(String codigo) {
        insertar(
                "INSERT INTO codigo_infraccion (municipalidad_id, familia, codigo, descripcion,"
                        + " porcentaje_uit, base_legal, vigencia_desde) VALUES ("
                        + municipalidad
                        + ", 'TRANSITO', '"
                        + codigo
                        + "', 'Infraccion de la prueba', 8.0000, 'D.S. 016-2009-MTC',"
                        + " DATE '2026-01-01') RETURNING id");
    }

    /** Un documento emitido suelto, para las pruebas que insertan por SQL directo. */
    private static long documentoSuelto(String tipo, String numero) {
        return insertar(
                "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                        + " referencia, datos, formato, resumen, fecha_emision, usuario_emision,"
                        + " observacion) VALUES ("
                        + municipalidad
                        + ", '"
                        + tipo
                        + "', '"
                        + numero
                        + "', 2026, 'prueba', CAST('{\"titulo\":\"x\",\"subtitulo\":null,"
                        + "\"aLaFecha\":\"2026-01-01\",\"cabecera\":[],\"tablas\":[],\"pie\":[],"
                        + "\"duplicado\":null}' AS jsonb), 'PDF', repeat('f', 64),"
                        + " DATE '2026-01-01', 'siembra', 'documento de prueba') RETURNING id");
    }

    private static long insertar(String sql) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql);
                    ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                app.commit();
                return id;
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException("No se pudo sembrar: " + sql, excepcion);
        }
    }

    /**
     * El padrón, leído de la base.
     *
     * <p>No es un doble de conveniencia: el nombre y el domicilio del obligado salen impresos en la
     * resolución, y este contexto los pide por la API pública de {@code contribuyentes} (ARQ-01
     * §4). Lo único que esta clase evita es arrastrar el módulo entero a la prueba.
     */
    private static final class PadronDeLaPrueba implements DirectorioDeContribuyentes {

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return jdbc.sql(
                            "SELECT id, codigo_contribuyente, nombre_razon_social, numero_documento"
                                    + " FROM contribuyente WHERE codigo_contribuyente = :codigo")
                    .param("codigo", codigo)
                    .query(PadronDeLaPrueba::mapear)
                    .optional();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            Map<Long, ResumenDeContribuyente> encontrados = new java.util.HashMap<>();
            for (Long id : ids) {
                jdbc.sql(
                                "SELECT id, codigo_contribuyente, nombre_razon_social,"
                                        + " numero_documento FROM contribuyente WHERE id = :id")
                        .param("id", id)
                        .query(PadronDeLaPrueba::mapear)
                        .optional()
                        .ifPresent(resumen -> encontrados.put(resumen.id(), resumen));
            }
            return encontrados;
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.of("AV. JOSE DE LAMA 1180 - SULLANA");
        }

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            // La resolucion no busca por nombre: el obligado sale de la papeleta.
            return List.of();
        }

        private static ResumenDeContribuyente mapear(ResultSet fila, int numero)
                throws SQLException {
            return new ResumenDeContribuyente(
                    fila.getLong("id"),
                    fila.getString("codigo_contribuyente"),
                    fila.getString("nombre_razon_social"),
                    "DNI " + fila.getString("numero_documento"));
        }
    }
}
