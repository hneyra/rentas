package kamayuk.rentas.tesoreria.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.AcogimientoAConvenio;
import kamayuk.rentas.cuentacorriente.ConciliacionDeCaja;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.aplicacion.AcogimientoAConvenioCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConciliacionDeCajaCuentaCorriente;
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
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.tesoreria.aplicacion.AbrirCaja;
import kamayuk.rentas.tesoreria.aplicacion.AnularRecibo;
import kamayuk.rentas.tesoreria.aplicacion.ArqueoDeTurno;
import kamayuk.rentas.tesoreria.aplicacion.CerrarTurno;
import kamayuk.rentas.tesoreria.aplicacion.CobrarDeuda;
import kamayuk.rentas.tesoreria.aplicacion.CobrarTasa;
import kamayuk.rentas.tesoreria.aplicacion.CondicionesParametrizadas;
import kamayuk.rentas.tesoreria.aplicacion.ConsultaDeRecaudacion;
import kamayuk.rentas.tesoreria.aplicacion.FormalizarConvenio;
import kamayuk.rentas.tesoreria.aplicacion.RegistrarPreconvenio;
import kamayuk.rentas.tesoreria.dominio.CierreDeTurno;
import kamayuk.rentas.tesoreria.dominio.CierreDeTurnoRepository;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.CriterioDeRecaudacion;
import kamayuk.rentas.tesoreria.dominio.EstadoDeTurno;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.LineaDeArqueo;
import kamayuk.rentas.tesoreria.dominio.LineaDeTasaPedida;
import kamayuk.rentas.tesoreria.dominio.RecaudacionDePartida;
import kamayuk.rentas.tesoreria.dominio.RecaudacionDeTributo;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.tesoreria.dominio.TipoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeGarantia;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeTurno;
import kamayuk.rentas.tesoreria.dominio.TipoDePago;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCaja;
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

/**
 * #36 — El cierre de caja contra PostgreSQL de verdad, conectado como {@code sgtm_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>El dia completo cuadra centimo a centimo</b>. Se abre, se cobra deuda tributaria, una
 *       tasa y la cuota inicial de un convenio, se anula un recibo, y el arqueo se compara contra
 *       el libro y contra los recibos. Con dobles, el libro seria el que la prueba escribiera.
 *   <li><b>Que {@code sgtm_app} no pueda actualizar un acta de cierre ni su desglose</b>. No es una
 *       convencion: es un {@code REVOKE} de V32, y se comprueba intentandolo por SQL directo. Y con
 *       el, <b>el hallazgo de #36</b>: el turno SI conserva el UPDATE, porque {@code SELECT … FOR
 *       UPDATE} lo exige y esa fila es donde se serializa la ventanilla. Revocarlo habria dejado la
 *       caja sin poder cobrar.
 *   <li><b>Que dos cierres simultaneos no produzcan dos actas</b>, con hilos de verdad. La
 *       restriccion unica de la secuencia es la que decide, y un doble pasaria haga lo que haga el
 *       codigo real.
 *   <li><b>Que el avance no contienda con la cobranza</b>. Se deja el turno bloqueado con {@code
 *       FOR UPDATE} desde otro hilo y se comprueba que la lectura responde igual, con tiempo de
 *       espera. Es lo unico que demuestra RF-088: un informe que hiciera esperar a la cola seria un
 *       informe que nadie mira.
 *   <li><b>El aislamiento</b>. Con el contexto de B, el turno y el cierre de A no existen.
 * </ul>
 */
