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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.coactiva.aplicacion.CambiarEstadoDelExpediente;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeExpedientes;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDelProcesoCoactivo;
import kamayuk.rentas.coactiva.aplicacion.ImportarValoresACoactiva;
import kamayuk.rentas.coactiva.aplicacion.NotificarActoCoactivo;
import kamayuk.rentas.coactiva.aplicacion.PlazosCoactivosParametrizados;
import kamayuk.rentas.coactiva.aplicacion.RegistrarActoCoactivo;
import kamayuk.rentas.coactiva.aplicacion.ReimprimirActoCoactivo;
import kamayuk.rentas.coactiva.dominio.ActoCoactivo;
import kamayuk.rentas.coactiva.dominio.ActoCoactivoRepository;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.NotificacionCoactiva;
import kamayuk.rentas.coactiva.dominio.PlantillaDeNumeroDeExpediente;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;
import kamayuk.rentas.coactiva.dominio.TipoDeMedidaCautelar;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.MovimientoDeFaseCuentaCorriente;
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
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
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
 * #41 — El procedimiento coactivo contra PostgreSQL de verdad (V34), conectado como {@code
 * kamayuk_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>El procedimiento entero, de una punta a la otra.</b> Deuda asentada, valor emitido,
 *       notificado, exigible, pasado a coactiva, expediente abierto (#40), REC-1 emitida e impresa,
 *       notificada con acuse, plazo vencido, REC-2 con su forma de medida, actos con sus
 *       documentos, pago total y acto nuevo rechazado. Contra dobles esto solo probaria que los
 *       dobles recuerdan lo que se les dijo.
 *   <li><b>Que la base impida una REC-2 prematura.</b> No es un {@code if}: es {@code
 *       acto_rec2_plazo_ck} y {@code acto_rec2_sustento_ck} (V34), y se comprueba intentandolo por
 *       SQL directo, que es como se salta cualquier comprobacion escrita en Java.
 *   <li><b>Que no haya dos REC-1 del mismo expediente bajo concurrencia real.</b> Un doble que
 *       consulta antes de insertar pasa la prueba y falla en produccion: diez peticiones
 *       simultaneas pasan las diez por el {@code if}. Aqui se lanzan diez hilos a la vez.
 *   <li><b>Que {@code kamayuk_app} no pueda editar ni borrar un acto.</b> Es el {@code REVOKE} de
 *       V34, y se comprueba intentandolo.
 *   <li><b>Que reintentar una diligencia no pierda la anterior.</b> La garantia es {@code
 *       notificacion_intento_uq} (V28) sobre la <b>misma</b> tabla que #39 usa, en su rebanada
 *       {@code objeto = 'ACTO_COACTIVO'}.
 *   <li><b>Que la REC se reimprima identica.</b> El SHA-256 se recalcula sobre los datos guardados;
 *       si no coincidiera, la reimpresion falla en vez de entregar otro papel con el mismo numero.
 *   <li><b>Que RLS aisle el acto</b>: desde otra municipalidad no existe.
 * </ul>
 */
@DisplayName("#41 — Los actos coactivos y sus notificaciones contra PostgreSQL")
class ActosCoactivosJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate FECHA_DEL_CARGO = LocalDate.of(2026, 1, 2);
    private static final LocalDate EMISION = LocalDate.of(2026, 3, 2);
    private static final LocalDate DILIGENCIA_DEL_VALOR = LocalDate.of(2026, 4, 3);
    private static final LocalDate EXIGIBLE_EL_VALOR = LocalDate.of(2026, 5, 5);
    private static final LocalDate PASE = LocalDate.of(2026, 6, 1);
    private static final LocalDate IMPORTACION = LocalDate.of(2026, 6, 15);

    /** El dia en que se dicta la REC-1. */
    private static final LocalDate REC1 = LocalDate.of(2026, 6, 16);

    /** El dia en que se diligencia la REC-1: miercoles. */
    private static final LocalDate DILIGENCIA_REC1 = LocalDate.of(2026, 6, 17);

    /**
     * Desde cuando se puede dictar la REC-2, con el plazo <b>parametrizado</b> de 7 dias habiles.
     *
     * <p>La cuenta, dia a dia: la diligencia es el miercoles 17; surte efecto el jueves 18 (art.
     * 106: el dia habil siguiente); siete dias habiles desde ahi son 19, 22, 23, 24, 25, 26 y 29;
     * el plazo vence el lunes 29 y la deuda es exigible el <b>martes 30</b>. Esta escrito aqui
     * porque una prueba que recalculara la fecha con el mismo codigo que verifica no verificaria
     * nada.
     */
    private static final LocalDate REC2_DESDE = LocalDate.of(2026, 6, 30);

    private static final Dinero PREDIAL = Dinero.de("500.00");
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-15T09:00:00Z"), ZoneOffset.UTC);

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
    private static LiquidacionDeCostasRepositoryJdbc costas;
    private static MovimientoDelExpedienteRepositoryJdbc movimientos;
    private static ActoCoactivoRepositoryJdbc actos;
    private static NotificacionCoactivaRepositoryJdbc diligencias;
    private static RegistrarAsiento registrarAsiento;

    private static ImportarValoresACoactiva importar;
    private static ConsultaDeExpedientes consulta;
    private static RegistrarActoCoactivo dictar;
    private static NotificarActoCoactivo notificar;
    private static CambiarEstadoDelExpediente cambiarEstado;
    private static ReimprimirActoCoactivo reimprimir;
    private static ConsultaDelProcesoCoactivo proceso;

    /** El mismo registro de documentos, con un dibujo distinto: para probar que el SHA muerde. */
    private static EmitirDocumento documentosConOtroDibujo;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("230404", "Municipalidad de los actos coactivos");
        otraMunicipalidad = crearMunicipalidad("240409", "Municipalidad vecina de #41");
        conjuntoId = crearConjuntoConElPlazoDeLaRec1(municipalidad);

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
        costas = new LiquidacionDeCostasRepositoryJdbc(jdbc);
        movimientos = new MovimientoDelExpedienteRepositoryJdbc(jdbc);
        actos = new ActoCoactivoRepositoryJdbc(jdbc);
        diligencias = new NotificacionCoactivaRepositoryJdbc(jdbc);

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
                                expedientes,
                                movimientos,
                                puerto,
                                envolver(
                                        new MovimientoDeFaseCuentaCorriente(
                                                registrarAsiento,
                                                asientos,
                                                saldos,
                                                calculo,
                                                redondeo)),
                                auditoria,
                                RELOJ));
        consulta =
                envolver(
                        new ConsultaDeExpedientes(expedientes, movimientos, puerto, deuda, costas));

        PlazosCoactivosParametrizados plazos =
                new PlazosCoactivosParametrizados(
                        envolver(
                                new kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados(
                                        new ParametrosRepositoryJdbc(jdbc))));

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

        documentosConOtroDibujo =
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
                                                new UnByteDeMas(new RenderizadorPdf()),
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
                                diligencias,
                                consulta,
                                puerto,
                                padron,
                                plazos,
                                documentos,
                                auditoria,
                                RELOJ));
        notificar =
                envolver(
                        new NotificarActoCoactivo(
                                actos,
                                diligencias,
                                expedientes,
                                movimientos,
                                plazos,
                                auditoria,
                                RELOJ));
        cambiarEstado =
                envolver(
                        new CambiarEstadoDelExpediente(expedientes, movimientos, auditoria, RELOJ));
        reimprimir = envolver(new ReimprimirActoCoactivo(actos, expedientes, documentos));
        proceso = envolver(new ConsultaDelProcesoCoactivo(consulta, actos, diligencias));
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
    @DisplayName("El procedimiento, de la REC-1 al pago total")
    class ElProcedimiento {

        @Test
        @DisplayName(
                "REC-1 emitida, notificada, plazo vencido, REC-2 con su medida, embargo, pago"
                        + " total y acto nuevo rechazado")
        void elProcedimientoCompleto() {
            String expediente = expedienteConDeuda("A-0001");

            RegistrarActoCoactivo.ActoDictado rec1 =
                    dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null);

            assertThat(rec1.acto().numero())
                    .as("el numero del acto ES el del documento emitido: no hay dos numeraciones")
                    .isEqualTo(rec1.emision().registro().numero())
                    .startsWith("REC1-2026-");
            assertThat(rec1.estado()).isEqualTo(EstadoDelExpediente.REC1_EMITIDA);
            assertThat(rec1.deuda().total()).isEqualTo(PREDIAL);
            assertThat(new String(rec1.emision().contenido(), StandardCharsets.ISO_8859_1))
                    .as("el papel dice a que dia esta la cifra que imprime (regla 9, RNF-075)")
                    .contains("Deuda actualizada al " + REC1);

            NotificarActoCoactivo.Diligencia acuse =
                    notificarActo(
                            rec1.acto().numero(),
                            DILIGENCIA_REC1,
                            ResultadoDeNotificacion.NOTIFICADO);

            assertThat(acuse.notificacion().exigibleDesde())
                    .as(
                            "los siete dias habiles salen del conjunto sellado, no de un 7"
                                    + " compilado (regla 5, art. 14.1 de la Ley 26979)")
                    .isEqualTo(REC2_DESDE);
            assertThat(acuse.notificacion().conjuntoId())
                    .as("y queda dicho de que conjunto salieron (ARQ-09 §3)")
                    .isEqualTo(conjuntoId);
            assertThat(acuse.estado()).isEqualTo(EstadoDelExpediente.REC1_NOTIFICADA);

            RegistrarActoCoactivo.ActoDictado rec2 =
                    dictarActo(
                            expediente,
                            TipoDeActoCoactivo.REC2,
                            REC2_DESDE,
                            TipoDeMedidaCautelar.RETENCION);

            assertThat(rec2.acto().medida()).isEqualTo(TipoDeMedidaCautelar.RETENCION);
            assertThat(rec2.acto().rec1ExigibleDesde())
                    .as("la REC-2 copia su sustento, no lo vuelve a resolver (patron de V28)")
                    .isEqualTo(REC2_DESDE);
            assertThat(rec2.acto().rec1NotificacionId())
                    .isEqualTo(acuse.notificacion().identificador());
            assertThat(rec2.estado()).isEqualTo(EstadoDelExpediente.REC2_EMITIDA);

            RegistrarActoCoactivo.ActoDictado embargo =
                    dictarActo(
                            expediente, TipoDeActoCoactivo.EMBARGO, REC2_DESDE.plusDays(2), null);
            assertThat(embargo.estado()).isEqualTo(EstadoDelExpediente.MEDIDA_CAUTELAR);

            // El obligado paga: un abono por el total, asentado en el libro de verdad.
            pagarTodo(expediente, REC2_DESDE.plusDays(5));

            assertThatThrownBy(
                            () ->
                                    dictarActo(
                                            expediente,
                                            TipoDeActoCoactivo.EMBARGO,
                                            REC2_DESDE.plusDays(10),
                                            null))
                    .as("embargar a quien ya pago es lo que esta regla existe para impedir")
                    .isInstanceOf(RegistrarActoCoactivo.DeudaExtinguida.class)
                    .hasMessageContaining("no tiene deuda");

            RegistrarActoCoactivo.ActoDictado conclusion =
                    dictarActo(
                            expediente,
                            TipoDeActoCoactivo.CONCLUSION,
                            REC2_DESDE.plusDays(10),
                            null);
            assertThat(conclusion.estado())
                    .as(
                            "concluir SI se puede sin deuda: es justamente el acto del expediente"
                                    + " pagado")
                    .isEqualTo(EstadoDelExpediente.CONCLUIDO);

            ConsultaDelProcesoCoactivo.ProcesoCoactivo seguimiento =
                    enTransaccion(() -> proceso.porNumero(expediente, REC2_DESDE.plusDays(10)))
                            .orElseThrow();
            assertThat(seguimiento.actuaciones())
                    .extracting(a -> a.acto().tipo())
                    .containsExactly(
                            TipoDeActoCoactivo.REC1,
                            TipoDeActoCoactivo.REC2,
                            TipoDeActoCoactivo.EMBARGO,
                            TipoDeActoCoactivo.CONCLUSION);
            assertThat(seguimiento.actuaciones().get(0).diligencias()).hasSize(1);
            assertThat(seguimiento.ficha().estado()).isEqualTo(EstadoDelExpediente.CONCLUIDO);
        }

        @Test
        @DisplayName("no hay REC-2 sin REC-1: la medida se dicta despues de iniciar")
        void noHayRec2SinRec1() {
            String expediente = expedienteConDeuda("A-0002");

            assertThatThrownBy(
                            () ->
                                    dictarActo(
                                            expediente,
                                            TipoDeActoCoactivo.REC2,
                                            REC2_DESDE,
                                            TipoDeMedidaCautelar.INSCRIPCION))
                    .isInstanceOf(RegistrarActoCoactivo.Rec1SinDictar.class)
                    .hasMessageContaining("art. 14.1");
        }

        @Test
        @DisplayName("no hay REC-2 con la REC-1 dictada y sin notificar: el plazo no ha empezado")
        void noHayRec2SinNotificar() {
            String expediente = expedienteConDeuda("A-0003");
            dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null);

            assertThatThrownBy(
                            () ->
                                    dictarActo(
                                            expediente,
                                            TipoDeActoCoactivo.REC2,
                                            REC2_DESDE,
                                            TipoDeMedidaCautelar.RETENCION))
                    .isInstanceOf(RegistrarActoCoactivo.Rec1SinNotificar.class);
        }

        @Test
        @DisplayName("una diligencia no hallada no abre el plazo: la REC-2 sigue sin sustento")
        void laNoHalladaNoAbreElPlazo() {
            String expediente = expedienteConDeuda("A-0004");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            NotificarActoCoactivo.Diligencia fallida =
                    notificarActo(
                            rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NO_UBICADO);

            assertThat(fallida.notificacion().exigibleDesde()).isNull();
            assertThat(fallida.estado())
                    .as("no hallar al obligado no notifica nada: el expediente no se mueve")
                    .isEqualTo(EstadoDelExpediente.REC1_EMITIDA);
            assertThatThrownBy(
                            () ->
                                    dictarActo(
                                            expediente,
                                            TipoDeActoCoactivo.REC2,
                                            REC2_DESDE,
                                            TipoDeMedidaCautelar.RETENCION))
                    .isInstanceOf(RegistrarActoCoactivo.Rec1SinNotificar.class);
        }

        @Test
        @DisplayName("no hay REC-2 con el plazo corriendo, y el mensaje dice desde cuando si")
        void noHayRec2ConElPlazoCorriendo() {
            String expediente = expedienteConDeuda("A-0005");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NOTIFICADO);

            assertThatThrownBy(
                            () ->
                                    dictarActo(
                                            expediente,
                                            TipoDeActoCoactivo.REC2,
                                            REC2_DESDE.minusDays(1),
                                            TipoDeMedidaCautelar.DEPOSITO))
                    .isInstanceOf(RegistrarActoCoactivo.PlazoDeLaRec1EnCurso.class)
                    .hasMessageContaining(REC2_DESDE.toString());
        }

        @Test
        @DisplayName("la negativa a recibir SI abre el plazo (art. 104 a): la REC-2 procede")
        void laNegativaAbreElPlazo() {
            String expediente = expedienteConDeuda("A-0006");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            NotificarActoCoactivo.Diligencia negativa =
                    notificarActo(
                            rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.RECHAZADO);

            assertThat(negativa.notificacion().exigibleDesde()).isEqualTo(REC2_DESDE);
            assertThat(
                            dictarActo(
                                            expediente,
                                            TipoDeActoCoactivo.REC2,
                                            REC2_DESDE,
                                            TipoDeMedidaCautelar.INSCRIPCION)
                                    .estado())
                    .isEqualTo(EstadoDelExpediente.REC2_EMITIDA);
        }

        @Test
        @DisplayName("sobre un expediente concluido no se dicta nada mas")
        void elConcluidoNoAdmiteMas() {
            String expediente = expedienteConDeuda("A-0007");
            dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null);
            enTransaccion(() -> cambiarAConcluido(expediente));

            assertThatThrownBy(
                            () ->
                                    dictarActo(
                                            expediente,
                                            TipoDeActoCoactivo.EMBARGO,
                                            REC2_DESDE,
                                            null))
                    .isInstanceOf(CambiarEstadoDelExpediente.ExpedienteConcluido.class);
        }
    }

    @Nested
    @DisplayName("La diligencia: el reintento no pierde el intento anterior")
    class LaDiligencia {

        @Test
        @DisplayName("una no hallada se reintenta, y la primera se queda donde estaba")
        void elReintentoConservaLaAnterior() {
            String expediente = expedienteConDeuda("N-0001");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();

            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NO_UBICADO);
            NotificarActoCoactivo.Diligencia segunda =
                    notificarActo(
                            rec1.numero(),
                            DILIGENCIA_REC1.plusDays(7),
                            ResultadoDeNotificacion.NOTIFICADO);

            List<NotificacionCoactiva> traza =
                    enTransaccion(() -> diligencias.deActo(rec1.identificador()));

            assertThat(traza)
                    .as(
                            "dos filas, no una corregida: la constancia del intento fallido sostiene"
                                    + " una notificacion por cedulon")
                    .hasSize(2);
            assertThat(traza.get(0).intento()).isEqualTo(1);
            assertThat(traza.get(0).resultado()).isEqualTo(ResultadoDeNotificacion.NO_UBICADO);
            assertThat(traza.get(0).exigibleDesde()).isNull();
            assertThat(traza.get(1).intento()).isEqualTo(2);
            assertThat(segunda.notificacion().intento()).isEqualTo(2);
            assertThat(traza)
                    .as("cada diligencia dice quien la registro y por que (regla 10)")
                    .allSatisfy(
                            d -> {
                                assertThat(d.usuarioRegistro()).isEqualTo("ejecutor.coactivo");
                                assertThat(d.observacion().texto()).isNotBlank();
                            });
        }

        @Test
        @DisplayName("el plazo se cuenta desde la PRIMERA que surtio efecto, no desde la ultima")
        void elPlazoSeCuentaDesdeLaPrimeraQueSurtioEfecto() {
            String expediente = expedienteConDeuda("N-0002");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();

            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NOTIFICADO);
            notificarActo(
                    rec1.numero(),
                    DILIGENCIA_REC1.plusDays(30),
                    ResultadoDeNotificacion.NOTIFICADO);

            assertThat(
                            enTransaccion(() -> diligencias.queSurtioEfecto(rec1.identificador()))
                                    .orElseThrow()
                                    .exigibleDesde())
                    .as("el plazo ya habia empezado a correr con la primera")
                    .isEqualTo(REC2_DESDE);
        }

        @Test
        @DisplayName("la diligencia anterior al acto se rechaza: no se notifica lo que no existe")
        void laDiligenciaAnteriorAlActoSeRechaza() {
            String expediente = expedienteConDeuda("N-0003");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();

            assertThatThrownBy(
                            () ->
                                    notificarActo(
                                            rec1.numero(),
                                            REC1.minusDays(1),
                                            ResultadoDeNotificacion.NOTIFICADO))
                    .isInstanceOf(NotificarActoCoactivo.DiligenciaAnteriorAlActo.class);
        }

        @Test
        @DisplayName(
                "las diligencias de un valor y las de un acto no se mezclan, aun compartiendo"
                        + " tabla")
        void lasRebanadasNoSeMezclan() {
            String expediente = expedienteConDeuda("N-0004");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NOTIFICADO);

            // El valor del expediente tiene su propia diligencia, de #39, en la MISMA tabla.
            assertThat(enTransaccion(() -> diligencias.deActo(rec1.identificador())))
                    .as("solo la del acto: la columna `objeto` es la que separa las rebanadas")
                    .hasSize(1);
            assertThat(
                            enTransaccion(
                                    () ->
                                            notificacionesDeValor.deValor(
                                                    valorDelExpediente(expediente))))
                    .as("y la del valor sigue siendo la suya")
                    .hasSize(1);
        }

        /**
         * <b>La diligencia de un acto de tipo largo cabe</b> (#408).
         *
         * <p>El numero de la diligencia es el del acto, una barra y el intento, y el del acto lleva
         * el tipo: {@code MEDIDA_CAUTELAR-2026-000001/1} tiene 29 caracteres, y hasta #408 el
         * dominio y la columna topaban en 20. Todas las demas pruebas notifican la REC-1, cuyo
         * numero mide 18: la muestra uniforme es la que lo escondia. Se notifican los dos que el
         * issue nombra —el mas largo, y uno de los que se dictan con la cobranza ya terminada— y se
         * relee de la base, que era el segundo tope.
         */
        @Test
        @DisplayName(
                "#408 — la diligencia de una MEDIDA_CAUTELAR (29) y la de una CONCLUSION (24) se"
                        + " registran: el numero lleva el tipo del acto")
        void laDiligenciaDeUnActoDeTipoLargoCabe() {
            String expediente = expedienteConDeuda("N-0408");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            // Desde #405 la medida no se dicta sin la REC-2 que la ordena: la cadena entera.
            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NOTIFICADO);
            dictarActo(
                    expediente,
                    TipoDeActoCoactivo.REC2,
                    REC2_DESDE,
                    TipoDeMedidaCautelar.RETENCION);

            ActoCoactivo medida =
                    dictarActo(expediente, TipoDeActoCoactivo.MEDIDA_CAUTELAR, REC2_DESDE, null)
                            .acto();
            NotificarActoCoactivo.Diligencia deLaMedida =
                    notificarActo(medida.numero(), REC2_DESDE, ResultadoDeNotificacion.NOTIFICADO);

            ActoCoactivo conclusion =
                    dictarActo(expediente, TipoDeActoCoactivo.CONCLUSION, REC2_DESDE, null).acto();
            NotificarActoCoactivo.Diligencia deLaConclusion =
                    notificarActo(
                            conclusion.numero(),
                            REC2_DESDE.plusDays(1),
                            ResultadoDeNotificacion.NOTIFICADO);

            assertThat(deLaMedida.notificacion().numero())
                    .as("el numero que imprime el cargo: el del acto, la barra y el intento")
                    .isEqualTo(medida.numero() + "/1")
                    .startsWith("MEDIDA_CAUTELAR-2026-")
                    .hasSize(29);
            assertThat(deLaConclusion.notificacion().numero())
                    .isEqualTo(conclusion.numero() + "/1")
                    .startsWith("CONCLUSION-2026-")
                    .hasSize(24);
            assertThat(enTransaccion(() -> diligencias.deActo(medida.identificador())))
                    .as("y la columna lo guarda entero: no basta con que el dominio lo admita")
                    .extracting(NotificacionCoactiva::numero)
                    .containsExactly(medida.numero() + "/1");
            assertThat(enTransaccion(() -> diligencias.deActo(conclusion.identificador())))
                    .extracting(NotificacionCoactiva::numero)
                    .containsExactly(conclusion.numero() + "/1");
        }
    }

    /**
     * #409 — El estado del expediente no retrocede por una diligencia, ni vuelve a {@code INICIADO}
     * por el cambio de estado.
     *
     * <p><b>La siembra que distingue</b> es una diligencia <b>fallida</b> —o ya eficaz—, luego un
     * acto que mueve el expediente, luego la diligencia eficaz. Dos {@code NOTIFICADO} seguidas —lo
     * que {@code elPlazoSeCuentaDesdeLaPrimeraQueSurtioEfecto} registra— dejan {@code
     * REC1_NOTIFICADA} con el codigo bueno y con el malo: sin un acto entre las dos, no hay nada a
     * lo que retroceder.
     */
    @Nested
    @DisplayName("#409 — El estado no retrocede")
    class ElEstadoNoRetrocede {

        @Test
        @DisplayName(
                "A1 — el cargo de la 2.a diligencia llega despues de la suspension: el expediente"
                        + " sigue SUSPENDIDO")
        void laDiligenciaTardiaNoLevantaLaSuspension() {
            String expediente = expedienteConDeuda("E-4091");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NO_UBICADO);
            RegistrarActoCoactivo.ActoDictado suspension =
                    dictarActo(
                            expediente,
                            TipoDeActoCoactivo.SUSPENSION,
                            DILIGENCIA_REC1.plusDays(5),
                            null);
            assertThat(suspension.estado()).isEqualTo(EstadoDelExpediente.SUSPENDIDO);
            int antes = historialDe(expediente).size();

            // El cargo vuelve DESPUES de dictada la suspension, con fecha ANTERIOR a ella.
            NotificarActoCoactivo.Diligencia tardia =
                    notificarActo(
                            rec1.numero(),
                            DILIGENCIA_REC1.plusDays(2),
                            ResultadoDeNotificacion.NOTIFICADO);

            assertThat(tardia.estado())
                    .as(
                            "un hecho que no es un acto del ejecutor no deshace la suspension:"
                                    + " la diligencia no la dicto nadie")
                    .isEqualTo(EstadoDelExpediente.SUSPENDIDO);
            assertThat(EstadoDelExpediente.delHistorial(historialDe(expediente)))
                    .as("y el derivado —grilla, filtro, resumen de cartera— dice lo mismo")
                    .isEqualTo(EstadoDelExpediente.SUSPENDIDO);
            assertThat(historialDe(expediente))
                    .as("el historial no gana ninguna fila: no hubo acto del procedimiento")
                    .hasSize(antes);
            assertThat(tardia.notificacion().exigibleDesde())
                    .as("la diligencia si surtio efecto, y se registra con su exigibilidad")
                    .isNotNull();
        }

        @Test
        @DisplayName(
                "A2 — otra diligencia eficaz de la REC-1 con la medida trabada: el expediente"
                        + " sigue en MEDIDA_CAUTELAR")
        void laDiligenciaTardiaNoDestrabaLaMedida() {
            String expediente = expedienteConDeuda("E-4092");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.RECHAZADO);
            dictarActo(
                    expediente,
                    TipoDeActoCoactivo.REC2,
                    REC2_DESDE,
                    TipoDeMedidaCautelar.RETENCION);
            RegistrarActoCoactivo.ActoDictado embargo =
                    dictarActo(
                            expediente, TipoDeActoCoactivo.EMBARGO, REC2_DESDE.plusDays(2), null);
            assertThat(embargo.estado()).isEqualTo(EstadoDelExpediente.MEDIDA_CAUTELAR);
            int antes = historialDe(expediente).size();

            // El cargo personal que llega despues de la certificacion de la negativa.
            NotificarActoCoactivo.Diligencia personal =
                    notificarActo(
                            rec1.numero(),
                            REC2_DESDE.plusDays(3),
                            ResultadoDeNotificacion.NOTIFICADO);

            assertThat(personal.estado())
                    .as("la medida sigue trabada: el procedimiento no vuelve a la REC-1")
                    .isEqualTo(EstadoDelExpediente.MEDIDA_CAUTELAR);
            assertThat(EstadoDelExpediente.delHistorial(historialDe(expediente)))
                    .isEqualTo(EstadoDelExpediente.MEDIDA_CAUTELAR);
            assertThat(historialDe(expediente)).hasSize(antes);
        }

        @Test
        @DisplayName(
                "la fallida y luego la eficaz, sin acto entre ellas, SI avanzan a REC1_NOTIFICADA")
        void laEficazTrasLaFallidaAvanza() {
            String expediente = expedienteConDeuda("E-4093");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NO_UBICADO);

            NotificarActoCoactivo.Diligencia eficaz =
                    notificarActo(
                            rec1.numero(),
                            DILIGENCIA_REC1.plusDays(7),
                            ResultadoDeNotificacion.NOTIFICADO);

            assertThat(eficaz.estado())
                    .as("la politica no congela el avance: solo impide el retroceso")
                    .isEqualTo(EstadoDelExpediente.REC1_NOTIFICADA);
            assertThat(historialDe(expediente))
                    .extracting(MovimientoDelExpediente::estado)
                    .containsExactly(
                            EstadoDelExpediente.INICIADO,
                            EstadoDelExpediente.REC1_EMITIDA,
                            EstadoDelExpediente.REC1_NOTIFICADA);
        }

        @Test
        @DisplayName(
                "B — volver a INICIADO por el cambio de estado se rechaza: nadie podria dictarle"
                        + " otra REC-1")
        void elCambioAIniciadoSeRechaza() {
            String expediente = expedienteConDeuda("E-4094");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NOTIFICADO);
            int antes = historialDe(expediente).size();

            assertThatThrownBy(
                            () ->
                                    cambiarEstado.cambiar(
                                            expediente,
                                            EstadoDelExpediente.INICIADO,
                                            DILIGENCIA_REC1.plusDays(1),
                                            "x",
                                            null,
                                            null,
                                            PORQUE))
                    .as(
                            "IllegalArgumentException es la que el controlador traduce a 422;"
                                    + " sin la guarda del movimiento la rechazaria la base, y el"
                                    + " PATCH saldria 500")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("INICIADO");
            assertThat(historialDe(expediente)).hasSize(antes);
            assertThat(EstadoDelExpediente.delHistorial(historialDe(expediente)))
                    .isEqualTo(EstadoDelExpediente.REC1_NOTIFICADA);
        }

        @Test
        @DisplayName("B — y un movimiento ESTADO a INICIADO tampoco entra por SQL directo")
        void elMovimientoAIniciadoNoEntraPorSql() {
            String expediente = expedienteConDeuda("E-4095");
            dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null);
            long expedienteId = idDelExpediente(expediente);

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "INSERT INTO expediente_movimiento"
                                                            + " (municipalidad_id, expediente_id,"
                                                            + " tipo, estado, fecha, motivo,"
                                                            + " usuario_registro, fecha_registro,"
                                                            + " observacion) VALUES ("
                                                            + municipalidad
                                                            + ", "
                                                            + expedienteId
                                                            + ", 'ESTADO', 'INICIADO', DATE '"
                                                            + REC1.plusDays(1)
                                                            + "', 'x', 'intruso', now(), 'sin"
                                                            + " pasar por el codigo')")))
                    .as(
                            "23514 es «viola una restriccion CHECK»:"
                                    + " expediente_movimiento_iniciado_ck")
                    .isEqualTo("23514");
            assertThat(EstadoDelExpediente.delHistorial(historialDe(expediente)))
                    .isEqualTo(EstadoDelExpediente.REC1_EMITIDA);
        }

        private List<MovimientoDelExpediente> historialDe(String expediente) {
            long id = idDelExpediente(expediente);
            return enTransaccion(() -> movimientos.deExpediente(id));
        }
    }

    /**
     * #405 — Un acto que ordena o ejecuta la medida cautelar no se dicta sin el acto que lo
     * sustenta.
     *
     * <p><b>La siembra que distingue</b> es la que ninguna otra prueba de esta clase hace: pedir el
     * embargo <b>sin</b> dictar antes la REC-2. Todas las demas la dictan primero —la muestra
     * uniforme—, y con ella la guarda buena y la ausente dan el mismo 201. Hasta #405 la unica
     * guarda del plazo de la REC-1 estaba atada al tipo {@code REC2}: cambiar el tipo del acto a
     * {@code EMBARGO} bastaba para saltarse el plazo del art. 14.1 de la Ley 26979.
     *
     * <p>El contraste mas fino es el primero: la REC-1 notificada y el plazo corriendo. La REC-2 ya
     * salia rechazada por {@code PlazoDeLaRec1EnCurso}; el embargo del mismo dia tiene que salir
     * rechazado tambien, y el expediente tiene que seguir donde estaba.
     */
    @Nested
    @DisplayName("#405 — Ningun acto de la medida sin el acto que lo sustenta")
    class ElActoPrevio {

        @Test
        @DisplayName(
                "REC-1 notificada y plazo corriendo: la REC-2 se rechaza, y el EMBARGO del mismo"
                        + " dia tambien")
        void elEmbargoConElPlazoCorriendoSeRechaza() {
            String expediente = expedienteConDeuda("P-4051");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NOTIFICADO);
            LocalDate enPlazo = REC2_DESDE.minusDays(1);

            assertThatThrownBy(
                            () ->
                                    dictarActo(
                                            expediente,
                                            TipoDeActoCoactivo.REC2,
                                            enPlazo,
                                            TipoDeMedidaCautelar.RETENCION))
                    .as("el contraste que ya estaba probado: la REC-2 respeta el plazo")
                    .isInstanceOf(RegistrarActoCoactivo.PlazoDeLaRec1EnCurso.class);
            assertThatThrownBy(
                            () -> dictarActo(expediente, TipoDeActoCoactivo.EMBARGO, enPlazo, null))
                    .as(
                            "el embargo sin REC-2 es la medida sin la resolucion que la ordena:"
                                    + " cambiar el tipo del acto no salta el plazo")
                    .isInstanceOf(RegistrarActoCoactivo.ActoPrevioSinDictar.class)
                    .hasMessageContaining(TipoDeActoCoactivo.REC2.titulo());

            assertThat(estadoDe(expediente))
                    .as("el expediente sigue donde estaba: la cartera no lo cuenta con medida")
                    .isEqualTo(EstadoDelExpediente.REC1_NOTIFICADA);
            assertThat(tiposDictados(expediente))
                    .as("y no queda ningun acto numerado de mas, ni su documento")
                    .containsExactly(TipoDeActoCoactivo.REC1);
        }

        @Test
        @DisplayName("expediente INICIADO, sin ninguna REC: ni EMBARGO ni MEDIDA_CAUTELAR")
        void sobreUnExpedienteIniciadoNoHayMedida() {
            String expediente = expedienteConDeuda("P-4052");

            assertThatThrownBy(() -> dictarActo(expediente, TipoDeActoCoactivo.EMBARGO, REC1, null))
                    .isInstanceOf(RegistrarActoCoactivo.ActoPrevioSinDictar.class)
                    .hasMessageContaining(expediente);
            assertThatThrownBy(
                            () ->
                                    dictarActo(
                                            expediente,
                                            TipoDeActoCoactivo.MEDIDA_CAUTELAR,
                                            REC1,
                                            null))
                    .isInstanceOf(RegistrarActoCoactivo.ActoPrevioSinDictar.class);

            assertThat(estadoDe(expediente)).isEqualTo(EstadoDelExpediente.INICIADO);
            assertThat(tiposDictados(expediente)).isEmpty();
        }

        @Test
        @DisplayName("la TASACION exige la medida trabada, y cualquiera de las dos formas la traba")
        void laTasacionExigeLaMedidaTrabada() {
            String expediente = expedienteConRec2("P-4053");
            LocalDate despues = REC2_DESDE.plusDays(1);

            assertThatThrownBy(
                            () ->
                                    dictarActo(
                                            expediente, TipoDeActoCoactivo.TASACION, despues, null))
                    .as("con la REC-2 dictada pero sin nada trabado, no hay bien que tasar")
                    .isInstanceOf(RegistrarActoCoactivo.ActoPrevioSinDictar.class)
                    .hasMessageContaining(TipoDeActoCoactivo.EMBARGO.titulo())
                    .hasMessageContaining(TipoDeActoCoactivo.MEDIDA_CAUTELAR.titulo());

            dictarActo(expediente, TipoDeActoCoactivo.MEDIDA_CAUTELAR, despues, null);
            assertThat(dictarActo(expediente, TipoDeActoCoactivo.TASACION, despues, null).estado())
                    .as("la constancia de la medida basta: no hace falta ademas el acta de embargo")
                    .isEqualTo(EstadoDelExpediente.MEDIDA_CAUTELAR);
        }

        @Test
        @DisplayName("el REMATE exige la TASACION: el embargo solo no basta")
        void elRemateExigeLaTasacion() {
            String iniciado = expedienteConDeuda("P-4054");
            assertThatThrownBy(() -> dictarActo(iniciado, TipoDeActoCoactivo.REMATE, REC1, null))
                    .as("el escenario del issue: REMATE sobre INICIADO, sin embargo ni tasacion")
                    .isInstanceOf(RegistrarActoCoactivo.ActoPrevioSinDictar.class);

            String expediente = expedienteConRec2("P-4055");
            LocalDate despues = REC2_DESDE.plusDays(1);
            dictarActo(expediente, TipoDeActoCoactivo.EMBARGO, despues, null);

            assertThatThrownBy(
                            () -> dictarActo(expediente, TipoDeActoCoactivo.REMATE, despues, null))
                    .as("se remata el bien TASADO: sin tasacion no hay precio base")
                    .isInstanceOf(RegistrarActoCoactivo.ActoPrevioSinDictar.class)
                    .hasMessageContaining(TipoDeActoCoactivo.TASACION.titulo());

            dictarActo(expediente, TipoDeActoCoactivo.TASACION, despues, null);
            assertThat(dictarActo(expediente, TipoDeActoCoactivo.REMATE, despues, null).acto())
                    .extracting(ActoCoactivo::tipo)
                    .isEqualTo(TipoDeActoCoactivo.REMATE);
            assertThat(tiposDictados(expediente))
                    .containsExactly(
                            TipoDeActoCoactivo.REC1,
                            TipoDeActoCoactivo.REC2,
                            TipoDeActoCoactivo.EMBARGO,
                            TipoDeActoCoactivo.TASACION,
                            TipoDeActoCoactivo.REMATE);
        }

        @Test
        @DisplayName("los actos que no ejecutan la medida no exigen nada antes")
        void losDemasNoExigenNada() {
            String expediente = expedienteConDeuda("P-4056");

            assertThat(dictarActo(expediente, TipoDeActoCoactivo.OTRO, REC1, null).acto().tipo())
                    .as(
                            "un OTRO sobre un expediente recien importado entra: la guarda es de"
                                    + " los cuatro actos de la medida, no de todos")
                    .isEqualTo(TipoDeActoCoactivo.OTRO);
            assertThat(dictarActo(expediente, TipoDeActoCoactivo.SUSPENSION, REC1, null).estado())
                    .isEqualTo(EstadoDelExpediente.SUSPENDIDO);
        }

        /**
         * Un expediente con la REC-1 notificada y la REC-2 dictada el primer dia en que procede.
         */
        private String expedienteConRec2(String sufijo) {
            String expediente = expedienteConDeuda(sufijo);
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NOTIFICADO);
            dictarActo(
                    expediente,
                    TipoDeActoCoactivo.REC2,
                    REC2_DESDE,
                    TipoDeMedidaCautelar.RETENCION);
            return expediente;
        }

        private EstadoDelExpediente estadoDe(String expediente) {
            long id = idDelExpediente(expediente);
            return EstadoDelExpediente.delHistorial(
                    enTransaccion(() -> movimientos.deExpediente(id)));
        }

        private List<TipoDeActoCoactivo> tiposDictados(String expediente) {
            long id = idDelExpediente(expediente);
            return enTransaccion(() -> actos.deExpediente(id)).stream()
                    .map(ActoCoactivo::tipo)
                    .toList();
        }
    }

    @Nested
    @DisplayName("El papel: la REC se reimprime identica anos despues")
    class ElPapel {

        @Test
        @DisplayName("la reimpresion devuelve el mismo resumen y sale marcada como duplicado")
        void laReimpresionEsIdentica() {
            String expediente = expedienteConDeuda("D-0001");
            RegistrarActoCoactivo.ActoDictado rec1 =
                    dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null);
            String resumenOriginal = rec1.emision().registro().resumen();

            ReimprimirActoCoactivo.Reimpresion duplicado =
                    enTransaccion(
                            () ->
                                    reimprimir.reimprimir(
                                            rec1.acto().numero(),
                                            FormatoDeDocumento.PDF,
                                            Observacion.de("Lo pide el obligado")));

            assertThat(duplicado.emision().registro().resumen())
                    .as(
                            "el resumen es el de la PRIMERA emision, y se comprueba antes de"
                                    + " entregar: si no coincidiera, la reimpresion falla")
                    .isEqualTo(resumenOriginal);
            assertThat(duplicado.emision().registro().reimpresiones()).isEqualTo(1);
            assertThat(new String(duplicado.emision().contenido(), StandardCharsets.ISO_8859_1))
                    .as("un duplicado sin marcar circula como si fuera el original")
                    .contains("DUPLICADO N° 1");
            assertThat(new String(rec1.emision().contenido(), StandardCharsets.ISO_8859_1))
                    .doesNotContain("DUPLICADO");
        }

        @Test
        @DisplayName("y sale en el formato que se pida, no en el que se emitio")
        void laReimpresionEnOtroFormato() {
            String expediente = expedienteConDeuda("D-0002");
            RegistrarActoCoactivo.ActoDictado rec1 =
                    dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null);

            ReimprimirActoCoactivo.Reimpresion hoja =
                    enTransaccion(
                            () ->
                                    reimprimir.delExpediente(
                                            expediente,
                                            TipoDeActoCoactivo.REC1,
                                            FormatoDeDocumento.XLS,
                                            Observacion.de("Rentas quiere las cifras")));

            assertThat(hoja.acto().numero()).isEqualTo(rec1.acto().numero());
            assertThat(new String(hoja.emision().contenido(), StandardCharsets.UTF_8))
                    .contains("<?xml")
                    .contains("Deuda actualizada al " + REC1);
        }

        @Test
        @DisplayName("si el renderizador cambia, la reimpresion FALLA en vez de dar otro papel")
        void siElRenderizadorCambiaLaReimpresionFalla() {
            String expediente = expedienteConDeuda("D-0004");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();

            // La unica forma real de que la reimpresion difiera es que cambie el dibujo entre la
            // emision y el duplicado: los datos son los mismos, estan guardados. Se simula con un
            // segundo generador que anade un byte, que es lo que hace un margen o una fuente
            // distinta. Sin la comprobacion del SHA-256, esto entregaria un papel distinto al
            // original CON EL MISMO NUMERO, y en un procedimiento coactivo eso anula la
            // resolucion.
            ReimprimirActoCoactivo conOtroDibujo =
                    envolver(
                            new ReimprimirActoCoactivo(
                                    actos, expedientes, documentosConOtroDibujo));

            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    conOtroDibujo.reimprimir(
                                                            rec1.numero(),
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(EmitirDocumento.LaReimpresionNoCoincide.class)
                    .hasMessageContaining("ya no se dibuja igual");
        }

        @Test
        @DisplayName("reimprimir lo que nunca se dicto no emite nada")
        void reimprimirLoQueNoExiste() {
            String expediente = expedienteConDeuda("D-0003");

            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    reimprimir.delExpediente(
                                                            expediente,
                                                            TipoDeActoCoactivo.REC2,
                                                            FormatoDeDocumento.PDF,
                                                            PORQUE)))
                    .isInstanceOf(ReimprimirActoCoactivo.ActoSinDictar.class);
        }
    }

    @Nested
    @DisplayName("Lo que la base impide, y no un `if`")
    class LoQueLaBaseImpide {

        @Test
        @DisplayName("diez REC-1 simultaneas dejan una: lo decide el indice, no la aplicacion")
        void diezRec1SimultaneasDejanUna() throws Exception {
            String expediente = expedienteConDeuda("B-0001");

            int hilos = 10;
            CountDownLatch salida = new CountDownLatch(1);
            List<Callable<Boolean>> intentos = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                intentos.add(
                        () -> {
                            salida.await(5, TimeUnit.SECONDS);
                            try {
                                dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null);
                                return true;
                            } catch (ActoCoactivoRepository.Rec1Duplicada
                                    | org.springframework.dao.DataAccessException rechazado) {
                                // Las dos formas en que pierde el que llega segundo: la traducida
                                // -acto_rec1_uq- y la que pueda venir del motor si dos
                                // transacciones se pisan. Cualquier otra sube y rompe la prueba.
                                return false;
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
                int abiertas = 0;
                for (Future<Boolean> futuro : futuros) {
                    if (Boolean.TRUE.equals(futuro.get(30, TimeUnit.SECONDS))) {
                        abiertas++;
                    }
                }
                assertThat(abiertas)
                        .as("dos resoluciones de inicio se contradicen en el mismo expediente")
                        .isEqualTo(1);
            } finally {
                piscina.shutdownNow();
            }

            assertThat(cuantosActos(expediente, "REC1")).isEqualTo(1);
        }

        /**
         * #427 — El intento de una diligencia se numera con el candado del acto tomado.
         *
         * <p>Hasta #427, {@code intentosDe} leia {@code max(intento)} en una sentencia aparte y sin
         * candado: las diligencias simultaneas sobre el mismo acto calculaban el mismo intento y
         * todas menos una chocaban en {@code notificacion_intento_uq} con una {@code
         * DuplicateKeyException} —un 500 con incidencia en el borde—. Con el candado entran todas,
         * una detras de otra, con intentos consecutivos. Que un doble clic registre dos diligencias
         * es lo que ya pasa con un reenvio secuencial: la idempotencia es otra decision.
         */
        @Test
        @DisplayName("seis diligencias simultaneas del mismo acto entran las seis, intentos 1 a 6")
        void seisDiligenciasSimultaneasEntranTodas() throws Exception {
            String expediente = expedienteConDeuda("B-0427");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();

            int hilos = 6;
            CountDownLatch salida = new CountDownLatch(1);
            List<String> rechazos = java.util.Collections.synchronizedList(new ArrayList<>());
            List<Callable<Boolean>> intentos = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                intentos.add(
                        () -> {
                            salida.await(5, TimeUnit.SECONDS);
                            try {
                                notificarActo(
                                        rec1.numero(),
                                        DILIGENCIA_REC1,
                                        ResultadoDeNotificacion.NO_UBICADO);
                                return true;
                            } catch (org.springframework.dao.DataAccessException choque) {
                                rechazos.add(choque.getClass().getSimpleName());
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
                int registradas = 0;
                for (Future<Boolean> futuro : futuros) {
                    if (Boolean.TRUE.equals(futuro.get(30, TimeUnit.SECONDS))) {
                        registradas++;
                    }
                }
                assertThat(registradas)
                        .as(
                                "cada diligencia es un hecho del notificador: ninguna puede salir"
                                        + " con 500 porque otra se registraba a la vez (rechazos:"
                                        + " %s)",
                                rechazos)
                        .isEqualTo(hilos);
            } finally {
                piscina.shutdownNow();
            }

            List<NotificacionCoactiva> traza =
                    enTransaccion(() -> diligencias.deActo(rec1.identificador()));
            assertThat(traza)
                    .extracting(NotificacionCoactiva::intento)
                    .as("consecutivos, sin huecos y sin repetir")
                    .containsExactly(1, 2, 3, 4, 5, 6);
            assertThat(traza)
                    .extracting(NotificacionCoactiva::numero)
                    .as("y el numero de cada diligencia es el del acto con su intento")
                    .containsExactly(
                            rec1.numero() + "/1",
                            rec1.numero() + "/2",
                            rec1.numero() + "/3",
                            rec1.numero() + "/4",
                            rec1.numero() + "/5",
                            rec1.numero() + "/6");
        }

        @Test
        @DisplayName("una REC-2 fechada antes del plazo la rechaza el CHECK, no la aplicacion")
        void laRec2PrematuraLaRechazaElCheck() {
            String expediente = expedienteConDeuda("B-0002");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            NotificarActoCoactivo.Diligencia acuse =
                    notificarActo(
                            rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NOTIFICADO);
            long expedienteId = idDelExpediente(expediente);

            // Por SQL directo: es como se salta cualquier comprobacion escrita en Java.
            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "INSERT INTO acto_coactivo (municipalidad_id,"
                                                            + " expediente_id, tipo, numero, fecha,"
                                                            + " descripcion, medida,"
                                                            + " rec1_notificacion_id,"
                                                            + " rec1_exigible_desde, documento_id,"
                                                            + " usuario_registro, fecha_registro,"
                                                            + " observacion) VALUES ("
                                                            + municipalidad
                                                            + ", "
                                                            + expedienteId
                                                            + ", 'REC2', 'REC2-SQL-1', DATE '"
                                                            + REC2_DESDE.minusDays(1)
                                                            + "', 'por SQL', 'RETENCION', "
                                                            + acuse.notificacion().identificador()
                                                            + ", DATE '"
                                                            + REC2_DESDE
                                                            + "', "
                                                            + documentoDe(rec1)
                                                            + ", 'intruso', now(), 'sin pasar por"
                                                            + " el codigo')")))
                    .as("23514 es «viola una restriccion CHECK»: acto_rec2_plazo_ck")
                    .isEqualTo("23514");
        }

        @Test
        @DisplayName("una REC-2 sin sustento tampoco entra por SQL directo")
        void laRec2SinSustentoTampocoEntra() {
            String expediente = expedienteConDeuda("B-0003");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            long expedienteId = idDelExpediente(expediente);

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "INSERT INTO acto_coactivo (municipalidad_id,"
                                                            + " expediente_id, tipo, numero, fecha,"
                                                            + " descripcion, medida, documento_id,"
                                                            + " usuario_registro, fecha_registro,"
                                                            + " observacion) VALUES ("
                                                            + municipalidad
                                                            + ", "
                                                            + expedienteId
                                                            + ", 'REC2', 'REC2-SQL-2', DATE '"
                                                            + REC2_DESDE
                                                            + "', 'por SQL', 'RETENCION', "
                                                            + documentoDe(rec1)
                                                            + ", 'intruso', now(), 'sin"
                                                            + " sustento')")))
                    .as("acto_rec2_sustento_ck: el sustento va entero o no va")
                    .isEqualTo("23514");
        }

        @Test
        @DisplayName("un acto que no es REC-2 con medida pegada tampoco")
        void unActoConMedidaPegadaTampoco() {
            String expediente = expedienteConDeuda("B-0004");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            long expedienteId = idDelExpediente(expediente);

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "INSERT INTO acto_coactivo (municipalidad_id,"
                                                            + " expediente_id, tipo, numero, fecha,"
                                                            + " descripcion, medida, documento_id,"
                                                            + " usuario_registro, fecha_registro,"
                                                            + " observacion) VALUES ("
                                                            + municipalidad
                                                            + ", "
                                                            + expedienteId
                                                            + ", 'EMBARGO', 'EMB-SQL-1', DATE '"
                                                            + REC2_DESDE
                                                            + "', 'por SQL', 'DEPOSITO', "
                                                            + documentoDe(rec1)
                                                            + ", 'intruso', now(), 'medida sin"
                                                            + " resolucion')")))
                    .as("acto_medida_ck: la medida la ordena la REC-2 y solo ella")
                    .isEqualTo("23514");
        }

        @Test
        @DisplayName("kamayuk_app no puede editar un acto: el privilegio no existe")
        void noSePuedeEditarUnActo() {
            String expediente = expedienteConDeuda("B-0005");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "UPDATE acto_coactivo SET descripcion = 'otra"
                                                            + " cosa' WHERE id = "
                                                            + rec1.identificador())))
                    .as("42501 es «privilegio insuficiente»: el REVOKE de V34")
                    .isEqualTo("42501");
        }

        @Test
        @DisplayName("ni borrarlo, ni borrar la diligencia que lo notifico")
        void niBorrarlo() {
            String expediente = expedienteConDeuda("B-0006");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NOTIFICADO);

            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "DELETE FROM acto_coactivo WHERE id = "
                                                            + rec1.identificador())))
                    .isEqualTo("42501");
            assertThat(
                            estadoSqlDelFallo(
                                    () ->
                                            ejecutarComoApp(
                                                    "DELETE FROM notificacion WHERE objeto ="
                                                            + " 'ACTO_COACTIVO' AND objeto_id = "
                                                            + rec1.identificador())))
                    .isEqualTo("42501");
        }

        @Test
        @DisplayName("desde otra municipalidad el acto no existe: RLS")
        void desdeOtraMunicipalidadNoExiste() {
            String expediente = expedienteConDeuda("B-0007");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NOTIFICADO);

            assertThat(enTransaccionDe(otraMunicipalidad, () -> actos.porNumero(rec1.numero())))
                    .as("la politica de V6 filtra por municipalidad, y no es opcional")
                    .isEmpty();
            assertThat(
                            enTransaccionDe(
                                    otraMunicipalidad,
                                    () -> diligencias.deActo(rec1.identificador())))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("El plazo es un dato, no un numero compilado")
    class ElPlazo {

        @Test
        @DisplayName("sin el parametro sellado no se notifica: falla nombrando la llave")
        void sinElParametroNoSeNotifica() {
            // El ejercicio 2025 no tiene conjunto sellado en esta base: es la misma situacion que
            // un 2026 sellado SIN el plazo de la REC-1, y las dos fallan antes de escribir nada.
            String expediente = expedienteConDeuda("P-0001");
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();

            assertThatThrownBy(
                            () ->
                                    notificarActo(
                                            rec1.numero(),
                                            LocalDate.of(2027, 3, 1),
                                            ResultadoDeNotificacion.NOTIFICADO))
                    .as(
                            "un plazo inventado produce medidas cautelares nulas: mejor que la"
                                    + " operacion se pare (regla 5)")
                    .isInstanceOf(RuntimeException.class);

            assertThat(enTransaccion(() -> diligencias.deActo(rec1.identificador())))
                    .as("y no deja ninguna fila a medias")
                    .isEmpty();
        }
    }

    /**
     * #404 — La guarda de deuda viva mira el dia del acto, y la proyeccion no puede ir hacia atras.
     *
     * <p>La siembra que distingue es un <b>pago entre las dos fechas</b>: cargo en enero, abono por
     * el integro el {@link #PAGO}, y la REC-2 el {@link #ACTO}. Sin el pago en medio, proyectar al
     * 1 de setiembre o al 20 da la misma cifra y la prueba no ve nada —que es lo que tenia la
     * muestra de antes, con proyecciones solo hacia el futuro—. Con el pago, la deuda al 1 de
     * setiembre es 500,00 y al 20 es cero: una guarda que lea la proyeccion dicta la medida
     * cautelar contra quien ya pago, y una que lea el dia del acto la rechaza.
     */
    @Nested
    @DisplayName("#404 — la proyeccion no lleva la guarda de deuda viva a antes del acto")
    class LaProyeccionNoVaHaciaAtras {

        /** El obligado paga el integro: el abono se asienta con esta fecha valor. */
        private static final LocalDate PAGO = LocalDate.of(2026, 9, 10);

        /** El dia de la REC-2: diez dias despues del pago, y con el plazo de la REC-1 vencido. */
        private static final LocalDate ACTO = LocalDate.of(2026, 9, 20);

        @Test
        @DisplayName("proyectar al 1 de setiembre, antes del pago y del acto, no dicta la REC-2")
        void haciaAtrasNoSeDicta() {
            String expediente = expedientePagadoConLaRec1Vencida("G-4041");

            assertThatThrownBy(
                            () ->
                                    dictarProyectando(
                                            expediente,
                                            TipoDeActoCoactivo.REC2,
                                            ACTO,
                                            TipoDeMedidaCautelar.RETENCION,
                                            LocalDate.of(2026, 9, 1)))
                    .as(
                            "la deuda al 1 de setiembre no ve el abono del 10: si la guarda la"
                                    + " leyera, se trabaria la retencion sobre quien ya pago")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2026-09-01")
                    .hasMessageContaining(ACTO.toString());

            assertThat(cuantosActos(expediente, "REC2"))
                    .as("y no queda ninguna REC-2 dictada")
                    .isZero();
        }

        @Test
        @DisplayName("sin proyeccion, la guarda lee el dia del acto: DeudaExtinguida")
        void sinProyeccionDeudaExtinguida() {
            String expediente = expedientePagadoConLaRec1Vencida("G-4042");

            assertThatThrownBy(
                            () ->
                                    dictarProyectando(
                                            expediente,
                                            TipoDeActoCoactivo.REC2,
                                            ACTO,
                                            TipoDeMedidaCautelar.RETENCION,
                                            null))
                    .isInstanceOf(RegistrarActoCoactivo.DeudaExtinguida.class)
                    .hasMessageContaining("no tiene deuda al " + ACTO);
            assertThat(cuantosActos(expediente, "REC2")).isZero();
        }

        @Test
        @DisplayName(
                "proyectar hacia adelante sigue decidiendo con la cifra impresa: DeudaExtinguida")
        void haciaAdelanteDeudaExtinguida() {
            String expediente = expedientePagadoConLaRec1Vencida("G-4043");
            LocalDate finDeMes = LocalDate.of(2026, 9, 30);

            assertThatThrownBy(
                            () ->
                                    dictarProyectando(
                                            expediente,
                                            TipoDeActoCoactivo.REC2,
                                            ACTO,
                                            TipoDeMedidaCautelar.RETENCION,
                                            finDeMes))
                    .as(
                            "hacia el futuro la decision de que decida la cifra impresa se"
                                    + " mantiene: el pago ya esta dentro de las dos fechas")
                    .isInstanceOf(RegistrarActoCoactivo.DeudaExtinguida.class)
                    .hasMessageContaining("no tiene deuda al " + finDeMes);
            assertThat(cuantosActos(expediente, "REC2")).isZero();
        }

        /** REC-1 dictada y notificada, plazo vencido, y el integro pagado el {@link #PAGO}. */
        private String expedientePagadoConLaRec1Vencida(String sufijo) {
            String expediente = expedienteConDeuda(sufijo);
            ActoCoactivo rec1 = dictarActo(expediente, TipoDeActoCoactivo.REC1, REC1, null).acto();
            notificarActo(rec1.numero(), DILIGENCIA_REC1, ResultadoDeNotificacion.NOTIFICADO);
            pagarTodo(expediente, PAGO);
            return expediente;
        }
    }

    // ==================================================================
    //  Utilidades
    // ==================================================================

    /**
     * El contexto se fija <b>antes</b> de abrir la transaccion, no dentro: {@code
     * TenantTransactionManager} lo lee al comenzarla para emitir el {@code SET LOCAL}.
     */
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

    private static RegistrarActoCoactivo.ActoDictado dictarActo(
            String expediente,
            TipoDeActoCoactivo tipo,
            LocalDate fecha,
            TipoDeMedidaCautelar medida) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("ejecutor.coactivo", null, null));
        return dictar.dictar(
                new RegistrarActoCoactivo.Peticion(
                        expediente, tipo, fecha, tipo.titulo() + " de la prueba", medida, null),
                FormatoDeDocumento.PDF,
                PORQUE);
    }

    /** Como {@link #dictarActo}, con la fecha a la que se proyecta la deuda impresa (#404). */
    private static RegistrarActoCoactivo.ActoDictado dictarProyectando(
            String expediente,
            TipoDeActoCoactivo tipo,
            LocalDate fecha,
            TipoDeMedidaCautelar medida,
            LocalDate proyectarDeudaAl) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("ejecutor.coactivo", null, null));
        return dictar.dictar(
                new RegistrarActoCoactivo.Peticion(
                        expediente,
                        tipo,
                        fecha,
                        tipo.titulo() + " de la prueba",
                        medida,
                        proyectarDeudaAl),
                FormatoDeDocumento.PDF,
                PORQUE);
    }

    private static NotificarActoCoactivo.Diligencia notificarActo(
            String numeroDelActo, LocalDate fecha, ResultadoDeNotificacion resultado) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("ejecutor.coactivo", null, null));
        return notificar.registrar(
                numeroDelActo,
                fecha,
                ModalidadDeNotificacion.PERSONAL,
                resultado,
                "J. RUIZ PALACIOS",
                null,
                resultado == ResultadoDeNotificacion.NOTIFICADO ? "TITULAR, PRUEBA" : null,
                resultado == ResultadoDeNotificacion.NOTIFICADO ? "DNI 12345678" : null,
                resultado == ResultadoDeNotificacion.NOTIFICADO ? "TITULAR" : null,
                resultado == ResultadoDeNotificacion.NOTIFICADO ? "CARGO-REC1" : null,
                PORQUE);
    }

    /** Un expediente coactivo abierto, con su valor y su deuda de 500,00 en el libro. */
    private static String expedienteConDeuda(String sufijo) {
        long contribuyente = contribuyenteConDeuda(sufijo);
        Valor valor = emitir(contribuyente, "OP-2026-" + sufijo);
        pasarACoactiva(valor);
        return enTransaccion(
                        () ->
                                importar.importar(
                                        new ImportarValoresACoactiva.Peticion(
                                                contribuyente,
                                                List.of(),
                                                "R. MENDOZA CRUZ",
                                                null,
                                                "Cobranza coactiva",
                                                "AV. GRAU 100 - SULLANA"),
                                        IMPORTACION,
                                        PlantillaDeNumeroDeExpediente.POR_OMISION,
                                        PORQUE))
                .expedienteAbierto()
                .numero();
    }

    private static long idDelExpediente(String numero) {
        return enTransaccion(() -> expedientes.porNumero(numero)).orElseThrow().identificador();
    }

    private static long valorDelExpediente(String numero) {
        return enTransaccion(() -> expedientes.valoresDe(idDelExpediente(numero))).get(0).valorId();
    }

    private static long documentoDe(ActoCoactivo acto) {
        return acto.documentoId();
    }

    private static EstadoDelExpediente cambiarAConcluido(String expediente) {
        long id = idDelExpediente(expediente);
        movimientos.registrar(
                MovimientoDelExpediente.cambioDeEstado(
                        id,
                        EstadoDelExpediente.CONCLUIDO,
                        REC1.plusDays(1),
                        "conclusion de la prueba",
                        null,
                        null,
                        RELOJ.instant(),
                        PORQUE));
        return EstadoDelExpediente.CONCLUIDO;
    }

    private static int cuantosActos(String expediente, String tipo) {
        Integer cuantos =
                enTransaccion(
                        () ->
                                jdbc.sql(
                                                "SELECT count(*)::int FROM acto_coactivo"
                                                        + " WHERE expediente_id = :expediente AND tipo"
                                                        + " = :tipo")
                                        .param("expediente", idDelExpediente(expediente))
                                        .param("tipo", tipo)
                                        .query(Integer.class)
                                        .single());
        return cuantos == null ? 0 : cuantos;
    }

    /** Un contribuyente con su cargo de predial ya asentado en el libro. */
    private static long contribuyenteConDeuda(String sufijo) {
        long id = crearContribuyente(sufijo);
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        id,
                                        "PREDIAL",
                                        Concepto.INSOLUTO,
                                        TipoAsiento.CARGO,
                                        Fase.VALOR,
                                        null,
                                        null,
                                        null,
                                        null,
                                        PREDIAL,
                                        FECHA_DEL_CARGO,
                                        "DETERMINACION DE LA PRUEBA"),
                                Observacion.de("Se asienta la deuda de la prueba")));
        return id;
    }

    /** El obligado paga: un abono por el total, en el libro de verdad. */
    private static void pagarTodo(String expediente, LocalDate fecha) {
        long contribuyente =
                enTransaccion(() -> expedientes.porNumero(expediente))
                        .orElseThrow()
                        .contribuyenteId();
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        contribuyente,
                                        "PREDIAL",
                                        Concepto.INSOLUTO,
                                        TipoAsiento.ABONO,
                                        Fase.VALOR,
                                        null,
                                        null,
                                        null,
                                        null,
                                        PREDIAL,
                                        fecha,
                                        "PAGO TOTAL DE LA PRUEBA"),
                                Observacion.de("El obligado paga el integro")));
    }

    private static Valor emitir(long contribuyenteId, String numero) {
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
                                                null,
                                                null,
                                                null,
                                                PREDIAL,
                                                Dinero.CERO,
                                                Dinero.CERO,
                                                Dinero.CERO))));
    }

    /** El camino de #39 entero: notificacion con acuse, estado y pase (PCO). */
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
     * El conjunto sellado de 2026 <b>con el plazo de la REC-1 dentro</b>.
     *
     * <p>Los siete dias habiles del art. 14.1 de la Ley 26979 entran como <b>dato</b>, no como
     * constante del programa (regla 5). Que esta prueba tenga que sembrarlos es la demostracion:
     * sin el parametro, notificar una REC falla.
     */
    private static long crearConjuntoConElPlazoDeLaRec1(long municipalidadId) throws SQLException {
        // El catalogo normativo lo carga SU PROPIO ROL, no la aplicacion ni el duenio del
        // esquema (SoD-1 de REQ-03, politica `parametro_escritura` de V6). Y va con
        // `municipalidad_id NULL`: el plazo del art. 14.1 es norma nacional, no una ordenanza.
        long parametroId;
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_texto, vigencia_desde, documento_fuente, sellado,"
                                        + " usuario_carga) VALUES (NULL, 'PLAZO',"
                                        + " 'REC1_CUMPLIMIENTO', '7 DIAS_HABILES',"
                                        + " DATE '2026-01-01', 'Ley 26979 art. 14.1', true,"
                                        + " 'siembra') RETURNING id")) {
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                parametroId = resultado.getLong(1);
                carga.commit();
            }
        }

        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            long conjunto;
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            // Nace ABIERTO: el disparador de V9 impide anadirle un parametro a
                            // un conjunto ya sellado, que es justo lo que hace fiable leerlo.
                            "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio,"
                                    + " version) VALUES (?, 2026, 1) RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    conjunto = resultado.getLong(1);
                }
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametro_detalle_de_prueba (municipalidad_id,"
                                    + " conjunto_id, parametro_id) VALUES (?, ?, ?)")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setLong(2, conjunto);
                sentencia.setLong(3, parametroId);
                sentencia.executeUpdate();
            }
            // Y se sella: `LectorDeParametrosSellados` no lee conjuntos abiertos, y con razon.
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
        return "4141" + Math.abs(codigo.hashCode() % 10000 + 10000);
    }

    /**
     * Un renderizador que dibuja un byte de mas.
     *
     * <p>Es la version minima de «alguien cambio una fuente o un margen»: los datos guardados son
     * los mismos y aun asi los bytes no coinciden. Sirve para probar que la comprobacion del
     * SHA-256 muerde de verdad.
     */
    private record UnByteDeMas(kamayuk.rentas.documentos.Renderizador original)
            implements kamayuk.rentas.documentos.Renderizador {

        @Override
        public FormatoDeDocumento formato() {
            return original.formato();
        }

        @Override
        public void escribir(
                kamayuk.rentas.documentos.ModeloDeDocumento modelo, java.io.OutputStream salida)
                throws java.io.IOException {
            original.escribir(modelo, salida);
            salida.write(' ');
        }
    }

    /**
     * El padron, leido de la base.
     *
     * <p>No es un doble de conveniencia: el nombre del obligado sale impreso en la resolucion, y
     * este contexto lo pide por la API publica de {@code contribuyentes} (ARQ-01 §4). Lo unico que
     * esta clase evita es arrastrar el modulo entero a la prueba.
     */
    private static final class PadronDeLaPrueba implements DirectorioDeContribuyentes {

        @Override
        public java.util.Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return jdbc.sql(
                            "SELECT id, codigo_contribuyente, nombre_razon_social, numero_documento"
                                    + " FROM contribuyente WHERE codigo_contribuyente = :codigo")
                    .param("codigo", codigo)
                    .query(PadronDeLaPrueba::mapear)
                    .optional();
        }

        @Override
        public java.util.Map<Long, ResumenDeContribuyente> porIds(java.util.Set<Long> ids) {
            java.util.Map<Long, ResumenDeContribuyente> encontrados = new java.util.HashMap<>();
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
        public java.util.Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return java.util.Optional.empty();
        }

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            // La resolucion no busca por nombre: el obligado sale del expediente.
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
