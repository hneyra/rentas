package kamayuk.rentas.coactiva.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.LinkedHashMap;
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
import kamayuk.rentas.coactiva.aplicacion.ArancelDeCostasParametrizado;
import kamayuk.rentas.coactiva.aplicacion.CambiarEstadoDelExpediente;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeCostas;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeDeudasCoactivas;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeExpedientes;
import kamayuk.rentas.coactiva.aplicacion.FraccionarEnCoactiva;
import kamayuk.rentas.coactiva.aplicacion.ImportarValoresACoactiva;
import kamayuk.rentas.coactiva.aplicacion.LiquidarCostas;
import kamayuk.rentas.coactiva.aplicacion.PlazosCoactivosParametrizados;
import kamayuk.rentas.coactiva.aplicacion.RegistrarActoCoactivo;
import kamayuk.rentas.coactiva.dominio.CriterioDeExpedientes;
import kamayuk.rentas.coactiva.dominio.CriterioDeLiquidaciones;
import kamayuk.rentas.coactiva.dominio.DeudaDelExpediente;
import kamayuk.rentas.coactiva.dominio.EstadoDeLaLiquidacion;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostas;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostasRepository;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ObligacionDelExpediente;
import kamayuk.rentas.coactiva.dominio.PlantillaDeNumeroDeExpediente;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;
import kamayuk.rentas.coactiva.dominio.TipoDeMedidaCautelar;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.AcogimientoAConvenio;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.aplicacion.AcogimientoAConvenioCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.GeneradorDeCargosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
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
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.rentas.BeneficioRegistrado;
import kamayuk.rentas.rentas.BeneficiosDelContribuyente;
import kamayuk.rentas.tesoreria.AnulacionesDeRecibo;
import kamayuk.rentas.tesoreria.ConvenioCoactivo;
import kamayuk.rentas.tesoreria.FraccionamientoCoactivo;
import kamayuk.rentas.tesoreria.aplicacion.CerrarConvenio;
import kamayuk.rentas.tesoreria.aplicacion.CondicionesParametrizadas;
import kamayuk.rentas.tesoreria.aplicacion.FormalizarConvenio;
import kamayuk.rentas.tesoreria.aplicacion.FraccionamientoCoactivoTesoreria;
import kamayuk.rentas.tesoreria.aplicacion.RegistrarPreconvenio;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.EstadoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.NumeroDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeConvenio;
import kamayuk.rentas.tesoreria.infraestructura.ConvenioRepositoryJdbc;
import kamayuk.rentas.tesoreria.infraestructura.MovimientoDeConvenioRepositoryJdbc;
import kamayuk.rentas.valores.ValoresEnCoactiva;
import kamayuk.rentas.valores.aplicacion.ValoresEnCoactivaValores;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.MovimientoDeValor;
import kamayuk.rentas.valores.dominio.Notificacion;
import kamayuk.rentas.valores.dominio.TipoDeMovimiento;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import kamayuk.rentas.valores.infraestructura.MovimientoDeValorRepositoryJdbc;
import kamayuk.rentas.valores.infraestructura.NotificacionRepositoryJdbc;
import kamayuk.rentas.valores.infraestructura.ValorRepositoryJdbc;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * #42 — Las costas del procedimiento y el fraccionamiento coactivo contra PostgreSQL de verdad
 * (V35), conectado como {@code sgtm_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>Que la costa sea un cargo del libro y no un campo del expediente.</b> Se liquida, y se
 *       comprueba que aparece un asiento de concepto {@code GASTO} en fase {@code COACTIVA} —leido
 *       de {@code cuenta_corriente_asiento} por SQL directo— y que {@code
 *       DeudaDelExpediente.costas} lo <b>relee</b> a la fecha que se pida. Contra un doble esto
 *       solo probaria que el doble recuerda lo que se le dijo.
 *   <li><b>Que la REC la sume sin cambiar su modelo.</b> La REC-2 se dicta despues de liquidar y su
 *       papel tiene que decir «Costas y gastos del procedimiento» con la cifra y un total exigible
 *       que la incluya. El modelo de #41 ya tenia la fila; lo que #42 cambia es que deja de valer
 *       cero.
 *   <li><b>Que el arancel no este en el codigo.</b> Sin el parametro sellado, liquidar falla
 *       nombrando la llave. Es lo que ocurre hoy con D-02c abierta (#193), y esta prueba lo
 *       comprueba en los dos sentidos: con parametro liquida, sin parametro no.
 *   <li><b>Que el fraccionamiento coactivo sea el mecanismo de #35 y el quiebre devuelva a
 *       COACTIVA.</b> Asiento por asiento, sobre un expediente de verdad: la deuda sale de fase
 *       {@code COACTIVA}, pasa a {@code CONVENIO} al cobrarse la inicial, y al quebrar vuelve a
 *       {@code COACTIVA} —no a {@code ORDINARIA}—.
 *   <li><b>Que un acto no se liquide dos veces.</b> No es un {@code if}: es {@code costa_acto_uq}
 *       (V35), y se comprueba con diez hilos a la vez, que es como se descubre que la comprobacion
 *       previa en Java no basta.
 *   <li><b>Que dos expedientes del mismo obligado no compartan obligacion de costas.</b> Es la
 *       clave primaria de {@code costa_obligacion} (V35 §3), y sin ella la columna «Costas S/»
 *       diria lo mismo en las dos filas de la grilla sin que nada fallara.
 *   <li><b>Que {@code sgtm_app} no pueda editar ni borrar una liquidacion.</b> Es el {@code REVOKE}
 *       de V35, y se comprueba intentandolo por SQL directo.
 *   <li><b>Que RLS aisle la liquidacion</b>: desde otra municipalidad no existe.
 * </ul>
 */
@DisplayName("#42 — Costas procesales y fraccionamiento coactivo contra PostgreSQL")
class CostasYFraccionamientoJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate FECHA_DEL_CARGO = LocalDate.of(2026, 1, 2);
    private static final LocalDate EMISION = LocalDate.of(2026, 3, 2);
    private static final LocalDate DILIGENCIA_DEL_VALOR = LocalDate.of(2026, 4, 3);
    private static final LocalDate EXIGIBLE_EL_VALOR = LocalDate.of(2026, 5, 5);
    private static final LocalDate PASE = LocalDate.of(2026, 6, 1);
    private static final LocalDate IMPORTACION = LocalDate.of(2026, 6, 15);
    private static final LocalDate REC1 = LocalDate.of(2026, 6, 16);
    private static final LocalDate DILIGENCIA_REC1 = LocalDate.of(2026, 6, 17);

    /** Los siete dias habiles del art. 14.1, contados desde la diligencia del miercoles 17. */
    private static final LocalDate REC2_DESDE = LocalDate.of(2026, 6, 30);

    /** El dia en que se liquidan las costas, ya con la REC-1 notificada. */
    private static final LocalDate LIQUIDACION = LocalDate.of(2026, 6, 18);

    private static final Dinero PREDIAL = Dinero.de("500.00");

    /**
     * El arancel sembrado para la REC-1 y para la REC-2.
     *
     * <p>Son datos <b>de la prueba</b>, no del programa: se cargan en {@code parametro_tributario}
     * y el codigo de produccion los lee de ahi. Que esta prueba tenga que sembrarlos es la
     * demostracion de la regla 5: sin parametro, liquidar falla.
     */
    private static final Dinero ARANCEL_REC1 = Dinero.de("35.00");

    private static final Dinero ARANCEL_REC2 = Dinero.de("50.00");

    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-18T09:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static long conjuntoId;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static ValorRepositoryJdbc valores;
    private static NotificacionRepositoryJdbc notificacionesDeValor;
    private static MovimientoDeValorRepositoryJdbc movimientosDeValor;
    private static ExpedienteRepositoryJdbc expedientes;
    private static MovimientoDelExpedienteRepositoryJdbc movimientos;
    private static ActoCoactivoRepositoryJdbc actos;
    private static LiquidacionDeCostasRepositoryJdbc liquidaciones;
    private static RegistrarAsiento registrarAsiento;
    private static ConvenioRepositoryJdbc convenios;
    private static MovimientoDeConvenioRepositoryJdbc movimientosDeConvenio;

    private static ImportarValoresACoactiva importar;
    private static ConsultaDeExpedientes consulta;
    private static RegistrarActoCoactivo dictar;
    private static kamayuk.rentas.coactiva.aplicacion.NotificarActoCoactivo notificar;
    private static LiquidarCostas liquidar;
    private static ConsultaDeCostas consultaDeCostas;
    private static ConsultaDeDeudasCoactivas consultaDeDeudas;
    private static FraccionarEnCoactiva fraccionar;
    private static FormalizarConvenio formalizar;
    private static CerrarConvenio cerrar;

    /**
     * De donde salen los identificadores de recibo, ahora que `recibo` no esta en esta base (P5D).
     *
     * <p>Desde `V7` no hay clave foranea que los valide: son numeros de `caja`. Que entren es parte
     * de lo que se verifica.
     */
    private static final java.util.concurrent.atomic.AtomicInteger SIGUIENTE_RECIBO =
            new java.util.concurrent.atomic.AtomicInteger(4_200);

    /** El mismo caso de uso, con un lector de parametros que NO tiene el arancel dentro. */
    private static LiquidarCostas liquidarSinArancel;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("250401", "Municipalidad de las costas");
        otraMunicipalidad = crearMunicipalidad("250402", "Municipalidad vecina de #42");
        conjuntoId = crearConjuntoSellado(municipalidad);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        valores = new ValorRepositoryJdbc(jdbc);
        notificacionesDeValor = new NotificacionRepositoryJdbc(jdbc);
        movimientosDeValor = new MovimientoDeValorRepositoryJdbc(jdbc);
        expedientes = new ExpedienteRepositoryJdbc(jdbc);
        movimientos = new MovimientoDelExpedienteRepositoryJdbc(jdbc);
        actos = new ActoCoactivoRepositoryJdbc(jdbc);
        liquidaciones = new LiquidacionDeCostasRepositoryJdbc(jdbc);
        convenios = new ConvenioRepositoryJdbc(jdbc);
        movimientosDeConvenio = new MovimientoDeConvenioRepositoryJdbc(jdbc);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        registrarAsiento = new RegistrarAsiento(asientos, saldos, auditoria, RELOJ);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);
        ConsultaDeDeudaPublica deuda =
                envolver(
                        new ConsultaDeDeudaCuentaCorriente(
                                envolver(
                                        new ConsultarDeuda(
                                                asientos, saldos, calculo, redondeo, RELOJ))));

        ValoresEnCoactiva puerto =
                envolver(new ValoresEnCoactivaValores(valores, movimientosDeValor));

        importar =
                envolver(
                        new ImportarValoresACoactiva(
                                expedientes, movimientos, puerto, auditoria, RELOJ));
        consulta =
                envolver(
                        new ConsultaDeExpedientes(
                                expedientes, movimientos, puerto, deuda, liquidaciones));

        LectorDeParametrosSellados lector =
                envolver(new LectorDeParametrosSellados(new ParametrosRepositoryJdbc(jdbc)));
        PlazosCoactivosParametrizados plazos = new PlazosCoactivosParametrizados(lector);
        ArancelDeCostasParametrizado aranceles = new ArancelDeCostasParametrizado(lector);

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

        DirectorioDeContribuyentes padron = new PadronDeLaPrueba();

        dictar =
                envolver(
                        new RegistrarActoCoactivo(
                                expedientes,
                                movimientos,
                                actos,
                                new NotificacionCoactivaRepositoryJdbc(jdbc),
                                consulta,
                                puerto,
                                padron,
                                plazos,
                                documentos,
                                auditoria,
                                RELOJ));
        notificar =
                envolver(
                        new kamayuk.rentas.coactiva.aplicacion.NotificarActoCoactivo(
                                actos,
                                new NotificacionCoactivaRepositoryJdbc(jdbc),
                                expedientes,
                                movimientos,
                                plazos,
                                auditoria,
                                RELOJ));

        GeneradorDeCargos cargos = new GeneradorDeCargosCuentaCorriente(envolver(registrarAsiento));

        liquidar =
                envolver(
                        new LiquidarCostas(
                                expedientes,
                                movimientos,
                                actos,
                                liquidaciones,
                                aranceles,
                                cargos,
                                auditoria,
                                RELOJ));
        liquidarSinArancel =
                envolver(
                        new LiquidarCostas(
                                expedientes,
                                movimientos,
                                actos,
                                liquidaciones,
                                new ArancelDeCostasParametrizado(new SinArancelDeCostas()),
                                cargos,
                                auditoria,
                                RELOJ));

        consultaDeCostas = envolver(new ConsultaDeCostas(liquidaciones, expedientes, deuda));

        // El fraccionamiento coactivo: el MISMO mecanismo de #35, invocado por su puerto.
        AcogimientoAConvenio acogimiento =
                envolver(
                        new AcogimientoAConvenioCuentaCorriente(
                                asientos, saldos, envolver(registrarAsiento), calculo, redondeo));
        RegistrarPreconvenio preconvenios =
                envolver(
                        new RegistrarPreconvenio(
                                convenios,
                                acogimiento,
                                new CondicionesParametrizadas(lector),
                                auditoria,
                                RELOJ));
        FraccionamientoCoactivo puertoDeConvenios =
                envolver(new FraccionamientoCoactivoTesoreria(preconvenios));
        fraccionar =
                envolver(new FraccionarEnCoactiva(expedientes, movimientos, puertoDeConvenios));

        consultaDeDeudas =
                envolver(
                        new ConsultaDeDeudasCoactivas(
                                consulta, expedientes, actos, puerto, new BeneficiosDeLaPrueba()));

        // P5D: la formalizacion se llama directamente. `CobrarDeuda`, `AbrirCaja` y las tablas
        // de la ventanilla se fueron a `caja` con `V7`; lo que en produccion llamara a esto es el
        // evento `PagoRegistrado` (ADR-0026 §3) con el `reciboId` que la caja acaba de emitir.
        // Lo que esta prueba mide no cambia: que la fase de la deuda vaya a CONVENIO al
        // formalizar y vuelva a COACTIVA —no a ORDINARIA— al quebrar.
        formalizar =
                envolver(
                        new FormalizarConvenio(
                                convenios, movimientosDeConvenio, acogimiento, auditoria, RELOJ));
        // Este quiebre no pasa por la guarda del recibo —quebrar no exige anularlo, porque ese
        // dinero SI entro— asi que el puerto no llega a preguntarse nada. Se pasa uno que lanza
        // si alguien lo pregunta, en vez de uno que conteste que si: contestar que si dejaria
        // anular un convenio con su inicial cobrada y viva.
        AnulacionesDeRecibo sinPreguntar =
                reciboId -> {
                    throw new AssertionError(
                            "Esta prueba no anula ningun convenio formalizado: si se pregunta por"
                                    + " el recibo "
                                    + reciboId
                                    + ", el escenario cambio");
                };
        cerrar =
                envolver(
                        new CerrarConvenio(
                                convenios,
                                movimientosDeConvenio,
                                sinPreguntar,
                                acogimiento,
                                preconvenios,
                                auditoria,
                                RELOJ));

        // Ni `area` ni `caja` se siembran ya: las dos se fueron con `V7`.
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
        OrigenContext.fijar(new Origen("ejecutor.coactivo", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ==================================================================

    @Nested
    @DisplayName("La costa es un cargo del libro, no un campo del expediente")
    class DeLaCosta {

        @Test
        @DisplayName(
                "liquidar asienta un GASTO en fase COACTIVA, y la deuda del expediente lo relee")
        void liquidarAsientaUnGastoEnCoactiva() {
            String expediente = expedienteConRec1("COSTA-1");

            LiquidacionDeCostas liquidacion = liquidarTodo(expediente);

            assertThat(liquidacion.numero()).startsWith("LC-2026-");
            assertThat(liquidacion.total())
                    .as("el importe sale del arancel sellado, no de la peticion (regla 5)")
                    .isEqualTo(ARANCEL_REC1);
            assertThat(liquidacion.conjuntoId())
                    .as("y queda dicho de que conjunto salio (ARQ-09 §3)")
                    .isEqualTo(conjuntoId);
            assertThat(liquidacion.costas())
                    .singleElement()
                    .satisfies(
                            costa -> {
                                assertThat(costa.actoTipo()).isEqualTo(TipoDeActoCoactivo.REC1);
                                assertThat(costa.arancelFuente())
                                        .as("la fila explica de donde salio su cifra")
                                        .isEqualTo("ARANCEL_COSTA:REC1");
                            });

            // El cargo, leido del libro por SQL directo: es ahi donde la costa vive.
            List<Map<String, Object>> asientos = asientosDelDocumento(liquidacion.numero());
            assertThat(asientos)
                    .as("un solo asiento: el cargo por el total de la liquidacion")
                    .hasSize(1);
            assertThat(asientos.get(0))
                    .containsEntry("concepto", "GASTO")
                    .containsEntry("tipo", "CARGO ")
                    .containsEntry("fase", "COACTIVA")
                    .containsEntry("tributo", LiquidacionDeCostas.TRIBUTO)
                    .containsEntry("referencia_externa", expediente);

            // Y `DeudaDelExpediente` lo RELEE: no hay ninguna columna de costas.
            DeudaDelExpediente conCostas = deudaDe(expediente, LIQUIDACION);
            assertThat(conCostas.costas()).isEqualTo(ARANCEL_REC1);
            assertThat(conCostas.materiaDeCobranza())
                    .as("las costas no se cuelan en las cuatro partes del tributo")
                    .isEqualTo(PREDIAL);
            assertThat(conCostas.total()).isEqualTo(PREDIAL.mas(ARANCEL_REC1));
            assertThat(conCostas.actualizadaA()).isEqualTo(LIQUIDACION);
        }

        @Test
        @DisplayName("antes de la liquidacion las costas son cero, y con la misma clase")
        void antesDeLiquidarLasCostasSonCero() {
            String expediente = expedienteConRec1("COSTA-2");

            DeudaDelExpediente sinCostas = deudaDe(expediente, LIQUIDACION);
            assertThat(sinCostas.costas()).isEqualTo(Dinero.de("0.00"));
            assertThat(sinCostas.total())
                    .as("agregar el sumando no cambia ninguna cifra existente")
                    .isEqualTo(PREDIAL);
        }

        @Test
        @DisplayName("la costa se lee a la fecha que se pida: antes de liquidarse no existe")
        void laCostaSeLeeALaFecha() {
            String expediente = expedienteConRec1("COSTA-3");
            liquidarTodo(expediente);

            assertThat(deudaDe(expediente, LIQUIDACION.minusDays(1)).costas())
                    .as("el dia anterior a la liquidacion todavia no habia costas (regla 9)")
                    .isEqualTo(Dinero.de("0.00"));
            assertThat(deudaDe(expediente, LIQUIDACION).costas()).isEqualTo(ARANCEL_REC1);
        }

        @Test
        @DisplayName(
                "la REC-2 imprime las costas y su total exigible, con el modelo de #41 intacto")
        void laRecImprimeLasCostas() {
            String expediente = expedienteConRec1("COSTA-4");
            notificarLaRec1(expediente);
            liquidarTodo(expediente);

            RegistrarActoCoactivo.ActoDictado rec2 =
                    dictarActo(
                            expediente,
                            TipoDeActoCoactivo.REC2,
                            REC2_DESDE,
                            TipoDeMedidaCautelar.RETENCION);

            assertThat(rec2.deuda().costas()).isEqualTo(ARANCEL_REC1);
            assertThat(rec2.deuda().total()).isEqualTo(PREDIAL.mas(ARANCEL_REC1));

            String papel = new String(rec2.emision().contenido(), StandardCharsets.ISO_8859_1);
            assertThat(papel)
                    .as("la fila de costas del modelo de #41 deja de valer cero")
                    .contains("Costas y gastos del procedimiento")
                    .contains("535.00")
                    .contains("Deuda actualizada al " + REC2_DESDE);
        }

        @Test
        @DisplayName(
                "sin NINGUN arancel publicado, liquidar falla nombrando la llave (#634, D-02c)")
        void sinArancelNoSeLiquida() {
            String expediente = expedienteConRec1("COSTA-5");

            assertThatThrownBy(
                            () ->
                                    liquidarSinArancel.liquidar(
                                            LiquidarCostas.Peticion.deTodoElExpediente(
                                                    expediente, LIQUIDACION),
                                            PORQUE))
                    .as(
                            "es lo que ocurre hoy: el arancel es de ordenanza local y #193 esta"
                                    + " bloqueado. Hasta #634 esta rama contestaba «no tiene"
                                    + " ningun acto pendiente de liquidar», que se lee como «no"
                                    + " hay nada que cobrar» y no como «falta publicar una cifra»")
                    .isInstanceOf(ArancelDeCostasParametrizado.ArancelSinParametrizar.class)
                    .hasMessageContaining("ARANCEL_COSTA:REC1")
                    .hasMessageContaining("#193");

            assertThatThrownBy(
                            () ->
                                    liquidarSinArancel.liquidar(
                                            new LiquidarCostas.Peticion(
                                                    expediente,
                                                    LIQUIDACION,
                                                    Set.of(idDeLaRec1(expediente))),
                                            PORQUE))
                    .as("y pedido el acto expresamente, el mensaje dice QUE llave falta")
                    .isInstanceOf(ArancelDeCostasParametrizado.ArancelSinParametrizar.class)
                    .hasMessageContaining("ARANCEL_COSTA:REC1")
                    .hasMessageContaining("#193");

            assertThat(deudaDe(expediente, LIQUIDACION).costas())
                    .as("y no se asento nada: mejor no liquidar que liquidar una cifra inventada")
                    .isEqualTo(Dinero.de("0.00"));
        }

        /**
         * El contraste que impide «arreglarlo» convirtiendo todo en {@code ArancelSinParametrizar}.
         *
         * <p>Aqui la ordenanza SI esta publicada —el conjunto sellado de la prueba trae {@code
         * ARANCEL_COSTA:REC1} y {@code ARANCEL_COSTA:REC2}— y lo que no tarifa es el acta de
         * embargo. Eso es una <b>decision de la ordenanza</b>, no un dato que falte, y el mensaje
         * no puede pedir que se publique nada.
         */
        @Test
        @DisplayName("con arancel publicado, el acto que la ordenanza no tarifa sigue sin llave")
        void loQueLaOrdenanzaNoTarifaSigueSiendoSinActos() {
            String expediente = expedienteConRec1("COSTA-10");
            liquidarTodo(expediente);
            dictarActo(expediente, TipoDeActoCoactivo.EMBARGO, LIQUIDACION, null);

            assertThatThrownBy(() -> liquidarTodo(expediente))
                    .as(
                            "queda un acto pendiente y el arancel no lo tarifa: no falta ninguna"
                                    + " cifra que publicar")
                    .isInstanceOf(LiquidarCostas.SinActosQueLiquidar.class)
                    .hasMessageContaining("no tiene ningun acto pendiente de liquidar")
                    .hasMessageNotContaining("ARANCEL_COSTA");
        }

        /**
         * Y el otro contraste: la comprobacion mira los actos <b>pendientes</b>, no el arancel.
         *
         * <p>Un expediente cuyos actos ya se liquidaron todos no gana un 422 nuevo porque la
         * ordenanza haya dejado de estar cargada: ahi no hay nada que liquidar y nunca lo hubo.
         */
        @Test
        @DisplayName("ya liquidado del todo, sin arancel sigue siendo «sin actos» y no una llave")
        void yaLiquidadoDelTodoNoEsFaltaDeArancel() {
            String expediente = expedienteConRec1("COSTA-11");
            liquidarTodo(expediente);

            assertThatThrownBy(
                            () ->
                                    liquidarSinArancel.liquidar(
                                            LiquidarCostas.Peticion.deTodoElExpediente(
                                                    expediente, LIQUIDACION),
                                            PORQUE))
                    .isInstanceOf(LiquidarCostas.SinActosQueLiquidar.class)
                    .hasMessageNotContaining("ARANCEL_COSTA");
        }

        @Test
        @DisplayName("un expediente concluido no devenga costas nuevas")
        void unExpedienteConcluidoNoSeLiquida() {
            String expediente = expedienteConRec1("COSTA-6");
            concluir(expediente);

            assertThatThrownBy(() -> liquidarTodo(expediente))
                    .isInstanceOf(CambiarEstadoDelExpediente.ExpedienteConcluido.class);
        }

        @Test
        @DisplayName("liquidar dos veces el mismo expediente no encuentra nada nuevo que liquidar")
        void liquidarDosVecesNoDuplica() {
            String expediente = expedienteConRec1("COSTA-7");
            liquidarTodo(expediente);

            assertThatThrownBy(() -> liquidarTodo(expediente))
                    .isInstanceOf(LiquidarCostas.SinActosQueLiquidar.class);
            assertThat(deudaDe(expediente, LIQUIDACION).costas()).isEqualTo(ARANCEL_REC1);
        }

        @Test
        @DisplayName("un acto de otro expediente no se liquida aqui")
        void unActoAjenoNoSeLiquida() {
            String propio = expedienteConRec1("COSTA-8");
            String ajeno = expedienteConRec1("COSTA-9");

            assertThatThrownBy(
                            () ->
                                    liquidar.liquidar(
                                            new LiquidarCostas.Peticion(
                                                    propio, LIQUIDACION, Set.of(idDeLaRec1(ajeno))),
                                            PORQUE))
                    .isInstanceOf(LiquidarCostas.ActoAjeno.class)
                    .hasMessageContaining("mezclaria dos procedimientos");
        }
    }

    /**
     * #426 — La deuda del expediente <b>obligación por obligación</b>.
     *
     * <p>Es la lectura de la que {@code fraccionamiento_coactivo} saca sus filas: su cuerpo pide
     * {@code tributo}, {@code ejercicio} y {@code predioId}/{@code vehiculoId} una a una, y una
     * suma no los tiene. Lo que estas pruebas fijan es que sale de la <b>misma</b> composición que
     * el total —si divergen, la grilla y la REC-2 dicen cifras distintas de la misma carpeta—.
     */
    @Nested
    @DisplayName("#426 — La deuda, obligacion por obligacion")
    class PorObligacion {

        @Test
        @DisplayName("una fila por obligacion, con la unidad que el fraccionamiento pide")
        void unaFilaPorObligacion() {
            String expediente = expedienteConRec1("OBLIG-1");

            ConsultaDeExpedientes.DeudaPorObligacion deuda = obligacionesDe(expediente);

            assertThat(deuda.obligaciones())
                    .as("el expediente tiene una sola obligacion de tributo")
                    .hasSize(1);
            ObligacionDelExpediente unica = deuda.obligaciones().get(0);
            assertThat(unica.tributo()).isEqualTo("PREDIAL");
            assertThat(unica.ejercicio().valor()).isEqualTo(2026);
            assertThat(unica.predioId())
                    .as("este escenario asienta la deuda SIN unidad, y la fila lo dice tal cual")
                    .isNull();
            assertThat(unica.esCosta()).isFalse();
            assertThat(unica.total()).isEqualTo(PREDIAL);
            assertThat(unica.actualizadaA()).isEqualTo(LIQUIDACION);
        }

        /**
         * El total no es una segunda cuenta: es la suma de las filas que se publican.
         *
         * <p>Lo que se compara es cifra a cifra contra {@link ConsultaDeExpedientes#deudaDe}, que
         * es lo que imprime la REC-2. Si las dos lecturas se compusieran por separado, esta prueba
         * es la que lo diría.
         */
        @Test
        @DisplayName("el total es exactamente la suma de las filas, y coincide con el de la REC-2")
        void elTotalEsLaSumaDeLasFilas() {
            String expediente = expedienteConRec1("OBLIG-2");
            liquidarTodo(expediente);

            ConsultaDeExpedientes.DeudaPorObligacion porObligacion = obligacionesDe(expediente);
            DeudaDelExpediente delExpediente = deudaDe(expediente, LIQUIDACION);

            assertThat(porObligacion.total().materiaDeCobranza())
                    .isEqualTo(delExpediente.materiaDeCobranza());
            assertThat(porObligacion.total().costas()).isEqualTo(delExpediente.costas());
            assertThat(porObligacion.total().total()).isEqualTo(delExpediente.total());

            Dinero sumaDeLasFilas =
                    porObligacion.obligaciones().stream()
                            .map(ObligacionDelExpediente::total)
                            .reduce(Dinero.de("0.00"), Dinero::mas);
            assertThat(sumaDeLasFilas)
                    .as("sumar lo publicado da el total publicado: no hay dos cuentas")
                    .isEqualTo(delExpediente.total());
        }

        /**
         * La costa viaja marcada, no escondida.
         *
         * <p>Se cobra igual, pero no se acoge a un fraccionamiento como una cuota más del predial:
         * quien lee la grilla tiene que poder distinguirla, y quien la marca tiene que saber qué
         * marcó.
         */
        @Test
        @DisplayName("la costa aparece como fila propia y marcada, no sumada al tributo")
        void laCostaApareceMarcada() {
            String expediente = expedienteConRec1("OBLIG-3");
            liquidarTodo(expediente);

            ConsultaDeExpedientes.DeudaPorObligacion deuda = obligacionesDe(expediente);

            assertThat(deuda.obligaciones()).hasSize(2);
            ObligacionDelExpediente costa =
                    deuda.obligaciones().stream()
                            .filter(ObligacionDelExpediente::esCosta)
                            .findFirst()
                            .orElseThrow();
            assertThat(costa.total()).isEqualTo(ARANCEL_REC1);
            assertThat(costa.predioId())
                    .as("una costa no es de un predio: es del procedimiento")
                    .isNull();
            assertThat(costa.vehiculoId()).isNull();

            ObligacionDelExpediente tributo =
                    deuda.obligaciones().stream()
                            .filter(fila -> !fila.esCosta())
                            .findFirst()
                            .orElseThrow();
            assertThat(tributo.total())
                    .as("y no se cuela en las cuatro partes del tributo")
                    .isEqualTo(PREDIAL);
        }

        @Test
        @DisplayName("las cifras salen a la fecha que se pida, no a hoy")
        void lasCifrasSalenALaFechaQueSePida() {
            String expediente = expedienteConRec1("OBLIG-4");
            liquidarTodo(expediente);

            ConsultaDeExpedientes.DeudaPorObligacion antes =
                    enTransaccion(
                                    () ->
                                            consulta.obligacionesDe(
                                                    expediente, LIQUIDACION.minusDays(1)))
                            .orElseThrow();

            assertThat(antes.aLaFecha()).isEqualTo(LIQUIDACION.minusDays(1));
            assertThat(antes.total().costas())
                    .as("la costa se liquido al dia siguiente: ese dia no sumaba nada")
                    .isEqualTo(Dinero.de("0.00"));
            assertThat(antes.obligaciones())
                    .filteredOn(ObligacionDelExpediente::esCosta)
                    .allSatisfy(
                            fila ->
                                    assertThat(fila.total())
                                            .as("y si su obligacion ya figura, figura en cero")
                                            .isEqualTo(Dinero.de("0.00")));
        }

        @Test
        @DisplayName("un expediente que no existe no devuelve una deuda vacia: no devuelve nada")
        void unExpedienteQueNoExisteNoDevuelveNada() {
            assertThat(enTransaccion(() -> consulta.obligacionesDe("EXP-2026-999999", LIQUIDACION)))
                    .isEmpty();
        }

        @Test
        @DisplayName("desde otra municipalidad el expediente no existe (RLS)")
        void desdeOtraMunicipalidadNoExiste() {
            String expediente = expedienteConRec1("OBLIG-5");

            assertThat(
                            enTransaccionDe(
                                    otraMunicipalidad,
                                    () -> consulta.obligacionesDe(expediente, LIQUIDACION)))
                    .isEmpty();
        }

        /**
         * El caso que este issue existe para servir: la fila trae la unidad.
         *
         * <p>{@code PeticionDeObligacionAcogida} pide {@code predioId}/{@code vehiculoId} por fila,
         * y sin esta lectura la pantalla no tenía de dónde sacarlos. La unidad va en los dos sitios
         * del escenario —el asiento del libro y el detalle del valor—, que es como llega a existir
         * una obligación con unidad de verdad.
         */
        @Test
        @DisplayName("cuando la obligacion tiene unidad, la fila la trae: es lo que se acoge")
        void laUnidadViajaCuandoLaObligacionLaTiene() {
            String expediente = expedienteConRec1("OBLIG-6", 7777L);

            ConsultaDeExpedientes.DeudaPorObligacion deuda = obligacionesDe(expediente);

            assertThat(deuda.obligaciones()).hasSize(1);
            ObligacionDelExpediente unica = deuda.obligaciones().get(0);
            assertThat(unica.predioId()).isEqualTo(7777L);
            assertThat(unica.vehiculoId()).isNull();
            assertThat(unica.tributo()).isEqualTo("PREDIAL");
            assertThat(unica.total()).isEqualTo(PREDIAL);
        }

        private ConsultaDeExpedientes.DeudaPorObligacion obligacionesDe(String expediente) {
            return enTransaccion(() -> consulta.obligacionesDe(expediente, LIQUIDACION))
                    .orElseThrow();
        }
    }

    @Nested
    @DisplayName("Lo que decide la base, y no un if")
    class DeLaBase {

        @Test
        @DisplayName("diez hilos liquidando el mismo acto producen UNA costa, no diez")
        void diezHilosProducenUnaCosta() throws Exception {
            String expediente = expedienteConRec1("CONC-1");
            long acto = idDeLaRec1(expediente);

            int hilos = 10;
            CountDownLatch salida = new CountDownLatch(1);
            AtomicInteger logradas = new AtomicInteger();
            List<Callable<Void>> intentos = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                intentos.add(
                        () -> {
                            TenantContext.fijar(new MunicipalidadId(municipalidad));
                            OrigenContext.fijar(new Origen("ejecutor.coactivo", null, null));
                            salida.await(10, TimeUnit.SECONDS);
                            try {
                                liquidar.liquidar(
                                        new LiquidarCostas.Peticion(
                                                expediente, LIQUIDACION, Set.of(acto)),
                                        PORQUE);
                                logradas.incrementAndGet();
                            } catch (LiquidacionDeCostasRepository.ActoYaLiquidado
                                    | LiquidarCostas.SinActosQueLiquidar
                                    | org.springframework.dao.DataAccessException rechazado) {
                                // Las tres formas en que pierde el que llega segundo: el choque
                                // traducido contra costa_acto_uq, la comprobacion previa que ya
                                // ve el acto liquidado, y lo que el motor devuelva si dos
                                // transacciones se pisan. Cualquier otra sube y rompe la prueba.
                            }
                            return null;
                        });
            }

            ExecutorService piscina = Executors.newFixedThreadPool(hilos);
            try {
                List<Future<Void>> futuros = new ArrayList<>();
                for (Callable<Void> intento : intentos) {
                    futuros.add(piscina.submit(intento));
                }
                salida.countDown();
                for (Future<Void> futuro : futuros) {
                    futuro.get(30, TimeUnit.SECONDS);
                }
            } finally {
                piscina.shutdownNow();
            }

            assertThat(logradas.get())
                    .as("una sola liquidacion gana; sin costa_acto_uq ganarian varias")
                    .isEqualTo(1);
            assertThat(cuantasCostasDe(expediente)).isEqualTo(1);
            assertThat(deudaDe(expediente, LIQUIDACION).costas())
                    .as("y el obligado paga la costa de su REC una vez, no diez")
                    .isEqualTo(ARANCEL_REC1);
        }

        @Test
        @DisplayName(
                "dos expedientes del mismo obligado no comparten obligacion de costas: se rechaza")
        void dosExpedientesNoComparten() {
            long titular = contribuyenteConDeuda("OBLI-1");
            String primero = expedienteDe(titular, "OBLI-1-A");
            dictarActo(primero, TipoDeActoCoactivo.REC1, REC1, null);
            liquidarTodo(primero);

            // Un segundo valor del mismo obligado abre un segundo expediente.
            String segundo = expedienteDe(titular, "OBLI-1-B");
            dictarActo(segundo, TipoDeActoCoactivo.REC1, REC1, null);

            assertThatThrownBy(() -> liquidarTodo(segundo))
                    .as(
                            "el libro no distingue expedientes en la clave de una obligacion:"
                                    + " compartirla dejaria la columna «Costas S/» diciendo lo"
                                    + " mismo en las dos filas")
                    .isInstanceOf(LiquidacionDeCostasRepository.ObligacionDeOtroExpediente.class)
                    .hasMessageContaining("ya son del expediente");

            assertThat(deudaDe(segundo, LIQUIDACION).costas())
                    .as("y el segundo expediente no se queda con las costas del primero")
                    .isEqualTo(Dinero.de("0.00"));
        }

        @Test
        @DisplayName(
                "sgtm_app no puede editar ni borrar una liquidacion, su linea ni su obligacion")
        void noSePuedeEditarUnaLiquidacion() {
            String expediente = expedienteConRec1("PRIV-1");
            LiquidacionDeCostas liquidacion = liquidarTodo(expediente);
            long id = liquidacion.identificador();

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE liquidacion_costas SET total = 1"
                                                            + " WHERE id = "
                                                            + id)))
                    .as("42501 es «no tiene privilegio», no un CHECK que casualmente lo pare")
                    .isEqualTo("42501");
            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE costa_procesal SET monto = 1"
                                                            + " WHERE liquidacion_id = "
                                                            + id)))
                    .isEqualTo("42501");
            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "DELETE FROM costa_procesal"
                                                            + " WHERE liquidacion_id = "
                                                            + id)))
                    .isEqualTo("42501");
            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE costa_obligacion SET expediente_id = 1"
                                                            + " WHERE tributo = 'COSTAS"
                                                            + " PROCESALES'")))
                    .as("mudar las costas de un procedimiento a otro tampoco")
                    .isEqualTo("42501");

            assertThat(deudaDe(expediente, LIQUIDACION).costas()).isEqualTo(ARANCEL_REC1);
        }

        @Test
        @DisplayName("desde otra municipalidad la liquidacion no existe (RLS)")
        void desdeOtraMunicipalidadNoExiste() {
            String expediente = expedienteConRec1("RLS-1");
            LiquidacionDeCostas liquidacion = liquidarTodo(expediente);

            assertThat(enTransaccion(() -> liquidaciones.porNumero(liquidacion.numero())))
                    .isPresent();
            assertThat(
                            enTransaccionDe(
                                    otraMunicipalidad,
                                    () -> liquidaciones.porNumero(liquidacion.numero())))
                    .as("la politica de V35 la filtra: desde la vecina no hay tal liquidacion")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("El fraccionamiento coactivo: el mecanismo de #35, y el quiebre vuelve a COACTIVA")
    class DelFraccionamiento {

        @Test
        @DisplayName(
                "acoger mueve a CONVENIO, y quebrar devuelve a COACTIVA —no a ORDINARIA—, asiento"
                        + " por asiento")
        void elQuiebreDevuelveACoactiva() {
            long titular = contribuyenteConDeuda("FRAC-1");
            String expediente = expedienteDe(titular, "FRAC-1");
            enCoactiva(titular);

            assertThat(faseDe(titular, "PREDIAL"))
                    .as("la deuda esta en coactiva antes de fraccionar")
                    .isEqualTo(Fase.COACTIVA);
            Map<String, Dinero> netoAntes = netearPorFaseYConcepto(titular);

            ConvenioCoactivo convenio = fraccionarTodo(expediente, titular);

            assertThat(convenio.tipo()).isEqualTo(TipoDeConvenio.COACTIVO.name());
            assertThat(convenio.estado())
                    .as("sin cuota inicial cobrada no hay convenio (criterio de #35)")
                    .isEqualTo(EstadoDeConvenio.PRECONVENIO.name());
            assertThat(convenio.deudaAcogida())
                    .allSatisfy(
                            cuota ->
                                    assertThat(cuota.faseOrigen())
                                            .as("la fase de origen viaja: es a donde volvera")
                                            .isEqualTo("COACTIVA"));
            assertThat(faseDe(titular, "PREDIAL"))
                    .as("un preconvenio no acoge nada: la deuda sigue en coactiva")
                    .isEqualTo(Fase.COACTIVA);

            Convenio guardado = porNumero(convenio.numero());
            formalizarLaInicial(guardado);
            assertThat(faseDe(titular, "PREDIAL"))
                    .as("cobrada la inicial, la deuda pasa a fase de convenio")
                    .isEqualTo(Fase.CONVENIO);

            CerrarConvenio.Cerrado cerrado = quebrar(guardado);

            assertThat(faseDe(titular, "PREDIAL"))
                    .as(
                            "y al quebrar vuelve a COACTIVA, no a ORDINARIA: el expediente sigue"
                                    + " vivo")
                    .isEqualTo(Fase.COACTIVA);
            assertThat(cerrado.cierre().tipo()).isEqualTo(TipoDeMovimientoDeConvenio.QUIEBRE);
            assertThat(netearPorFaseYConcepto(titular))
                    .as("centimo a centimo y por fase: el libro vuelve a decir lo que decia")
                    .isEqualTo(netoAntes);
            assertThat(deudaDe(expediente, LIQUIDACION).materiaDeCobranza())
                    .as("y el expediente vuelve a tener su deuda coactiva entera")
                    .isEqualTo(PREDIAL);
        }

        @Test
        @DisplayName("el expediente no se suspende solo: eso es un acto del ejecutor")
        void elExpedienteNoSeSuspendeSolo() {
            long titular = contribuyenteConDeuda("FRAC-2");
            String expediente = expedienteDe(titular, "FRAC-2");
            enCoactiva(titular);
            dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null);

            fraccionarTodo(expediente, titular);

            assertThat(estadoDe(expediente))
                    .as(
                            "suscribir un convenio no dicta una suspension: eso es una resolucion"
                                    + " firmada, y RegistrarActoCoactivo ya la emite")
                    .isEqualTo(EstadoDelExpediente.REC1_EMITIDA);
            assertThat(enTransaccion(() -> movimientos.deExpediente(idDelExpediente(expediente))))
                    .as("el historial no gana ninguna fila por fraccionar")
                    .extracting(MovimientoDelExpediente::estado)
                    .containsExactly(
                            EstadoDelExpediente.INICIADO, EstadoDelExpediente.REC1_EMITIDA);
        }

        @Test
        @DisplayName("no se acoge deuda que no venga de coactiva: la ordinaria tiene su pantalla")
        void noSeAcogeDeudaOrdinaria() {
            long titular = contribuyenteConDeuda("FRAC-3");
            String expediente = expedienteDe(titular, "FRAC-3");
            enCoactiva(titular);
            // Y ademas una deuda ORDINARIA del mismo obligado, que la pantalla podria marcar.
            asentarCargo(titular, "ARBITRIO", Dinero.de("120.00"), Fase.ORDINARIA);

            assertThatThrownBy(
                            () ->
                                    fraccionar.fraccionar(
                                            peticionDe(
                                                    expediente,
                                                    List.of(
                                                            new SeleccionDeObligacion(
                                                                    "PREDIAL", EJERCICIO, null,
                                                                    null),
                                                            new SeleccionDeObligacion(
                                                                    "ARBITRIO",
                                                                    EJERCICIO,
                                                                    null,
                                                                    null))),
                                            PORQUE))
                    .isInstanceOf(FraccionarEnCoactiva.DeudaAjenaAlProcedimiento.class)
                    .hasMessageContaining("ARBITRIO")
                    .hasMessageContaining("ORDINARIA");

            assertThat(faseDe(titular, "PREDIAL"))
                    .as("y no se acogio nada: la comprobacion va sobre la simulacion")
                    .isEqualTo(Fase.COACTIVA);
            assertThat(faseDe(titular, "ARBITRIO")).isEqualTo(Fase.ORDINARIA);
        }

        @Test
        @DisplayName("un expediente concluido no fracciona")
        void unExpedienteConcluidoNoFracciona() {
            long titular = contribuyenteConDeuda("FRAC-4");
            String expediente = expedienteDe(titular, "FRAC-4");
            enCoactiva(titular);
            concluir(expediente);

            assertThatThrownBy(() -> fraccionarTodo(expediente, titular))
                    .isInstanceOf(CambiarEstadoDelExpediente.ExpedienteConcluido.class);
        }

        @Test
        @DisplayName("las costas liquidadas se pueden fraccionar como cualquier otra deuda")
        void lasCostasSeFraccionan() {
            long titular = contribuyenteConDeuda("FRAC-5");
            String expediente = expedienteDe(titular, "FRAC-5");
            enCoactiva(titular);
            dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null);
            liquidarTodo(expediente);

            ConvenioCoactivo convenio =
                    fraccionar.fraccionar(
                            peticionDe(
                                    expediente,
                                    List.of(
                                            new SeleccionDeObligacion(
                                                    "PREDIAL", EJERCICIO, null, null),
                                            new SeleccionDeObligacion(
                                                    LiquidacionDeCostas.TRIBUTO,
                                                    EJERCICIO,
                                                    null,
                                                    null))),
                            PORQUE);

            assertThat(convenio.total())
                    .as("la costa entra en el convenio con su importe, sin recomponerse")
                    .isEqualTo(PREDIAL.mas(ARANCEL_REC1));
            assertThat(convenio.deudaAcogida())
                    .as("y tambien viene de coactiva: se asento en esa fase")
                    .allSatisfy(cuota -> assertThat(cuota.faseOrigen()).isEqualTo("COACTIVA"));
        }
    }

    @Nested
    @DisplayName("Las consultas")
    class DeLasConsultas {

        @Test
        @DisplayName("la grilla de deudas trae la deuda, las costas y su fecha")
        void laGrillaDeDeudas() {
            String expediente = expedienteConRec1("CONS-1");
            liquidarTodo(expediente);

            Pagina<ConsultaDeDeudasCoactivas.DeudaEnCoactiva> pagina =
                    enTransaccion(
                            () ->
                                    consultaDeDeudas.deudas(
                                            new CriterioDeExpedientes(
                                                    expediente, null, null, null, null),
                                            LIQUIDACION,
                                            Paginacion.de(0, 20, "numero")));

            assertThat(pagina.contenido())
                    .singleElement()
                    .satisfies(
                            fila -> {
                                assertThat(fila.deuda().costas()).isEqualTo(ARANCEL_REC1);
                                assertThat(fila.deuda().total())
                                        .isEqualTo(PREDIAL.mas(ARANCEL_REC1));
                                assertThat(fila.aLaFecha())
                                        .as("toda cifra sale con su fecha (regla 9)")
                                        .isEqualTo(LIQUIDACION);
                                assertThat(fila.tributos()).containsExactly("PREDIAL");
                                assertThat(fila.estado())
                                        .isEqualTo(EstadoDelExpediente.REC1_EMITIDA);
                                assertThat(fila.ultimaActuacion()).isNotNull();
                            });
        }

        @Test
        @DisplayName("un expediente sin deuda no sale en la consulta de deudas")
        void sinDeudaNoSale() {
            String expediente = expedienteConRec1("CONS-2");
            pagarTodo(expediente);

            assertThat(
                            enTransaccion(
                                            () ->
                                                    consultaDeDeudas.deudas(
                                                            new CriterioDeExpedientes(
                                                                    expediente,
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    null),
                                                            LIQUIDACION,
                                                            Paginacion.de(0, 20, "numero")))
                                    .contenido())
                    .as("«consulta de DEUDAS»: un expediente pagado no es una deuda")
                    .isEmpty();
        }

        @Test
        @DisplayName("la grilla de liquidaciones deriva su estado del libro, no de una columna")
        void laGrillaDeLiquidaciones() {
            String expediente = expedienteConRec1("CONS-3");
            LiquidacionDeCostas liquidacion = liquidarTodo(expediente);

            ConsultaDeCostas.LiquidacionEnConsulta fila =
                    enTransaccion(
                                    () ->
                                            consultaDeCostas.buscar(
                                                    new CriterioDeLiquidaciones(
                                                            liquidacion.numero(), null, null),
                                                    LIQUIDACION,
                                                    null,
                                                    Paginacion.de(0, 20, "fecha")))
                            .contenido()
                            .get(0);

            assertThat(fila.estado()).isEqualTo(EstadoDeLaLiquidacion.ACTIVA);
            assertThat(fila.pendiente()).isEqualTo(ARANCEL_REC1);
            assertThat(fila.aLaFecha()).isEqualTo(LIQUIDACION);
            assertThat(fila.numeroDeExpediente()).isEqualTo(expediente);

            pagarLasCostas(expediente);

            ConsultaDeCostas.LiquidacionEnConsulta despues =
                    enTransaccion(
                                    () ->
                                            consultaDeCostas.porNumero(
                                                    liquidacion.numero(), LIQUIDACION))
                            .orElseThrow();
            assertThat(despues.estado())
                    .as("cancelada porque el libro lo dice, no porque nadie escribiera una C")
                    .isEqualTo(EstadoDeLaLiquidacion.CANCELADA);
            assertThat(despues.liquidacion().total())
                    .as("y lo liquidado sigue congelado: es de otra fecha")
                    .isEqualTo(ARANCEL_REC1);
        }

        @Test
        @DisplayName("deudas en beneficio: lista el beneficio registrado y no calcula el descuento")
        void deudasEnBeneficio() {
            String conBeneficio = expedienteConRec1(BeneficiosDeLaPrueba.CON_BENEFICIO);
            liquidarTodo(conBeneficio);
            String sinBeneficio = expedienteConRec1("CONS-5");

            Pagina<ConsultaDeDeudasCoactivas.DeudaConBeneficio> pagina =
                    enTransaccion(
                            () ->
                                    consultaDeDeudas.enBeneficio(
                                            CriterioDeExpedientes.todos(),
                                            LIQUIDACION,
                                            Paginacion.de(0, 50, "numero")));

            assertThat(pagina.contenido())
                    .as("solo los obligados con beneficio registrado y vigente a la fecha")
                    .isNotEmpty()
                    .allSatisfy(fila -> assertThat(fila.beneficios()).isNotEmpty())
                    .anySatisfy(
                            fila ->
                                    assertThat(fila.deuda().expediente().numero())
                                            .isEqualTo(conBeneficio));
            assertThat(pagina.contenido())
                    .extracting(fila -> fila.deuda().expediente().numero())
                    .doesNotContain(sinBeneficio);

            ConsultaDeDeudasCoactivas.DeudaConBeneficio fila =
                    pagina.contenido().stream()
                            .filter(f -> f.deuda().expediente().numero().equals(conBeneficio))
                            .findFirst()
                            .orElseThrow();
            assertThat(fila.deuda().deuda().total())
                    .as("lo que se debe: sin descuento, porque el efecto es D-02b (#191)")
                    .isEqualTo(PREDIAL.mas(ARANCEL_REC1));
            assertThat(fila.beneficios())
                    .singleElement()
                    .satisfies(
                            beneficio -> {
                                assertThat(beneficio.tipo()).isEqualTo("AMNISTIA COACTIVA");
                                assertThat(beneficio.baseLegal()).isNotBlank();
                                assertThat(beneficio.porcentajeDeclarado())
                                        .as("lo que la norma declara, no un descuento aplicado")
                                        .isNotNull();
                            });
        }
    }

    // ==================================================================

    private static LiquidacionDeCostas liquidarTodo(String expediente) {
        return liquidar.liquidar(
                LiquidarCostas.Peticion.deTodoElExpediente(expediente, LIQUIDACION), PORQUE);
    }

    private static ConvenioCoactivo fraccionarTodo(String expediente, long titular) {
        return fraccionar.fraccionar(
                peticionDe(
                        expediente,
                        List.of(new SeleccionDeObligacion("PREDIAL", EJERCICIO, null, null))),
                PORQUE);
    }

    private static FraccionarEnCoactiva.Peticion peticionDe(
            String expediente, List<SeleccionDeObligacion> obligaciones) {
        return new FraccionarEnCoactiva.Peticion(
                expediente,
                obligaciones,
                LIQUIDACION,
                LIQUIDACION,
                6,
                Alicuota.de("20"),
                LIQUIDACION.plusMonths(1),
                null);
    }

    private static Convenio porNumero(@Nullable String numero) {
        return enTransaccion(
                        () ->
                                convenios.porNumero(
                                        NumeroDeConvenio.de(
                                                java.util.Objects.requireNonNull(numero))))
                .orElseThrow();
    }

    /**
     * Formaliza el convenio con un recibo de `caja`.
     *
     * <p>Hasta `V7` esto cobraba de verdad en ventanilla. El identificador que entra no existe en
     * ninguna tabla de esta base, y eso es a proposito: `V7` retiro `convenio_movimiento_recibo_fk`
     * y que la formalizacion lo acepte es la comprobacion de que la retiro de verdad.
     */
    private static void formalizarLaInicial(Convenio convenio) {
        formalizar.formalizar(
                convenio.numero(),
                SIGUIENTE_RECIBO.incrementAndGet(),
                convenio.cuotaInicial(),
                LIQUIDACION,
                Observacion.de("Cuota inicial del convenio coactivo, prueba de #42"));
    }

    private static CerrarConvenio.Cerrado quebrar(Convenio convenio) {
        return cerrar.cerrar(
                new CerrarConvenio.Cierre(
                        convenio.numero(),
                        TipoDeMovimientoDeConvenio.QUIEBRE,
                        LIQUIDACION,
                        "INCUMPLIMIENTO",
                        "EJECUTOR COACTIVO",
                        "MEMO-2026-042",
                        null),
                null,
                Observacion.de("Se quiebra el convenio coactivo, prueba de #42"));
    }

    /** Un expediente con su REC-1 dictada, sobre un contribuyente nuevo. */
    private static String expedienteConRec1(String sufijo) {
        return expedienteConRec1(sufijo, null);
    }

    private static String expedienteConRec1(String sufijo, @Nullable Long predioId) {
        long titular = contribuyenteConDeuda(sufijo, predioId);
        String expediente = expedienteDe(titular, sufijo, predioId);
        dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null);
        return expediente;
    }

    private static void notificarLaRec1(String expediente) {
        String rec1 =
                enTransaccion(() -> actos.rec1De(idDelExpediente(expediente)))
                        .orElseThrow()
                        .numero();
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("ejecutor.coactivo", null, null));
        notificar.registrar(
                rec1,
                DILIGENCIA_REC1,
                ModalidadDeNotificacion.PERSONAL,
                ResultadoDeNotificacion.NOTIFICADO,
                "J. RUIZ PALACIOS",
                null,
                "TITULAR, PRUEBA",
                "DNI 12345678",
                "TITULAR",
                "CARGO-42",
                PORQUE);
    }

    private static RegistrarActoCoactivo.ActoDictado dictarActo(
            String expediente,
            TipoDeActoCoactivo tipo,
            LocalDate fecha,
            @Nullable TipoDeMedidaCautelar medida) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("ejecutor.coactivo", null, null));
        return dictar.dictar(
                new RegistrarActoCoactivo.Peticion(
                        expediente, tipo, fecha, tipo.titulo() + " de la prueba", medida, null),
                FormatoDeDocumento.PDF,
                PORQUE);
    }

    private static String expedienteDe(long titular, String sufijoDelValor) {
        return expedienteDe(titular, sufijoDelValor, null);
    }

    private static String expedienteDe(
            long titular, String sufijoDelValor, @Nullable Long predioId) {
        Valor valor = emitir(titular, "OP-" + sufijoDelValor, predioId);
        pasarACoactiva(valor);
        return importar.importar(
                        new ImportarValoresACoactiva.Peticion(
                                titular,
                                List.of(valor.numero()),
                                "EJECUTOR COACTIVO",
                                null,
                                null,
                                "AV. GRAU 100"),
                        IMPORTACION,
                        PlantillaDeNumeroDeExpediente.POR_OMISION,
                        PORQUE)
                .expedienteAbierto()
                .numero();
    }

    private static DeudaDelExpediente deudaDe(String expediente, LocalDate fecha) {
        return enTransaccion(
                () ->
                        consulta.deudaDe(
                                enTransaccion(() -> expedientes.porNumero(expediente))
                                        .orElseThrow(),
                                fecha));
    }

    private static EstadoDelExpediente estadoDe(String expediente) {
        return EstadoDelExpediente.delHistorial(
                enTransaccion(() -> movimientos.deExpediente(idDelExpediente(expediente))));
    }

    private static void concluir(String expediente) {
        long id = idDelExpediente(expediente);
        enTransaccion(
                () ->
                        movimientos.registrar(
                                MovimientoDelExpediente.cambioDeEstado(
                                        id,
                                        EstadoDelExpediente.CONCLUIDO,
                                        REC1.plusDays(1),
                                        "conclusion de la prueba",
                                        null,
                                        null,
                                        RELOJ.instant(),
                                        PORQUE)));
    }

    private static long idDelExpediente(String numero) {
        return enTransaccion(() -> expedientes.porNumero(numero)).orElseThrow().identificador();
    }

    private static long idDeLaRec1(String expediente) {
        return enTransaccion(() -> actos.rec1De(idDelExpediente(expediente)))
                .orElseThrow()
                .identificador();
    }

    private static int cuantasCostasDe(String expediente) {
        Integer cuantas =
                enTransaccion(
                        () ->
                                jdbc.sql(
                                                "SELECT count(*)::int FROM costa_procesal"
                                                        + " WHERE expediente_id = :expediente")
                                        .param("expediente", idDelExpediente(expediente))
                                        .query(Integer.class)
                                        .single());
        return cuantas == null ? 0 : cuantas;
    }

    private static List<Map<String, Object>> asientosDelDocumento(String documento) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT concepto, tipo, fase, tributo, referencia_externa,"
                                                + " monto FROM cuenta_corriente_asiento"
                                                + " WHERE documento_origen = :documento"
                                                + " ORDER BY id")
                                .param("documento", documento)
                                .query()
                                .listOfRows());
    }

    /** El neteo del libro por (fase, concepto), que es la comparacion que pide #35. */
    private static Map<String, Dinero> netearPorFaseYConcepto(long titular) {
        Map<String, Dinero> neto = new LinkedHashMap<>();
        List<Map<String, Object>> filas =
                enTransaccion(
                        () ->
                                jdbc.sql(
                                                "SELECT fase, concepto, tipo, monto FROM"
                                                        + " cuenta_corriente_asiento WHERE"
                                                        + " contribuyente_id = :titular ORDER BY id")
                                        .param("titular", titular)
                                        .query()
                                        .listOfRows());
        for (Map<String, Object> fila : filas) {
            String clave = fila.get("fase") + "/" + fila.get("concepto");
            Dinero monto = new Dinero((java.math.BigDecimal) fila.get("monto"));
            Dinero acumulado = neto.getOrDefault(clave, Dinero.CERO);
            neto.put(
                    clave,
                    "CARGO ".equals(fila.get("tipo"))
                            ? acumulado.mas(monto)
                            : acumulado.menos(monto));
        }
        neto.entrySet().removeIf(entrada -> !entrada.getValue().esPositivo());
        return neto;
    }

    private static Fase faseDe(long titular, String tributo) {
        String fase =
                enTransaccion(
                        () ->
                                jdbc.sql(
                                                "SELECT fase FROM saldo_proyectado"
                                                        + " WHERE contribuyente_id = :titular"
                                                        + "   AND tributo = :tributo")
                                        .param("titular", titular)
                                        .param("tributo", tributo)
                                        .query(String.class)
                                        .single());
        return Fase.valueOf(java.util.Objects.requireNonNull(fase));
    }

    /** Un contribuyente con su cargo de predial ya asentado, todavia en fase VALOR. */
    private static long contribuyenteConDeuda(String sufijo) {
        return contribuyenteConDeuda(sufijo, null);
    }

    /**
     * El mismo contribuyente con deuda, pero <b>con su unidad</b> cuando se le pide.
     *
     * <p>La unidad tiene que ir en los DOS sitios —el asiento del libro y el detalle del valor—,
     * porque la deuda del expediente se compone cruzando lo que el valor formaliza con lo que el
     * libro dice, y la clave de ese cruce incluye {@code predioId} (#426).
     */
    private static long contribuyenteConDeuda(String sufijo, @Nullable Long predioId) {
        long id = crearContribuyente(sufijo);
        asentarCargo(id, "PREDIAL", PREDIAL, Fase.VALOR, predioId);
        return id;
    }

    private static void asentarCargo(long titular, String tributo, Dinero monto, Fase fase) {
        asentarCargo(titular, tributo, monto, fase, null);
    }

    private static void asentarCargo(
            long titular, String tributo, Dinero monto, Fase fase, @Nullable Long predioId) {
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        titular,
                                        tributo,
                                        Concepto.INSOLUTO,
                                        TipoAsiento.CARGO,
                                        fase,
                                        null,
                                        predioId,
                                        null,
                                        null,
                                        monto,
                                        FECHA_DEL_CARGO,
                                        "DETERMINACION DE LA PRUEBA " + tributo),
                                Observacion.de("Se asienta la deuda de la prueba")));
    }

    /** Mueve la deuda del predial a fase coactiva, que es donde el expediente la encuentra. */
    private static void enCoactiva(long titular) {
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        titular,
                                        "PREDIAL",
                                        Concepto.INSOLUTO,
                                        TipoAsiento.ABONO,
                                        Fase.VALOR,
                                        null,
                                        null,
                                        null,
                                        null,
                                        PREDIAL,
                                        PASE,
                                        "PASE A COACTIVA DE LA PRUEBA"),
                                Observacion.de("Sale de la fase de valor")));
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        titular,
                                        "PREDIAL",
                                        Concepto.INSOLUTO,
                                        TipoAsiento.CARGO,
                                        Fase.COACTIVA,
                                        null,
                                        null,
                                        null,
                                        null,
                                        PREDIAL,
                                        PASE,
                                        "PASE A COACTIVA DE LA PRUEBA"),
                                Observacion.de("Entra en cobranza coactiva")));
    }

    private static void pagarTodo(String expediente) {
        long titular =
                enTransaccion(() -> expedientes.porNumero(expediente))
                        .orElseThrow()
                        .contribuyenteId();
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        titular,
                                        "PREDIAL",
                                        Concepto.INSOLUTO,
                                        TipoAsiento.ABONO,
                                        Fase.VALOR,
                                        null,
                                        null,
                                        null,
                                        null,
                                        PREDIAL,
                                        LIQUIDACION,
                                        "PAGO TOTAL DE LA PRUEBA"),
                                Observacion.de("El obligado paga el integro")));
    }

    private static void pagarLasCostas(String expediente) {
        long titular =
                enTransaccion(() -> expedientes.porNumero(expediente))
                        .orElseThrow()
                        .contribuyenteId();
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        titular,
                                        LiquidacionDeCostas.TRIBUTO,
                                        Concepto.GASTO,
                                        TipoAsiento.ABONO,
                                        Fase.COACTIVA,
                                        null,
                                        null,
                                        null,
                                        null,
                                        ARANCEL_REC1,
                                        LIQUIDACION,
                                        "PAGO DE COSTAS DE LA PRUEBA"),
                                Observacion.de("El obligado paga las costas")));
    }

    private static Valor emitir(long contribuyenteId, String numero) {
        return emitir(contribuyenteId, numero, null);
    }

    private static Valor emitir(long contribuyenteId, String numero, @Nullable Long predioId) {
        return enTransaccion(
                () ->
                        valores.insertar(
                                new Valor(
                                        null,
                                        TipoValor.ORDEN_DE_PAGO,
                                        numero,
                                        EJERCICIO,
                                        contribuyenteId,
                                        TipoValor.ORDEN_DE_PAGO.baseLegal(),
                                        PREDIAL,
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        EMISION,
                                        EstadoDeValor.EMITIDO,
                                        EMISION,
                                        null,
                                        Observacion.de("Se emite para la prueba")),
                                List.of(
                                        ValorDetalle.nuevo(
                                                "PREDIAL",
                                                EJERCICIO,
                                                null,
                                                predioId,
                                                null,
                                                null,
                                                PREDIAL,
                                                Dinero.CERO,
                                                Dinero.CERO,
                                                Dinero.CERO))));
    }

    private static void pasarACoactiva(Valor valor) {
        Notificacion diligencia =
                enTransaccion(
                        () ->
                                notificacionesDeValor.insertar(
                                        new Notificacion(
                                                null,
                                                valor.id(),
                                                valor.numero() + "/1",
                                                1,
                                                DILIGENCIA_DEL_VALOR,
                                                ModalidadDeNotificacion.PERSONAL,
                                                ResultadoDeNotificacion.NOTIFICADO,
                                                "J. RUIZ PALACIOS",
                                                "AV. GRAU 100 - SULLANA",
                                                "TITULAR, PRUEBA",
                                                "DNI 12345678",
                                                "TITULAR",
                                                "CARGO-1",
                                                EXIGIBLE_EL_VALOR,
                                                conjuntoId,
                                                null,
                                                Observacion.de("Se diligencio para la prueba"))));
        enTransaccion(() -> valores.cambiarEstado(valor.id(), EstadoDeValor.NOTIFICADO));
        enTransaccion(
                () ->
                        movimientosDeValor.registrarPase(
                                new MovimientoDeValor(
                                        null,
                                        valor.id(),
                                        TipoDeMovimiento.PCO,
                                        PASE,
                                        diligencia.id(),
                                        EXIGIBLE_EL_VALOR,
                                        null,
                                        Observacion.de("Se pasa a coactiva para la prueba"))));
        enTransaccion(() -> valores.cambiarEstado(valor.id(), EstadoDeValor.COACTIVA));
    }

    private static <T> T enTransaccion(Supplier<T> accion) {
        return enTransaccionDe(municipalidad, accion);
    }

    private static <T> T enTransaccionDe(long tenant, Supplier<T> accion) {
        TenantContext.fijar(new MunicipalidadId(tenant));
        return transaccion.execute(
                estado -> {
                    TenantContext.fijar(new MunicipalidadId(tenant));
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

    private static void ejecutarComoApp(String sql) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

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

    private static long insertarComoOwner(long muni, String sql, Object... parametros) {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            ContextoDeTenant.fijar(owner, muni);
            try (PreparedStatement sentencia = owner.prepareStatement(sql)) {
                for (int i = 0; i < parametros.length; i++) {
                    sentencia.setObject(i + 1, parametros[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    owner.commit();
                    return id;
                }
            }
        } catch (SQLException fallo) {
            throw new IllegalStateException("No se pudo sembrar: " + sql, fallo);
        }
    }

    /**
     * El conjunto sellado de 2026, con todo lo que #42 necesita <b>como dato</b>.
     *
     * <p>El plazo de la REC-1 (#41), el interes y el maximo de cuotas del fraccionamiento (#35), la
     * politica de redondeo de la cuota (E-7 §3) y —lo propio de #42— el <b>arancel de costas por
     * acto</b>. Ninguno de esos numeros vive en el codigo de produccion: que esta prueba tenga que
     * sembrarlos es la demostracion de la regla 5.
     */
    private static long crearConjuntoSellado(long municipalidadId) throws SQLException {
        List<Long> parametros = new ArrayList<>();
        parametros.add(
                cargarParametro(
                        "PLAZO",
                        "REC1_CUMPLIMIENTO",
                        null,
                        "7 DIAS_HABILES",
                        "Ley 26979 art. 14.1"));
        parametros.add(
                cargarParametro(
                        "ARANCEL_COSTA",
                        "REC1",
                        ARANCEL_REC1.valor().toPlainString(),
                        null,
                        "Ordenanza de aranceles de costas (dato de la prueba)"));
        parametros.add(
                cargarParametro(
                        "ARANCEL_COSTA",
                        "REC2",
                        ARANCEL_REC2.valor().toPlainString(),
                        null,
                        "Ordenanza de aranceles de costas (dato de la prueba)"));
        parametros.add(
                cargarParametro(
                        "INTERES_FRACCIONAMIENTO",
                        "ORDINARIO",
                        "1",
                        null,
                        "Ordenanza de fraccionamiento (dato de la prueba)"));
        parametros.add(
                cargarParametro(
                        "CUOTAS_MAXIMAS_FRACCIONAMIENTO",
                        "ORDINARIO",
                        "12",
                        null,
                        "Ordenanza de fraccionamiento (dato de la prueba)"));
        parametros.add(
                cargarParametro(
                        "REDONDEO",
                        "CUOTA",
                        "2",
                        RoundingMode.HALF_UP.name(),
                        "Politica de redondeo de la prueba (D-03)"));

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
            for (Long parametroId : parametros) {
                try (PreparedStatement sentencia =
                        app.prepareStatement(
                                "INSERT INTO conjunto_parametro_detalle_de_prueba (municipalidad_id,"
                                        + " conjunto_id, parametro_id) VALUES (?, ?, ?)")) {
                    sentencia.setLong(1, municipalidadId);
                    sentencia.setLong(2, conjunto);
                    sentencia.setLong(3, parametroId);
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
     * Carga un parametro con su propio rol.
     *
     * <p>El catalogo normativo lo carga {@code rol_carga_parametros}, no la aplicacion ni el duenio
     * del esquema (SoD-1 de REQ-03, politica {@code parametro_escritura} de V6).
     */
    private static long cargarParametro(
            String tipo,
            @Nullable String clave,
            @Nullable String numero,
            @Nullable String texto,
            String fuente)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, valor_texto, vigencia_desde,"
                                        + " documento_fuente, sellado, usuario_carga)"
                                        + " VALUES (NULL, ?, ?, ?::monto_calc, ?,"
                                        + " DATE '2026-01-01', ?, true, 'siembra') RETURNING id")) {
            sentencia.setString(1, tipo);
            sentencia.setString(2, clave);
            sentencia.setString(3, numero);
            sentencia.setString(4, texto);
            sentencia.setString(5, fuente);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static long crearContribuyente(String sufijo) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, sufijo);
                sentencia.setString(3, dniDe(sufijo));
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(
                    "No se pudo crear el contribuyente de prueba", excepcion);
        }
    }

    private static String dniDe(String codigo) {
        return "4242" + Math.abs(codigo.hashCode() % 10000 + 10000);
    }

    /** Un lector de parametros con todo menos el arancel de costas: el estado de hoy (#193). */
    private static final class SinArancelDeCostas
            implements kamayuk.rentas.parametros.LectorDeParametros {

        @Override
        public kamayuk.rentas.parametros.ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            return kamayuk.rentas.parametros.ParametrosSellados.de(ejercicio, 1).construir();
        }

        @Override
        public kamayuk.rentas.parametros.ParametrosSellados porConjunto(
                kamayuk.rentas.parametros.IdentificadorDeConjunto identificador) {
            return vigenteEn(EJERCICIO);
        }

        @Override
        public kamayuk.rentas.parametros.IdentificadorDeConjunto conjuntoVigenteEn(
                Ejercicio ejercicio) {
            return kamayuk.rentas.parametros.IdentificadorDeConjunto.de(conjuntoId);
        }
    }

    /**
     * Los beneficios, con uno solo registrado.
     *
     * <p>Un doble y no la base porque {@code rentas} tiene su propia prueba de beneficios (#27): lo
     * que #42 verifica es que la consulta <b>los liste sin aplicarlos</b>, no como se guardan.
     */
    private static final class BeneficiosDeLaPrueba implements BeneficiosDelContribuyente {

        /** El sufijo del contribuyente al que se le registra el beneficio. */
        static final String CON_BENEFICIO = "CONS-4";

        @Override
        public List<BeneficioRegistrado> vigentesA(long contribuyenteId, LocalDate aLaFecha) {
            String codigo =
                    jdbc.sql("SELECT codigo_contribuyente FROM contribuyente" + " WHERE id = :id")
                            .param("id", contribuyenteId)
                            .query(String.class)
                            .optional()
                            .orElse("");
            if (!CON_BENEFICIO.equals(codigo)) {
                return List.of();
            }
            return List.of(
                    new BeneficioRegistrado(
                            "AMNISTIA COACTIVA",
                            "DESCUENTO",
                            "PREDIAL",
                            Alicuota.de("50"),
                            null,
                            "Ordenanza 015-2026 (dato de la prueba)",
                            LocalDate.of(2026, 1, 1),
                            null));
        }
    }

    /** El padron, leido de la base: el nombre del obligado sale impreso en la resolucion. */
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
            Map<Long, ResumenDeContribuyente> encontrados = new LinkedHashMap<>();
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
            return Optional.empty();
        }

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
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