@DisplayName("#36 — El cierre de caja contra PostgreSQL")
class CierreDeCajaJdbcTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 15);
    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-03-15T18:00:00Z"), ZoneId.of("America/Lima"));

    /** {@code insufficient_privilege}: el SQLSTATE de un {@code REVOKE} que muerde. */
    private static final String PRIVILEGIO_INSUFICIENTE = "42501";

    /** {@code unique_violation}: el SQLSTATE de un indice unico que muerde. */
    private static final String VIOLACION_DE_UNICIDAD = "23505";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static long areaTributaria;
    private static long areaComercializacion;
    private static JdbcClient jdbc;
    private static TransactionTemplate transaccion;
    private static TenantTransactionManager gestor;
    private static DriverManagerDataSource pool;

    private static CierreDeTurnoRepositoryJdbc cierres;
    private static TurnoDeCajaRepositoryJdbc turnos;
    private static CajaRepositoryJdbc cajas;
    private static RegistrarAsiento registrarAsiento;
    private static CobrarDeuda cobrarDeuda;
    private static CobrarTasa cobrarTasa;
    private static AnularRecibo anularRecibo;
    private static CerrarTurno cerrarTurno;
    private static ConsultaDeRecaudacion consulta;
    private static RegistrarPreconvenio preconvenios;

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240601", "Municipalidad del cierre");
        otraMunicipalidad = crearMunicipalidad("240602", "Municipalidad vecina de #36");

        pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        cierres = new CierreDeTurnoRepositoryJdbc(jdbc);
        turnos = new TurnoDeCajaRepositoryJdbc(jdbc);
        cajas = new CajaRepositoryJdbc(jdbc);
        ReciboRepositoryJdbc recibos = new ReciboRepositoryJdbc(jdbc);
        TasaRepositoryJdbc tasas = new TasaRepositoryJdbc(jdbc);
        MovimientoDeReciboRepositoryJdbc movimientosDeRecibo =
                new MovimientoDeReciboRepositoryJdbc(jdbc);
        RecaudacionRepositoryJdbc recaudacion = new RecaudacionRepositoryJdbc(jdbc);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        registrarAsiento = new RegistrarAsiento(asientos, saldos, auditoria, RELOJ);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

        RegistroDeAbonos abonos =
                envolver(
                        new RegistroDeAbonosCuentaCorriente(
                                asientos, saldos, envolver(registrarAsiento), calculo, redondeo));
        AcogimientoAConvenio acogimiento =
                envolver(
                        new AcogimientoAConvenioCuentaCorriente(
                                asientos, saldos, envolver(registrarAsiento), calculo, redondeo));
        ConciliacionDeCaja libro = envolver(new ConciliacionDeCajaCuentaCorriente(asientos));

        AbrirCaja abrirCaja = envolver(new AbrirCaja(cajas, turnos, auditoria, RELOJ));
        ConvenioRepositoryJdbc convenios = new ConvenioRepositoryJdbc(jdbc);
        MovimientoDeConvenioRepositoryJdbc movimientosDeConvenio =
                new MovimientoDeConvenioRepositoryJdbc(jdbc);
        preconvenios =
                envolver(
                        new RegistrarPreconvenio(
                                convenios,
                                acogimiento,
                                new CondicionesParametrizadas(new ParametrosDeLaPrueba()),
                                auditoria,
                                RELOJ));
        FormalizarConvenio formalizar =
                envolver(
                        new FormalizarConvenio(
                                convenios, movimientosDeConvenio, acogimiento, auditoria, RELOJ));

        cobrarDeuda =
                envolver(new CobrarDeuda(abrirCaja, abonos, recibos, formalizar, auditoria, RELOJ));
        cobrarTasa = envolver(new CobrarTasa(abrirCaja, tasas, recibos, auditoria, RELOJ));
        anularRecibo =
                envolver(
                        new AnularRecibo(
                                recibos, movimientosDeRecibo, turnos, abonos, auditoria, RELOJ));

        ArqueoDeTurno arqueos = new ArqueoDeTurno(cierres, libro);
        cerrarTurno = envolver(new CerrarTurno(cajas, turnos, cierres, arqueos, auditoria, RELOJ));
        consulta = envolver(new ConsultaDeRecaudacion(recaudacion, arqueos));

        areaTributaria = crearArea(municipalidad, "A-36", "Unidad de Rentas");
        areaComercializacion = crearArea(municipalidad, "A-37", "Comercializacion");
        crearCaja(municipalidad, "C-36", "R36", areaTributaria);
        crearCaja(municipalidad, "C-37", "R37", areaTributaria);
        crearArea(otraMunicipalidad, "A-36", "Unidad de Rentas");
        crearCaja(otraMunicipalidad, "C-36", "R36", null);
        crearTasa("T-360", Dinero.de("50.00"), areaTributaria, "1.3.1.1.1.1");
        crearTasa("T-361", Dinero.de("33.33"), areaComercializacion, "1.3.9.9.9.9");
        crearTasa("T-362", Dinero.de("33.34"), areaComercializacion, "1.3.9.9.9.9");
    }

    /**
     * Envuelve el caso de uso en un proxy transaccional <b>de verdad</b>, igual que {@code
     * CajaJdbcTest}: lo que se verifica es la anotacion del codigo de produccion, no una
     * transaccion que abra la prueba.
     */
    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
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
        OrigenContext.fijar(new Origen("cajero.prueba", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 1 — El dia completo")
    class DelDiaCompleto {

        @Test
        @DisplayName("abrir, cobrar de las tres clases, anular uno, cerrar: el arqueo cuadra")
        void elDiaCompletoCuadraCentimoACentimo() {
            String cajero = cajero("dia-completo");

            // 1. Deuda tributaria, cobrada en ventanilla: 300,00 que SI abonan en el libro.
            long conDeuda = contribuyenteConDeuda("D36-1", Dinero.de("300.00"));
            Recibo tributario = cobrarLaDeuda(conDeuda, cajero, FormaDePago.EFECTIVO);

            // 2. Una tasa del TUPA: 50,00 que NO tocan el libro.
            long deLaTasa = crearContribuyente(municipalidad, "D36-2");
            Recibo deTasa = cobrarLaTasa(deLaTasa, cajero, "T-360", 1, FormaDePago.TARJETA);

            // 3. La cuota inicial de un convenio: tampoco toca el libro como abono.
            long delConvenio = contribuyenteConDeuda("D36-3", Dinero.de("1000.00"));
            Convenio convenio = registrarPreconvenio(delConvenio);
            Recibo inicial = cobrarLaInicial(delConvenio, convenio, cajero);

            // 4. Otra cobranza tributaria que despues se anula: entra y sale el mismo dia.
            long anulado = contribuyenteConDeuda("D36-4", Dinero.de("120.00"));
            Recibo aAnular = cobrarLaDeuda(anulado, cajero, FormaDePago.EFECTIVO);
            anularRecibo.anular(
                    new AnularRecibo.Anulacion(
                            aAnular.numero(), "el contribuyente pago de mas", null, null),
                    porQue());

            Dinero laInicial = inicial.total();
            Dinero declaradoEfectivo = Dinero.de("300.00");
            Dinero declaradoTarjeta = Dinero.de("50.00");

            CerrarTurno.Cerrado cerrado =
                    cerrarTurno.cerrar(
                            new CerrarTurno.Cierre(
                                    "C-36",
                                    cajero,
                                    HOY,
                                    Map.of(
                                            FormaDePago.EFECTIVO,
                                            declaradoEfectivo.mas(laInicial),
                                            FormaDePago.TARJETA,
                                            declaradoTarjeta)),
                            porQue());

            assertThat(cerrado.cierre().arqueoCongelado().recibosEmitidos()).isEqualTo(4);
            assertThat(cerrado.cierre().arqueoCongelado().recibosAnulados()).isEqualTo(1);
            assertThat(cerrado.cierre().arqueoCongelado().totalCobrado())
                    .as("los cuatro recibos, el anulado incluido")
                    .isEqualTo(Dinero.de("470.00").mas(laInicial));
            assertThat(cerrado.cierre().arqueoCongelado().totalAnulado())
                    .as("y la anulacion, restada: no cuenta como cobro")
                    .isEqualTo(Dinero.de("120.00"));
            assertThat(cerrado.cierre().arqueoCongelado().neto())
                    .isEqualTo(Dinero.de("350.00").mas(laInicial));
            assertThat(cerrado.cierre().arqueoCongelado().diferencia())
                    .as("lo declarado coincide con el neto, al centimo")
                    .isEqualTo(Dinero.CERO);

            assertThat(cerrado.cuadre().conAsientos())
                    .as("solo lo tributario vivo: 300, porque los 120 se reversaron")
                    .isEqualTo(Dinero.de("300.00"));
            assertThat(cerrado.cuadre().sinAsientos())
                    .as("la tasa y la cuota inicial cuadran contra el papel, no contra asientos")
                    .isEqualTo(Dinero.de("50.00").mas(laInicial));
            assertThat(cerrado.cuadre().total())
                    .as("y las dos mitades suman exactamente el neto del arqueo")
                    .isEqualTo(cerrado.cierre().arqueoCongelado().neto());

            // Y el acta esta en la base, con su desglose por medio de pago.
            assertThat(
                            enTransaccion(
                                    () ->
                                            jdbc.sql(
                                                            "SELECT neto FROM cierre_turno"
                                                                    + " WHERE id = :id")
                                                    .param("id", cerrado.cierre().idGuardado())
                                                    .query(java.math.BigDecimal.class)
                                                    .single()))
                    .isEqualByComparingTo(cerrado.cierre().arqueoCongelado().neto().valor());
            assertThat(sumaDelDetalle(cerrado.cierre().idGuardado()))
                    .as("la suma de las lineas del arqueo es su neto: sin centimos huerfanos")
                    .isEqualTo(cerrado.cierre().arqueoCongelado().neto());
            assertThat(tributario.numero()).isNotEqualTo(deTasa.numero());
        }

        @Test
        @DisplayName("cobrar con el turno cerrado falla, y el mensaje dice como se reabre")
        void noSeCobraConElTurnoCerrado() {
            String cajero = cajero("tras-cerrar");
            long titular = contribuyenteConDeuda("D36-5", Dinero.de("90.00"));
            cobrarLaDeuda(titular, cajero, FormaDePago.EFECTIVO);
            cerrarTurno.cerrar(
                    new CerrarTurno.Cierre(
                            "C-36", cajero, HOY, Map.of(FormaDePago.EFECTIVO, Dinero.de("90.00"))),
                    porQue());

            long otro = contribuyenteConDeuda("D36-6", Dinero.de("40.00"));
            assertThatThrownBy(() -> cobrarLaDeuda(otro, cajero, FormaDePago.EFECTIVO))
                    .isInstanceOf(AbrirCaja.TurnoCerrado.class)
                    .hasMessageContaining("reversar ese cierre");
        }

        @Test
        @DisplayName("reversar reabre el turno, se sigue cobrando, y el cierre nuevo lo incluye")
        void reversarReabreYSeSigueCobrando() {
            String cajero = cajero("reabrir");
            long titular = contribuyenteConDeuda("D36-7", Dinero.de("200.00"));
            cobrarLaDeuda(titular, cajero, FormaDePago.EFECTIVO);
            CerrarTurno.Cerrado primero =
                    cerrarTurno.cerrar(
                            new CerrarTurno.Cierre(
                                    "C-36",
                                    cajero,
                                    HOY,
                                    Map.of(FormaDePago.EFECTIVO, Dinero.de("200.00"))),
                            porQue());

            cerrarTurno.reversar(
                    "C-36", cajero, HOY, "quedaba un contribuyente en la cola", porQue());

            assertThat(estadoDelTurno(primero.cierre().turnoId()))
                    .as("reversar reabre: es la unica forma de seguir cobrando ese dia")
                    .isEqualTo(EstadoDeTurno.ABIERTO);

            long segundo = contribuyenteConDeuda("D36-8", Dinero.de("75.00"));
            cobrarLaDeuda(segundo, cajero, FormaDePago.EFECTIVO);

            CerrarTurno.Cerrado nuevo =
                    cerrarTurno.cerrar(
                            new CerrarTurno.Cierre(
                                    "C-36",
                                    cajero,
                                    HOY,
                                    Map.of(FormaDePago.EFECTIVO, Dinero.de("275.00"))),
                            porQue());

            assertThat(nuevo.cierre().secuencia()).isEqualTo(3);
            assertThat(nuevo.cierre().arqueoCongelado().neto()).isEqualTo(Dinero.de("275.00"));
            assertThat(primeroSigueDiciendo(primero.cierre().idGuardado()))
                    .as("y el arqueo del primer cierre sigue diciendo lo que decia")
                    .isEqualTo(Dinero.de("200.00"));
        }
    }

    @Nested
    @DisplayName("AC 2 — Un cierre no se modifica ni se borra")
    class DeLaInmutabilidad {

        @Test
        @DisplayName("sgtm_app no puede actualizar el acta, su desglose ni el turno")
        void sinUpdate() {
            String cajero = cajero("inmutable");
            long titular = contribuyenteConDeuda("D36-9", Dinero.de("60.00"));
            cobrarLaDeuda(titular, cajero, FormaDePago.EFECTIVO);
            CerrarTurno.Cerrado cerrado =
                    cerrarTurno.cerrar(
                            new CerrarTurno.Cierre("C-36", cajero, HOY, Map.of()), porQue());
            long cierreId = cerrado.cierre().idGuardado();

            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            jdbc.sql(
                                                            "UPDATE cierre_turno"
                                                                    + " SET total_declarado = 0"
                                                                    + " WHERE id = :id")
                                                    .param("id", cierreId)
                                                    .update()))
                    .as("corregir el arqueo firmado haria desaparecer el descuadre del acta")
                    .isEqualTo(PRIVILEGIO_INSUFICIENTE);
            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            jdbc.sql(
                                                            "UPDATE cierre_turno_detalle"
                                                                    + " SET declarado = 0"
                                                                    + " WHERE cierre_id = :id")
                                                    .param("id", cierreId)
                                                    .update()))
                    .isEqualTo(PRIVILEGIO_INSUFICIENTE);
        }

        @Test
        @DisplayName(
                "el turno CONSERVA el UPDATE, y no es un descuido: sin el no se puede bloquear")
        void elTurnoConservaElUpdatePorqueSinElNoHayForUpdate() {
            // Este es el hallazgo de #36 (V32 §1.bis). Aqui iba un REVOKE mas, por el mismo
            // motivo que V29 se lo hizo al recibo. No se puede: `SELECT ... FOR UPDATE`
            // exige el privilegio de UPDATE, y esa fila es donde se serializa la ventanilla
            // desde V29. Revocarlo no habria hecho el turno inmutable: habria dejado la caja
            // sin poder cobrar.
            //
            // Se demuestra con `cierre_turno`, que SI tiene el UPDATE revocado: pedir su
            // bloqueo falla con el mismo 42501 que fallaria el de `cierre_caja`.
            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            jdbc.sql(
                                                            "SELECT id FROM cierre_turno"
                                                                    + " WHERE turno_id = :t"
                                                                    + " FOR UPDATE")
                                                    .param("t", 1L)
                                                    .query(Long.class)
                                                    .list()))
                    .as("bloquear una fila exige UPDATE, aunque la sentencia sea un SELECT")
                    .isEqualTo(PRIVILEGIO_INSUFICIENTE);

            assertThat(privilegioDeUpdateSobre("cierre_caja"))
                    .as("por eso el turno lo conserva: sin el, la ventanilla no puede cobrar")
                    .isTrue();
            assertThat(privilegioDeUpdateSobre("cierre_turno"))
                    .as("y el acta no, que es donde el privilegio si puede ser la barrera")
                    .isFalse();
            assertThat(privilegioDeUpdateSobre("cierre_turno_detalle")).isFalse();
        }

        @Test
        @DisplayName("un cierre no se reversa dos veces: lo impide el indice unico parcial")
        void unaSolaReversionPorCierre() {
            String cajero = cajero("doble-reversion");
            long titular = contribuyenteConDeuda("D36-10", Dinero.de("30.00"));
            cobrarLaDeuda(titular, cajero, FormaDePago.EFECTIVO);
            CerrarTurno.Cerrado cerrado =
                    cerrarTurno.cerrar(
                            new CerrarTurno.Cierre("C-36", cajero, HOY, Map.of()), porQue());

            cerrarTurno.reversar("C-36", cajero, HOY, "primera reversion", porQue());

            // La segunda no pasa por el caso de uso -no hay cierre vigente-, asi que se
            // intenta por SQL directo: lo que se prueba es que la BASE lo impide, no que
            // la aplicacion se acuerde de mirar.
            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            jdbc.sql(
                                                            "INSERT INTO cierre_turno"
                                                                    + " (municipalidad_id, turno_id,"
                                                                    + "  tipo, secuencia, fecha,"
                                                                    + "  fecha_registro, revierte_a_id,"
                                                                    + "  motivo, usuario_registro,"
                                                                    + "  observacion)"
                                                                    + " VALUES (:muni, :turno,"
                                                                    + "  'REVERSION', 9, :fecha, now(),"
                                                                    + "  :cierre, 'otra vez',"
                                                                    + "  'prueba', 'segunda"
                                                                    + " reversion')")
                                                    .param("muni", municipalidad)
                                                    .param("turno", cerrado.cierre().turnoId())
                                                    .param("fecha", HOY)
                                                    .param("cierre", cerrado.cierre().idGuardado())
                                                    .update()))
                    .as("dos reversiones dejarian el historial contando una reapertura de mas")
                    .isEqualTo(VIOLACION_DE_UNICIDAD);
        }

        @Test
        @DisplayName("la base comprueba la aritmetica del arqueo: neto y diferencia")
        void laBaseComprubaLaAritmetica() {
            String cajero = cajero("aritmetica");
            long titular = contribuyenteConDeuda("D36-11", Dinero.de("10.00"));
            cobrarLaDeuda(titular, cajero, FormaDePago.EFECTIVO);
            CerrarTurno.Cerrado cerrado =
                    cerrarTurno.cerrar(
                            new CerrarTurno.Cierre("C-36", cajero, HOY, Map.of()), porQue());

            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            jdbc.sql(
                                                            "INSERT INTO cierre_turno"
                                                                    + " (municipalidad_id, turno_id,"
                                                                    + "  tipo, secuencia, fecha,"
                                                                    + "  fecha_registro, total_cobrado,"
                                                                    + "  total_anulado, neto,"
                                                                    + "  total_declarado, diferencia,"
                                                                    + "  recibos_emitidos,"
                                                                    + "  recibos_anulados,"
                                                                    + "  usuario_registro, observacion)"
                                                                    + " VALUES (:muni, :turno,"
                                                                    + "  'CIERRE', 8, :fecha, now(),"
                                                                    + "  100.00, 10.00, 95.00, 95.00,"
                                                                    + "  0.00, 1, 0, 'prueba', 'un"
                                                                    + " arqueo que no cuadra consigo"
                                                                    + " mismo')")
                                                    .param("muni", municipalidad)
                                                    .param("turno", cerrado.cierre().turnoId())
                                                    .param("fecha", HOY)
                                                    .update()))
                    .as("100 - 10 no son 95, y eso no depende de que la aplicacion lo mire")
                    .isEqualTo("23514");
        }
    }

    @Nested
    @DisplayName("AC 3 — Dos cierres a la vez")
    class DeLaConcurrencia {

        @Test
        @DisplayName("ocho peticiones simultaneas de cierre producen un acta, no ocho")
        void unSoloCierre() throws Exception {
            String cajero = cajero("concurrencia");
            long titular = contribuyenteConDeuda("D36-12", Dinero.de("500.00"));
            cobrarLaDeuda(titular, cajero, FormaDePago.EFECTIVO);
            long turnoId = turnoDe("C-36", cajero);

            int cuantos = 8;
            ExecutorService hilos = Executors.newFixedThreadPool(cuantos);
            CountDownLatch salida = new CountDownLatch(1);
            try {
                List<Future<Boolean>> intentos = new java.util.ArrayList<>();
                for (int i = 0; i < cuantos; i++) {
                    intentos.add(
                            hilos.submit(
                                    () -> {
                                        salida.await();
                                        TenantContext.fijar(new MunicipalidadId(municipalidad));
                                        OrigenContext.fijar(
                                                new Origen("cajero.prueba", null, null));
                                        try {
                                            cerrarTurno.cerrar(
                                                    new CerrarTurno.Cierre(
                                                            "C-36", cajero, HOY, Map.of()),
                                                    porQue());
                                            return true;
                                        } catch (CerrarTurno.TurnoYaCerrado
                                                | CierreDeTurnoRepository.TurnoYaTieneEseMovimiento
                                                        rechazado) {
                                            // Los dos son «este turno ya se cerro», y se atrapan
                                            // por su tipo y no como RuntimeException a proposito:
                                            // lo que se prueba no es que los perdedores fallen,
                                            // sino que fallen POR ESO. Con un catch ancho, un
                                            // fallo de conexion contaria como cierre rechazado y
                                            // la prueba seguiria verde.
                                            return false;
                                        } finally {
                                            TenantContext.limpiar();
                                            OrigenContext.limpiar();
                                        }
                                    }));
                }
                salida.countDown();
                long exitosos = 0;
                for (Future<Boolean> intento : intentos) {
                    if (Boolean.TRUE.equals(intento.get(30, TimeUnit.SECONDS))) {
                        exitosos++;
                    }
                }
                assertThat(exitosos).as("uno cierra; los otros siete chocan").isEqualTo(1);
            } finally {
                hilos.shutdownNow();
            }

            assertThat(enTransaccion(() -> cierres.deTurno(turnoId)))
                    .as("y en la base queda un acta, no ocho")
                    .singleElement()
                    .extracting(CierreDeTurno::tipo)
                    .isEqualTo(TipoDeMovimientoDeTurno.CIERRE);
        }
    }

    @Nested
    @DisplayName("AC 4 — El avance en vivo no contiende con la cobranza")
    class DeLaNoContencion {

        @Test
        @DisplayName("con el turno bloqueado por otra transaccion, el avance responde igual")
        void elAvanceNoEsperaAlCandado() throws Exception {
            String cajero = cajero("no-contencion");
            long titular = contribuyenteConDeuda("D36-13", Dinero.de("250.00"));
            cobrarLaDeuda(titular, cajero, FormaDePago.EFECTIVO);

            CountDownLatch bloqueado = new CountDownLatch(1);
            CountDownLatch suelta = new CountDownLatch(1);
            ExecutorService hilos = Executors.newFixedThreadPool(2);
            try {
                // Un hilo se queda con el turno bloqueado con FOR UPDATE, que es exactamente
                // lo que hace una cobranza en curso, y no lo suelta hasta que se le diga.
                Future<?> cobranza =
                        hilos.submit(
                                () -> {
                                    TenantContext.fijar(new MunicipalidadId(municipalidad));
                                    transaccion.execute(
                                            estado -> {
                                                TenantContext.fijar(
                                                        new MunicipalidadId(municipalidad));
                                                turnos.bloquear(cajaDe("C-36"), cajero, HOY);
                                                bloqueado.countDown();
                                                try {
                                                    suelta.await(30, TimeUnit.SECONDS);
                                                } catch (InterruptedException interrumpido) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                return null;
                                            });
                                    TenantContext.limpiar();
                                    return null;
                                });

                assertThat(bloqueado.await(30, TimeUnit.SECONDS))
                        .as("el candado tiene que estar puesto antes de medir nada")
                        .isTrue();

                Future<Dinero> avance =
                        hilos.submit(
                                () -> {
                                    TenantContext.fijar(new MunicipalidadId(municipalidad));
                                    try {
                                        return consulta.delTurno("C-36", cajero, HOY, HOY)
                                                .orElseThrow()
                                                .arqueo()
                                                .neto();
                                    } finally {
                                        TenantContext.limpiar();
                                    }
                                });

                // Cinco segundos son de sobra para una lectura agregada, y muy poco para
                // esperar a que el otro hilo suelte el candado -que no lo va a soltar-.
                assertThat(avance.get(5, TimeUnit.SECONDS))
                        .as("el avance lee sin FOR UPDATE: la cobranza en curso no lo detiene")
                        .isEqualTo(Dinero.de("250.00"));

                suelta.countDown();
                cobranza.get(30, TimeUnit.SECONDS);
            } finally {
                suelta.countDown();
                hilos.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("AC 5 — La distribucion suma el total, sin centimos huerfanos")
    class DeLaDistribucion {

        @Test
        @DisplayName("por tributo y por partida suman lo mismo, y ese mismo es el neto del periodo")
        void lasPartesSumanElTotal() {
            String cajero = cajero("distribucion");
            long tributario = contribuyenteConDeuda("D36-14", Dinero.de("100.00"));
            cobrarLaDeuda(tributario, cajero, FormaDePago.EFECTIVO);
            long deTasas = crearContribuyente(municipalidad, "D36-15");
            // 33,33 y 33,34: si en algun sitio hubiera un reparto proporcional, aqui
            // saldria un centimo huerfano.
            cobrarLaTasa(deTasas, cajero, "T-361", 1, FormaDePago.EFECTIVO);
            cobrarLaTasa(deTasas, cajero, "T-362", 1, FormaDePago.EFECTIVO);

            CriterioDeRecaudacion delDia =
                    CriterioDeRecaudacion.delDia(HOY).enLaCajaDe("C-36", cajero);

            ConsultaDeRecaudacion.Avance avance = consulta.avance(delDia, HOY);
            ConsultaDeRecaudacion.Distribucion distribucion = consulta.porPartida(delDia, HOY);

            assertThat(avance.neto())
                    .as("100,00 + 33,33 + 33,34 = 166,67, al centimo")
                    .isEqualTo(Dinero.de("166.67"));
            assertThat(distribucion.neto())
                    .as("la distribucion reparte filas: suma exactamente lo mismo")
                    .isEqualTo(avance.neto());

            Dinero sumaDeLasFilas = Dinero.CERO;
            for (RecaudacionDeTributo fila : avance.filas()) {
                sumaDeLasFilas = sumaDeLasFilas.mas(fila.neto());
            }
            assertThat(sumaDeLasFilas).isEqualTo(avance.neto());
        }

        @Test
        @DisplayName("las tasas traen su area y su partida; lo tributario, ninguna de las dos")
        void loTributarioNoTienePartida() {
            String cajero = cajero("partidas");
            long tributario = contribuyenteConDeuda("D36-16", Dinero.de("400.00"));
            cobrarLaDeuda(tributario, cajero, FormaDePago.EFECTIVO);
            long deTasas = crearContribuyente(municipalidad, "D36-17");
            cobrarLaTasa(deTasas, cajero, "T-360", 2, FormaDePago.EFECTIVO);

            ConsultaDeRecaudacion.Distribucion distribucion =
                    consulta.porPartida(
                            CriterioDeRecaudacion.delDia(HOY).enLaCajaDe("C-36", cajero), HOY);

            assertThat(distribucion.filas())
                    .filteredOn(RecaudacionDePartida::tienePartida)
                    .singleElement()
                    .satisfies(
                            fila -> {
                                assertThat(fila.areaCodigo()).isEqualTo("A-36");
                                assertThat(fila.partidaPresupuestal()).isEqualTo("1.3.1.1.1.1");
                                assertThat(fila.neto()).isEqualTo(Dinero.de("100.00"));
                            });
            assertThat(distribucion.filas())
                    .filteredOn(fila -> !fila.tienePartida())
                    .as("lo tributario no tiene area ni partida en ningun sitio del esquema")
                    .allSatisfy(
                            fila -> {
                                assertThat(fila.areaCodigo()).isNull();
                                assertThat(fila.partidaPresupuestal()).isNull();
                            });
            assertThat(distribucion.netoSinPartida())
                    .as("y el reporte lo dice en vez de esconderlo")
                    .isEqualTo(Dinero.de("400.00"));
        }

        @Test
        @DisplayName("filtrar por area deja fuera lo tributario, porque no consta en ninguna")
        void elFiltroPorArea() {
            String cajero = cajero("filtro-area");
            long tributario = contribuyenteConDeuda("D36-18", Dinero.de("70.00"));
            cobrarLaDeuda(tributario, cajero, FormaDePago.EFECTIVO);
            long deTasas = crearContribuyente(municipalidad, "D36-19");
            cobrarLaTasa(deTasas, cajero, "T-361", 3, FormaDePago.EFECTIVO);

            ConsultaDeRecaudacion.Distribucion soloComercializacion =
                    consulta.porPartida(
                            new CriterioDeRecaudacion(HOY, HOY, null, "A-37", "C-36", cajero), HOY);

            assertThat(soloComercializacion.filas()).hasSize(1);
            assertThat(soloComercializacion.neto()).isEqualTo(Dinero.de("99.99"));
            assertThat(soloComercializacion.netoSinPartida()).isEqualTo(Dinero.CERO);
        }

        @Test
        @DisplayName("una anulacion se resta del avance en vez de desaparecer de el")
        void laAnulacionSeRestaDelAvance() {
            String cajero = cajero("avance-anulado");
            long titular = contribuyenteConDeuda("D36-20", Dinero.de("220.00"));
            Recibo recibo = cobrarLaDeuda(titular, cajero, FormaDePago.EFECTIVO);
            anularRecibo.anular(
                    new AnularRecibo.Anulacion(recibo.numero(), "cobro duplicado", null, null),
                    porQue());

            ConsultaDeRecaudacion.Avance avance =
                    consulta.avance(
                            CriterioDeRecaudacion.delDia(HOY).enLaCajaDe("C-36", cajero), HOY);

            assertThat(avance.totalCobrado()).isEqualTo(Dinero.de("220.00"));
            assertThat(avance.totalAnulado()).isEqualTo(Dinero.de("220.00"));
            assertThat(avance.neto())
                    .as("entro y salio: el avance lo cuenta y lo resta, no lo esconde")
                    .isEqualTo(Dinero.CERO);
        }
    }

    @Nested
    @DisplayName("AC 6 — Ningun cajero ve la caja de otra municipalidad")
    class DelAislamiento {

        @Test
        @DisplayName("con el contexto de B, el turno y el cierre de A no existen")
        void desdeBNoSeVeNadaDeA() {
            String cajero = cajero("aislamiento");
            long titular = contribuyenteConDeuda("D36-21", Dinero.de("640.00"));
            cobrarLaDeuda(titular, cajero, FormaDePago.EFECTIVO);
            CerrarTurno.Cerrado cerrado =
                    cerrarTurno.cerrar(
                            new CerrarTurno.Cierre("C-36", cajero, HOY, Map.of()), porQue());
            long turnoId = cerrado.cierre().turnoId();

            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
            try {
                List<CierreDeTurno> desdeB =
                        transaccion.execute(
                                estado -> {
                                    TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
                                    return cierres.deTurno(turnoId);
                                });
                assertThat(desdeB).as("RLS: el cierre de A no existe para B").isEmpty();
                java.util.Optional<TurnoDeCaja> elTurnoDesdeB =
                        transaccion.execute(
                                estado -> {
                                    TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
                                    return turnos.porId(turnoId);
                                });
                assertThat(elTurnoDesdeB)
                        .as("ni su turno, aunque la caja se llame igual en las dos")
                        .isEmpty();
                List<RecaudacionDeTributo> laRecaudacionDesdeB =
                        transaccion.execute(
                                estado -> {
                                    TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
                                    return new RecaudacionRepositoryJdbc(jdbc)
                                            .porTributo(CriterioDeRecaudacion.delDia(HOY));
                                });
                assertThat(laRecaudacionDesdeB).as("ni un sol de la recaudacion de A").isEmpty();
            } finally {
                TenantContext.fijar(new MunicipalidadId(municipalidad));
            }
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Un cajero distinto por prueba: {@code cierre_uq} hace unico el turno por (caja, cajero). */
    private static String cajero(String sufijo) {
        return "c" + CONTADOR.incrementAndGet() + "-" + sufijo;
    }

    private static Recibo cobrarLaDeuda(long titular, String cajero, FormaDePago forma) {
        return cobrarDeuda.cobrar(
                new CobrarDeuda.Cobranza(
                        "C-36",
                        cajero,
                        titular,
                        List.of(new SeleccionDeObligacion("PREDIAL", EJERCICIO, null, null)),
                        forma,
                        TipoDePago.NORMAL,
                        null,
                        HOY,
                        null,
                        null),
                porQue());
    }

    private static Recibo cobrarLaTasa(
            long titular, String cajero, String codigo, int cantidad, FormaDePago forma) {
        return cobrarTasa.cobrar(
                new CobrarTasa.CobroDeTasas(
                        "C-36",
                        cajero,
                        titular,
                        List.of(new LineaDeTasaPedida(codigo, cantidad)),
                        forma,
                        HOY,
                        null),
                porQue());
    }

    private static Convenio registrarPreconvenio(long titular) {
        return preconvenios.registrar(
                new RegistrarPreconvenio.Peticion(
                        titular,
                        List.of(new SeleccionDeObligacion("PREDIAL", EJERCICIO, null, null)),
                        TipoDeConvenio.ORDINARIO,
                        HOY,
                        HOY,
                        6,
                        Alicuota.de("10"),
                        HOY.plusMonths(1),
                        TipoDeGarantia.NO_REQUIERE,
                        null,
                        null,
                        null),
                null,
                Observacion.de("Acogimiento a fraccionamiento, prueba de #36"));
    }

    private static Recibo cobrarLaInicial(long titular, Convenio convenio, String cajero) {
        return cobrarDeuda.cobrar(
                new CobrarDeuda.Cobranza(
                        "C-36",
                        cajero,
                        titular,
                        List.of(),
                        FormaDePago.EFECTIVO,
                        TipoDePago.PRECONVENIO,
                        null,
                        HOY,
                        null,
                        convenio.numero().impreso()),
                porQue());
    }

    private static EstadoDeTurno estadoDelTurno(long turnoId) {
        return enTransaccion(() -> turnos.porId(turnoId).orElseThrow().estado());
    }

    private static long turnoDe(String codigoDeCaja, String cajero) {
        return enTransaccion(
                () ->
                        turnos.bloquear(cajaDe(codigoDeCaja), cajero, HOY)
                                .map(TurnoDeCaja::idGuardado)
                                .orElseThrow());
    }

    private static long cajaDe(String codigo) {
        Long id = enTransaccion(() -> cajas.porCodigo(codigo).orElseThrow().id());
        return java.util.Objects.requireNonNull(id);
    }

    /** La suma de las lineas del arqueo guardado: tiene que ser su neto, al centimo. */
    private static Dinero sumaDelDetalle(long cierreId) {
        return enTransaccion(
                () -> {
                    Dinero total = Dinero.CERO;
                    List<LineaDeArqueo> lineas =
                            jdbc.sql(
                                            "SELECT forma_pago, cobrado, anulado, declarado"
                                                    + " FROM cierre_turno_detalle"
                                                    + " WHERE cierre_id = :id ORDER BY id")
                                    .param("id", cierreId)
                                    .query(
                                            (fila, numero) ->
                                                    new LineaDeArqueo(
                                                            FormaDePago.valueOf(
                                                                    fila.getString("forma_pago")
                                                                            .strip()),
                                                            new Dinero(
                                                                    fila.getBigDecimal("cobrado")),
                                                            new Dinero(
                                                                    fila.getBigDecimal("anulado")),
                                                            new Dinero(
                                                                    fila.getBigDecimal(
                                                                            "declarado"))))
                                    .list();
                    for (LineaDeArqueo linea : lineas) {
                        total = total.mas(linea.neto());
                    }
                    return total;
                });
    }

    private static Dinero primeroSigueDiciendo(long cierreId) {
        return enTransaccion(
                () ->
                        new Dinero(
                                java.util.Objects.requireNonNull(
                                        jdbc.sql("SELECT neto FROM cierre_turno WHERE id = :id")
                                                .param("id", cierreId)
                                                .query(java.math.BigDecimal.class)
                                                .single())));
    }

    private static Observacion porQue() {
        return Observacion.de("Operacion de caja, prueba de #36");
    }

    /** Si {@code sgtm_app} tiene UPDATE sobre esa tabla, preguntado a la propia base. */
    private static boolean privilegioDeUpdateSobre(String tabla) {
        Boolean tiene =
                enTransaccion(
                        () ->
                                jdbc.sql("SELECT has_table_privilege(:tabla, 'UPDATE')")
                                        .param("tabla", tabla)
                                        .query(Boolean.class)
                                        .single());
        return Boolean.TRUE.equals(tiene);
    }

    /**
     * El SQLSTATE con el que la base rechaza la sentencia, o {@code null} si la deja pasar.
     *
     * <p>Se compara el codigo y no el texto del mensaje a proposito: PostgreSQL lo traduce al
     * idioma del servidor.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private static @Nullable String sqlStateAlIntentar(Runnable sentencia) {
        try {
            enTransaccion(
                    () -> {
                        sentencia.run();
                        return null;
                    });
            return null;
        } catch (RuntimeException rechazo) {
            for (Throwable causa = rechazo; causa != null; causa = causa.getCause()) {
                if (causa instanceof SQLException sql) {
                    return sql.getSQLState();
                }
            }
            throw rechazo;
        }
    }

    private static <T> T enTransaccion(java.util.function.Supplier<T> accion) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        return transaccion.execute(
                estado -> {
                    TenantContext.fijar(new MunicipalidadId(municipalidad));
                    return accion.get();
                });
    }

    /** Un contribuyente con un cargo insoluto ya asentado, en fase ordinaria. */
    private static long contribuyenteConDeuda(String sufijo, Dinero monto) {
        long id = crearContribuyente(municipalidad, sufijo);
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        id,
                                        "PREDIAL",
                                        Concepto.INSOLUTO,
                                        TipoAsiento.CARGO,
                                        Fase.ORDINARIA,
                                        null,
                                        null,
                                        null,
                                        null,
                                        monto,
                                        LocalDate.of(2026, 1, 2),
                                        "DETERMINACION DE LA PRUEBA"),
                                Observacion.de("Se asienta la deuda de la prueba")));
        return id;
    }

    private static long crearContribuyente(long muni, String sufijo) {
        int orden = CONTADOR.incrementAndGet();
        return insertarComoOwner(
                muni,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA', 'siembra')"
                        + " RETURNING id",
                muni,
                sufijo + "-" + orden,
                String.format("%08d", 30_000_000 + orden));
    }

    private static void crearTasa(String codigo, Dinero importe, long area, String partida) {
        insertarComoOwner(
                municipalidad,
                "INSERT INTO tasa (municipalidad_id, codigo, descripcion, area_id,"
                        + " partida_presupuestal, importe, vigencia_desde, documento_fuente)"
                        + " VALUES (?, ?, 'Concepto del TUPA', ?, ?, ?, ?,"
                        + "         'TUPA 2026 de la prueba') RETURNING id",
                municipalidad,
                codigo,
                area,
                partida,
                importe.valor(),
                LocalDate.of(2026, 1, 1));
    }

    private static long crearArea(long muni, String codigo, String nombre) {
        return insertarComoOwner(
                muni,
                "INSERT INTO area (municipalidad_id, codigo, nombre) VALUES (?, ?, ?) RETURNING id",
                muni,
                codigo,
                nombre);
    }

    private static long crearCaja(long muni, String codigo, String serie, @Nullable Long area) {
        return insertarComoOwner(
                muni,
                "INSERT INTO caja (municipalidad_id, codigo, nombre, area_id, serie)"
                        + " VALUES (?, ?, 'Caja de la prueba', ?, ?) RETURNING id",
                muni,
                codigo,
                area,
                serie);
    }

    /**
     * Inserta una fila de siembra como {@code sgtm_owner}, con el contexto de tenant fijado.
     *
     * <p>Fijarlo no es opcional aunque quien escriba sea el dueno de la tabla: {@code FORCE ROW
     * LEVEL SECURITY} alcanza tambien al dueno (DAT-01 §0).
     */
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
     * Los parametros del convenio, <b>de mentira</b>: el interes y el maximo de cuotas.
     *
     * <p>Mismo doble que en {@code ConvenioJdbcTest} y por el mismo motivo: sus valores reales —de
     * ordenanza local— los firma D-02b (#191), y ninguna asercion de esta clase depende de cuanto
     * valen. Lo unico que se necesita del convenio aqui es que su cuota inicial se cobre con un
     * recibo que <b>no abona en el libro</b>.
     */
    private static final class ParametrosDeLaPrueba implements LectorDeParametros {

        private static final IdentificadorDeConjunto CONJUNTO = IdentificadorDeConjunto.de(1);

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            return ParametrosSellados.de(ejercicio, 1)
                    .numero("INTERES_FRACCIONAMIENTO", "ORDINARIO", ValorNormativo.de("1"))
                    .numero("CUOTAS_MAXIMAS_FRACCIONAMIENTO", "ORDINARIO", ValorNormativo.de("12"))
                    .numero(
                            PoliticasDeRedondeoSelladas.TIPO,
                            PuntoDeRedondeo.CUOTA.name(),
                            ValorNormativo.de("2"))
                    .texto(
                            PoliticasDeRedondeoSelladas.TIPO,
                            PuntoDeRedondeo.CUOTA.name(),
                            RoundingMode.HALF_UP.name())
                    .construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(EJERCICIO);
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            return CONJUNTO;
        }
    }
}
