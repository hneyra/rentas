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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
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
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.aplicacion.AcogimientoAConvenioCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistroDeAbonosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.ConstanciaDeNoAdeudo;
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
import kamayuk.rentas.tesoreria.aplicacion.CerrarConvenio;
import kamayuk.rentas.tesoreria.aplicacion.CobrarDeuda;
import kamayuk.rentas.tesoreria.aplicacion.CondicionesParametrizadas;
import kamayuk.rentas.tesoreria.aplicacion.ConsultaDeConvenios;
import kamayuk.rentas.tesoreria.aplicacion.FormalizarConvenio;
import kamayuk.rentas.tesoreria.aplicacion.RegistrarPreconvenio;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.ConvenioEnConsulta;
import kamayuk.rentas.tesoreria.dominio.CriterioDeConvenios;
import kamayuk.rentas.tesoreria.dominio.EstadoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.NumeroDeConvenio;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.tesoreria.dominio.TipoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeGarantia;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDePago;
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
 * #35 — El ciclo de vida del convenio contra PostgreSQL de verdad, como {@code sgtm_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>El ciclo completo</b> (AC central). Deuda, preconvenio, inicial cobrada en caja,
 *       convenio con cuotas, fase CONVENIO en el libro real, quiebre, y las deudas de vuelta a
 *       <b>su</b> fase con sus importes. La comparacion es <b>asiento por asiento</b>: se recorre
 *       cada fila del libro antes y despues, se netea por (fase, concepto) y se exige que ninguna
 *       fila anterior haya cambiado. Contra un doble esto solo probaria que el doble repone lo que
 *       le quitaron.
 *   <li><b>Que la fase de origen se respete.</b> Una cuota que venia de coactiva vuelve a coactiva,
 *       no a ordinaria. Es lo que {@code convenio_deuda.fase_origen} existe para poder decir, y
 *       solo se ve con la proyeccion del saldo de verdad.
 *   <li><b>Que sin inicial cobrada no hay convenio.</b> El preconvenio no toca el libro: se
 *       comprueba contando asientos.
 *   <li><b>Que reejecutar no duplica</b>, con <b>hilos de verdad</b>: la barrera son {@code
 *       convenio_cuota_uq}, {@code convenio_deuda_uq} y los dos indices unicos parciales de {@code
 *       convenio_movimiento}, no un {@code if}.
 *   <li><b>Que {@code sgtm_app} no pueda editar ni borrar</b> un convenio, su cronograma ni su
 *       acta. No es una convencion: son los privilegios que V31 concede, y se comprueba
 *       intentandolo.
 *   <li><b>El aislamiento</b>: con el contexto de B, el convenio de A no existe.
 * </ul>
 */
@DisplayName("#35 — Convenio de fraccionamiento contra PostgreSQL")
class ConvenioJdbcTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);

    /** El libro se particiona por ejercicio y V2 solo declara 2026 y 2027. */
    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final Clock RELOJ = relojDe(HOY);

    /** La deuda ordinaria del contribuyente de la prueba. */
    private static final Dinero PREDIAL = Dinero.de("300.00");

    /** Y la que viene de coactiva: es la que prueba que la fase de origen se respeta. */
    private static final Dinero ARBITRIOS = Dinero.de("200.00");

    private static final SeleccionDeObligacion LO_PREDIAL =
            new SeleccionDeObligacion("PREDIAL", EJERCICIO, null, null);

    private static final SeleccionDeObligacion LO_COACTIVO =
            new SeleccionDeObligacion("ARBITRIO", EJERCICIO, null, null);

    private static Clock relojDe(LocalDate dia) {
        return Clock.fixed(dia.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    }

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static JdbcClient jdbc;
    private static TransactionTemplate transaccion;
    private static TenantTransactionManager gestor;

    private static ConvenioRepositoryJdbc convenios;
    private static MovimientoDeConvenioRepositoryJdbc movimientos;
    private static MovimientoDeReciboRepositoryJdbc movimientosDeRecibo;
    private static ReciboRepositoryJdbc recibos;
    private static RegistrarAsiento registrarAsiento;
    private static AcogimientoAConvenio acogimiento;
    private static RegistrarPreconvenio preconvenios;
    private static CobrarDeuda cobrarDeuda;
    private static CerrarConvenio cerrar;
    private static ConsultaDeConvenios consulta;
    private static ConsultarDeuda deudas;

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240401", "Municipalidad de los convenios");
        otraMunicipalidad = crearMunicipalidad("240402", "Municipalidad vecina de #35");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        convenios = new ConvenioRepositoryJdbc(jdbc);
        movimientos = new MovimientoDeConvenioRepositoryJdbc(jdbc);
        movimientosDeRecibo = new MovimientoDeReciboRepositoryJdbc(jdbc);
        recibos = new ReciboRepositoryJdbc(jdbc);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        registrarAsiento = new RegistrarAsiento(asientos, saldos, auditoria, RELOJ);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

        acogimiento =
                envolver(
                        new AcogimientoAConvenioCuentaCorriente(
                                asientos, saldos, envolver(registrarAsiento), calculo, redondeo));
        deudas = envolver(new ConsultarDeuda(asientos, saldos, calculo, redondeo, RELOJ));

        CondicionesParametrizadas condiciones =
                new CondicionesParametrizadas(new ParametrosDeLaPrueba());
        preconvenios =
                envolver(
                        new RegistrarPreconvenio(
                                convenios, acogimiento, condiciones, auditoria, RELOJ));

        CajaRepositoryJdbc cajas = new CajaRepositoryJdbc(jdbc);
        TurnoDeCajaRepositoryJdbc turnos = new TurnoDeCajaRepositoryJdbc(jdbc);
        AbrirCaja abrirCaja = envolver(new AbrirCaja(cajas, turnos, auditoria, RELOJ));
        RegistroDeAbonos abonos =
                envolver(
                        new RegistroDeAbonosCuentaCorriente(
                                asientos, saldos, envolver(registrarAsiento), calculo, redondeo));
        FormalizarConvenio formalizar =
                envolver(
                        new FormalizarConvenio(
                                convenios, movimientos, acogimiento, auditoria, RELOJ));
        cobrarDeuda =
                envolver(new CobrarDeuda(abrirCaja, abonos, recibos, formalizar, auditoria, RELOJ));
        cerrar =
                envolver(
                        new CerrarConvenio(
                                convenios,
                                movimientos,
                                movimientosDeRecibo,
                                acogimiento,
                                preconvenios,
                                auditoria,
                                RELOJ));
        consulta = envolver(new ConsultaDeConvenios(convenios, movimientos, RELOJ));

        long areaId = crearArea(municipalidad, "A-35");
        crearCaja(municipalidad, "C-35", "R35", areaId);
        crearArea(otraMunicipalidad, "A-35");
        crearCaja(otraMunicipalidad, "C-35", "R35", null);
    }

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
    @DisplayName("AC central — Del preconvenio al quiebre, y la deuda de vuelta")
    class DelCicloCompleto {

        @Test
        @DisplayName("el preconvenio no toca el libro: sin inicial cobrada no hay convenio")
        void elPreconvenioNoTocaElLibro() {
            long titular = contribuyenteConDeuda("CICLO-1");
            long antes = contarAsientos(titular);

            Convenio convenio = registrarPreconvenio(titular, 6, "20");

            assertThat(contarAsientos(titular))
                    .as("un preconvenio no acoge nada: el libro no se entera de que existe")
                    .isEqualTo(antes);
            assertThat(estadoDe(convenio)).isEqualTo(EstadoDeConvenio.PRECONVENIO);
            assertThat(faseDe(titular, "PREDIAL"))
                    .as("y la deuda sigue donde estaba, en cobranza ordinaria")
                    .isEqualTo(Fase.ORDINARIA);
            assertThat(deudaDe(titular, "PREDIAL")).isEqualTo(PREDIAL);
        }

        @Test
        @DisplayName("cobrar la inicial en caja formaliza el convenio y mueve la deuda a CONVENIO")
        void laInicialFormalizaYAcoge() {
            long titular = contribuyenteConDeuda("CICLO-2");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            // 500 acogidos, 20 % de inicial: 100,00. El importe no viaja en la peticion.
            assertThat(convenio.montoTotal()).isEqualTo(Dinero.de("500.00"));
            assertThat(convenio.cuotaInicial()).isEqualTo(Dinero.de("100.00"));

            Recibo recibo = cobrarLaInicial(titular, convenio);

            assertThat(recibo.tipoDePago()).isEqualTo(TipoDePago.PRECONVENIO);
            assertThat(recibo.total())
                    .as("el papel dice lo que el cronograma congelo, no lo que mando el cliente")
                    .isEqualTo(Dinero.de("100.00"));
            assertThat(estadoDe(convenio)).isEqualTo(EstadoDeConvenio.VIGENTE);
            assertThat(faseDe(titular, "PREDIAL"))
                    .as("la deuda ordinaria pasa a fase CONVENIO, con asientos")
                    .isEqualTo(Fase.CONVENIO);
            assertThat(faseDe(titular, "ARBITRIO")).isEqualTo(Fase.CONVENIO);
            assertThat(deudaDe(titular, "PREDIAL"))
                    .as("y el total adeudado no cambia: mover de fase no condona nada")
                    .isEqualTo(PREDIAL);
            assertThat(deudaDe(titular, "ARBITRIO")).isEqualTo(ARBITRIOS);
        }

        @Test
        @DisplayName("la constancia de no adeudo sigue negandose con la deuda en fase CONVENIO")
        void laConstanciaSigueNegandose() {
            long titular = contribuyenteConDeuda("CICLO-3");
            String codigo = codigoDe(titular);
            assertThat(constancia(codigo).seNiega()).isTrue();

            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, convenio);

            ConstanciaDeNoAdeudo tras = constancia(codigo);
            assertThat(tras.seNiega())
                    .as("fraccionar no extingue la deuda: la mueve de fase. Basta una con saldo")
                    .isTrue();
            assertThat(tras.obligaciones())
                    .as("y el detalle la muestra en su fase nueva")
                    .anySatisfy(fila -> assertThat(fila.fase()).isEqualTo(Fase.CONVENIO));
        }

        @Test
        @DisplayName(
                "el quiebre devuelve cada deuda a SU fase de origen, comparado asiento por asiento")
        void elQuiebreDevuelveAsientoPorAsiento() {
            long titular = contribuyenteConDeuda("CICLO-4");

            // El estado del libro ANTES del convenio, fila a fila.
            List<FilaDelLibro> antes = libroDe(titular);
            Map<String, Dinero> netoAntes = netearPorFaseYConcepto(antes);
            Map<String, Dinero> deudaAntes = deudaPorTributo(titular);
            Map<String, Fase> faseAntes = fasePorTributo(titular);

            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, convenio);
            assertThat(faseDe(titular, "ARBITRIO")).isEqualTo(Fase.CONVENIO);

            quebrar(convenio, "DOS CUOTAS CONSECUTIVAS IMPAGAS");

            List<FilaDelLibro> despues = libroDe(titular);

            // 1. Ninguna fila anterior cambio ni desaparecio. Es lo que separa «devolver
            //    con asientos» de un UPDATE de la columna de fase: el libro solo crece.
            assertThat(despues)
                    .as("el libro solo se agrega: ninguna fila anterior se edito ni se borro")
                    .containsAll(antes);
            assertThat(despues.size())
                    .as("y crecio: el acogimiento y su devolucion escribieron sus asientos")
                    .isGreaterThan(antes.size());

            // 2. El neteo por (fase, concepto) vuelve a ser exactamente el de antes.
            assertThat(netearPorFaseYConcepto(despues))
                    .as("centimo a centimo, y por fase: es la comparacion que pide #35")
                    .isEqualTo(netoAntes);

            // 3. Y las dos deudas vuelven a su fase, con su importe.
            assertThat(deudaPorTributo(titular)).isEqualTo(deudaAntes);
            assertThat(fasePorTributo(titular))
                    .as("la que venia de coactiva vuelve a COACTIVA, no a ORDINARIA")
                    .isEqualTo(faseAntes)
                    .containsEntry("ARBITRIO", Fase.COACTIVA)
                    .containsEntry("PREDIAL", Fase.ORDINARIA);

            assertThat(estadoDe(convenio)).isEqualTo(EstadoDeConvenio.QUEBRADO);
        }

        @Test
        @DisplayName("el acta del quiebre congela lo devuelto y cuenta sus asientos")
        void elActaCongelaLoDevuelto() {
            long titular = contribuyenteConDeuda("CICLO-5");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, convenio);

            CerrarConvenio.Cerrado cerrado = quebrar(convenio, "INCUMPLIMIENTO");

            assertThat(cerrado.cierre().importe())
                    .as("lo devuelto es lo pendiente en fase de convenio, releido del libro")
                    .isEqualTo(PREDIAL.mas(ARBITRIOS));
            assertThat(cerrado.cierre().asientos())
                    .as("dos por cuota devuelta: el abono en convenio y el cargo en su fase")
                    .isEqualTo(4);
            assertThat(cerrado.cierre().motivoDelCierre()).isEqualTo("INCUMPLIMIENTO");
            assertThat(cerrado.cierre().usuarioRegistro()).isEqualTo("cajero.prueba");
        }

        @Test
        @DisplayName("los asientos de ida y de vuelta se distinguen por su documento de origen")
        void idaYVueltaSeDistinguen() {
            long titular = contribuyenteConDeuda("CICLO-6");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, convenio);
            quebrar(convenio, "INCUMPLIMIENTO");

            assertThat(asientosCon(FormalizarConvenio.documentoDelConvenio(convenio.numero())))
                    .as("el acogimiento marca sus asientos con el numero del convenio")
                    .isEqualTo(4);
            assertThat(
                            asientosCon(
                                    CerrarConvenio.documentoDelCierre(
                                            TipoDeMovimientoDeConvenio.QUIEBRE, convenio.numero())))
                    .as("y la devolucion con uno distinto: si no, una no podria hallar la otra")
                    .isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Lo que el estado del convenio no admite")
    class DeLosLimites {

        @Test
        @DisplayName("un preconvenio no se puede quebrar: no acogio nada que devolver")
        void unPreconvenioNoSeQuiebra() {
            long titular = contribuyenteConDeuda("LIM-1");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");

            assertThatThrownBy(() -> quebrar(convenio, "INCUMPLIMIENTO"))
                    .isInstanceOf(CerrarConvenio.ConvenioSinFormalizar.class)
                    .hasMessageContaining("no acogio ninguna deuda");
            assertThat(faseDe(titular, "PREDIAL")).isEqualTo(Fase.ORDINARIA);
        }

        @Test
        @DisplayName("formalizar dos veces seguidas: la segunda dice que ya lo estaba")
        void laSegundaFormalizacionSeRechaza() {
            long titular = contribuyenteConDeuda("LIM-2");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, convenio);

            assertThatThrownBy(() -> cobrarLaInicial(titular, convenio))
                    .isInstanceOf(FormalizarConvenio.ConvenioNoEsPreconvenio.class);
            assertThat(formalizacionesDe(convenio)).isEqualTo(1);
            assertThat(deudaDe(titular, "PREDIAL"))
                    .as("dos acogimientos dejarian la deuda contada dos veces")
                    .isEqualTo(PREDIAL);
        }

        @Test
        @DisplayName("cerrar dos veces seguidas: la segunda dice que ya estaba cerrado")
        void elSegundoCierreSeRechaza() {
            long titular = contribuyenteConDeuda("LIM-3");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, convenio);
            quebrar(convenio, "INCUMPLIMIENTO");

            assertThatThrownBy(() -> quebrar(convenio, "OTRA VEZ"))
                    .isInstanceOf(MovimientoDeConvenioRepository.ConvenioYaCerrado.class);
            assertThat(cierresDe(convenio)).isEqualTo(1);
            assertThat(deudaDe(titular, "PREDIAL"))
                    .as("dos devoluciones dejarian al contribuyente debiendo el doble")
                    .isEqualTo(PREDIAL);
        }

        @Test
        @DisplayName("anular y quebrar el mismo convenio: el segundo acto se rechaza")
        void anularYQuebrarSeExcluyen() {
            long titular = contribuyenteConDeuda("LIM-4");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            Recibo recibo = cobrarLaInicial(titular, convenio);
            anularElRecibo(recibo);

            cerrarCon(convenio, TipoDeMovimientoDeConvenio.ANULACION, "NO DEBIO EXISTIR");

            assertThatThrownBy(() -> quebrar(convenio, "INCUMPLIMIENTO"))
                    .isInstanceOf(MovimientoDeConvenioRepository.ConvenioYaCerrado.class);
            assertThat(cierresDe(convenio))
                    .as("convenio_movimiento_cierre_uq es parcial sobre los TRES tipos a la vez")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("con DIEZ hilos formalizando el mismo convenio, solo uno lo consigue")
        @SuppressWarnings("checkstyle:IllegalCatch")
        void diezFormalizacionesSimultaneasProducenUna() throws Exception {
            long titular = contribuyenteConDeuda("CONC-1");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");

            int hilos = 10;
            CountDownLatch salida = new CountDownLatch(1);
            List<Callable<Boolean>> tareas = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                String quien = "cajero." + i;
                tareas.add(
                        () -> {
                            TenantContext.fijar(new MunicipalidadId(municipalidad));
                            OrigenContext.fijar(new Origen(quien, null, null));
                            salida.await(10, TimeUnit.SECONDS);
                            try {
                                cobrarLaInicial(titular, convenio, quien);
                                return true;
                            } catch (RuntimeException rechazada) {
                                // Se captura lo ancho a proposito: lo que se mide es
                                // cuantas ganan, y las nueve que pierden pueden hacerlo
                                // por el indice unico o por el aborto de su transaccion.
                                return false;
                            }
                        });
            }

            ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
            int formalizadas = 0;
            try {
                List<Future<Boolean>> futuros = new ArrayList<>();
                for (Callable<Boolean> tarea : tareas) {
                    futuros.add(ejecutor.submit(tarea));
                }
                salida.countDown();
                for (Future<Boolean> futuro : futuros) {
                    if (Boolean.TRUE.equals(futuro.get(60, TimeUnit.SECONDS))) {
                        formalizadas++;
                    }
                }
            } finally {
                ejecutor.shutdownNow();
            }

            assertThat(formalizadas)
                    .as("convenio_movimiento_formalizacion_uq: una sola gana")
                    .isEqualTo(1);
            assertThat(formalizacionesDe(convenio)).isEqualTo(1);
            assertThat(deudaDe(titular, "PREDIAL"))
                    .as("diez acogimientos dejarian la deuda contada diez veces")
                    .isEqualTo(PREDIAL);
        }
    }

    @Nested
    @DisplayName("La anulacion, con el recibo de por medio")
    class DeLaAnulacion {

        @Test
        @DisplayName("no se anula un convenio cuyo recibo de inicial sigue vigente")
        void noSeAnulaConElReciboVigente() {
            long titular = contribuyenteConDeuda("ANUL-1");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, convenio);

            assertThatThrownBy(
                            () ->
                                    cerrarCon(
                                            convenio,
                                            TipoDeMovimientoDeConvenio.ANULACION,
                                            "NO DEBIO EXISTIR"))
                    .isInstanceOf(CerrarConvenio.ReciboDeLaInicialVigente.class)
                    .hasMessageContaining("Anulese primero el recibo");

            assertThat(cierresDe(convenio)).isZero();
            assertThat(faseDe(titular, "PREDIAL"))
                    .as("y nada se devolvio: la deuda sigue en fase de convenio")
                    .isEqualTo(Fase.CONVENIO);
        }

        @Test
        @DisplayName("con el recibo ya anulado, la anulacion procede y devuelve la deuda")
        void conElReciboAnuladoProcede() {
            long titular = contribuyenteConDeuda("ANUL-2");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            Recibo recibo = cobrarLaInicial(titular, convenio);
            anularElRecibo(recibo);

            CerrarConvenio.Cerrado cerrado =
                    cerrarCon(convenio, TipoDeMovimientoDeConvenio.ANULACION, "NO DEBIO EXISTIR");

            assertThat(estadoDe(convenio)).isEqualTo(EstadoDeConvenio.ANULADO);
            assertThat(cerrado.cierre().importe()).isEqualTo(PREDIAL.mas(ARBITRIOS));
            assertThat(fasePorTributo(titular))
                    .containsEntry("PREDIAL", Fase.ORDINARIA)
                    .containsEntry("ARBITRIO", Fase.COACTIVA);
        }

        @Test
        @DisplayName("quebrar no exige anular el recibo: ese dinero si entro")
        void quebrarNoExigeAnularElRecibo() {
            long titular = contribuyenteConDeuda("ANUL-3");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            Recibo recibo = cobrarLaInicial(titular, convenio);

            quebrar(convenio, "INCUMPLIMIENTO");

            assertThat(estadoDe(convenio)).isEqualTo(EstadoDeConvenio.QUEBRADO);
            assertThat(enTransaccion(() -> movimientosDeRecibo.anulacionDe(recibo.id())))
                    .as("el recibo sigue vigente: el convenio existio y se cobro su inicial")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("La reformulacion")
    class DeLaReformulacion {

        @Test
        @DisplayName("quiebra el anterior y abre uno nuevo sobre lo pendiente, en una transaccion")
        void reformularQuiebraYAbre() {
            long titular = contribuyenteConDeuda("REF-1");
            Convenio original = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, original);

            CerrarConvenio.Cerrado cerrado =
                    cerrar.cerrar(
                            new CerrarConvenio.Cierre(
                                    original.numero(),
                                    TipoDeMovimientoDeConvenio.REFORMULACION,
                                    HOY,
                                    "REFORMULADO A PEDIDO DEL CONTRIBUYENTE",
                                    null,
                                    null,
                                    peticionDe(titular, 12, "0")),
                            null,
                            Observacion.de("Se reformula el convenio a doce cuotas"));

            assertThat(estadoDe(original)).isEqualTo(EstadoDeConvenio.REFORMULADO);
            Convenio nuevo = cerrado.reformulado();
            assertThat(nuevo).isNotNull();
            assertThat(nuevo.convenioOrigenId())
                    .as("el convenio nuevo dice de cual sale: el saldo no se queda sin rastro")
                    .isEqualTo(original.idGuardado());
            assertThat(estadoDe(nuevo))
                    .as("y nace como preconvenio: tambien el reformulado espera su inicial")
                    .isEqualTo(EstadoDeConvenio.PRECONVENIO);
            assertThat(cerrado.cierre().convenioNuevoId()).isEqualTo(nuevo.idGuardado());
            assertThat(fasePorTributo(titular))
                    .as("la deuda volvio a su fase mientras el nuevo no se formalice")
                    .containsEntry("PREDIAL", Fase.ORDINARIA)
                    .containsEntry("ARBITRIO", Fase.COACTIVA);
        }
    }

    @Nested
    @DisplayName("Lo que la base impide por si sola")
    class DeLaBase {

        @Test
        @DisplayName("reejecutar la generacion de cuotas no duplica: lo impide el indice unico")
        void reejecutarNoDuplica() {
            long titular = contribuyenteConDeuda("DUP-1");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            long convenioId = convenio.idGuardado();

            assertThat(cuotasDe(convenioId)).isEqualTo(7);
            assertThat(deudaAcogidaDe(convenioId)).isEqualTo(2);

            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            jdbc.sql(
                                                            "INSERT INTO convenio_cuota"
                                                                    + " (municipalidad_id, convenio_id,"
                                                                    + "  numero, vencimiento, monto,"
                                                                    + "  capital)"
                                                                    + " VALUES (:muni, :c, 1, :v, 1,"
                                                                    + " 1)")
                                                    .param("muni", municipalidad)
                                                    .param("c", convenioId)
                                                    .param("v", HOY)
                                                    .update()))
                    .as("convenio_cuota_uq: la cuota 1 de ese convenio ya existe")
                    .isEqualTo(VIOLACION_DE_UNICIDAD);

            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            jdbc.sql(
                                                            "INSERT INTO convenio_deuda"
                                                                    + " (municipalidad_id, convenio_id,"
                                                                    + "  tributo, ejercicio, periodo,"
                                                                    + "  fase_origen, insoluto, monto,"
                                                                    + "  fecha_corte)"
                                                                    + " VALUES (:muni, :c, 'PREDIAL',"
                                                                    + "  2026, 0, 'ORDINARIA', 1, 1,"
                                                                    + "  :f)")
                                                    .param("muni", municipalidad)
                                                    .param("c", convenioId)
                                                    .param("f", HOY)
                                                    .update()))
                    .as("convenio_deuda_uq: esa cuota ya esta acogida a ese convenio")
                    .isEqualTo(VIOLACION_DE_UNICIDAD);

            assertThat(cuotasDe(convenioId)).isEqualTo(7);
            assertThat(deudaAcogidaDe(convenioId)).isEqualTo(2);
        }

        @Test
        @DisplayName("cerrar un convenio exige su motivo: un blanco muere en el CHECK")
        void elMotivoEnBlancoMuereEnElCheck() {
            long titular = contribuyenteConDeuda("CHK-1");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");

            assertThatThrownBy(
                            () ->
                                    insertarMovimiento(
                                            convenio.idGuardado(), "QUIEBRE", "   ", null, null))
                    .as("espacios no son un motivo: btrim lo dice")
                    .hasStackTraceContaining("convenio_movimiento_cierre_ck");
        }

        @Test
        @DisplayName("formalizar sin recibo tampoco pasa: sin cuota inicial no hay convenio")
        void formalizarSinReciboNoPasa() {
            long titular = contribuyenteConDeuda("CHK-2");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");

            assertThatThrownBy(
                            () ->
                                    insertarMovimiento(
                                            convenio.idGuardado(),
                                            "FORMALIZACION",
                                            null,
                                            null,
                                            null))
                    .hasStackTraceContaining("convenio_movimiento_formalizacion_ck");
        }

        @Test
        @DisplayName("sgtm_app no puede actualizar un convenio ni su cronograma (V31)")
        void noSePuedeActualizarUnConvenio() {
            long titular = contribuyenteConDeuda("PRIV-1");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");

            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            jdbc.sql(
                                                            "UPDATE convenio SET numero_cuotas = 99"
                                                                    + " WHERE id = :id")
                                                    .param("id", convenio.idGuardado())
                                                    .update()))
                    .as("un convenio es un acto que el contribuyente firmo: no se edita")
                    .isEqualTo(PRIVILEGIO_INSUFICIENTE);

            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            jdbc.sql(
                                                            "UPDATE convenio_cuota SET monto = 0"
                                                                    + " WHERE convenio_id = :id")
                                                    .param("id", convenio.idGuardado())
                                                    .update()))
                    .isEqualTo(PRIVILEGIO_INSUFICIENTE);
        }

        @Test
        @DisplayName("ni editar o borrar su acta, ni la deuda que acogio")
        void noSePuedeEditarElActa() {
            long titular = contribuyenteConDeuda("PRIV-2");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, convenio);

            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            jdbc.sql(
                                                            "UPDATE convenio_movimiento"
                                                                    + " SET importe = 0"
                                                                    + " WHERE convenio_id = :id")
                                                    .param("id", convenio.idGuardado())
                                                    .update()))
                    .as("la salida comoda: editar el acta en vez del convenio")
                    .isEqualTo(PRIVILEGIO_INSUFICIENTE);

            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            jdbc.sql(
                                                            "DELETE FROM convenio_deuda"
                                                                    + " WHERE convenio_id = :id")
                                                    .param("id", convenio.idGuardado())
                                                    .update()))
                    .as("borrarla dejaria el quiebre sin saber a que fase devolver")
                    .isEqualTo(PRIVILEGIO_INSUFICIENTE);
        }

        @Test
        @DisplayName("desde B, el convenio de A no existe")
        void desdeBElConvenioDeANoExiste() {
            long titular = contribuyenteConDeuda("RLS-35");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            NumeroDeConvenio numero = convenio.numero();
            long convenioId = convenio.idGuardado();

            // El contexto se fija ANTES de abrir la transaccion: el SET LOCAL lo emite el
            // gestor al abrirla, asi que fijarlo solo dentro del callback llegaria tarde.
            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
            Optional<Convenio> desdeB =
                    transaccion.execute(
                            estado -> {
                                TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
                                return convenios.porNumero(numero);
                            });
            assertThat(desdeB).as("la politica RLS de convenio no deja ver la fila de A").isEmpty();

            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
            List<?> cuotasDesdeB =
                    transaccion.execute(
                            estado -> {
                                TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
                                return jdbc.sql(
                                                "SELECT id FROM convenio_deuda"
                                                        + " WHERE convenio_id = :id")
                                        .param("id", convenioId)
                                        .query(Long.class)
                                        .list();
                            });
            assertThat(cuotasDesdeB)
                    .as("ni la de convenio_deuda, que es una tabla nueva de V31")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("#606 — Reenviar el mismo intento no abre un segundo convenio")
    class DeLaIdempotencia {

        @Test
        @DisplayName("el alta reenviada con la misma clave devuelve el convenio de la primera vez")
        void elAltaReenviadaDevuelveElMismo() {
            long titular = contribuyenteConDeuda("IDEM-1");

            Convenio primero = registrarPreconvenio(titular, 6, "20", "idem-alta-1");
            Convenio segundo = registrarPreconvenio(titular, 6, "20", "idem-alta-1");

            assertThat(segundo.numero())
                    .as("dos preconvenios sobre la misma deuda son dos papeles que se contradicen")
                    .isEqualTo(primero.numero());
            assertThat(segundo.idGuardado()).isEqualTo(primero.idGuardado());
            assertThat(conveniosDe(titular)).isEqualTo(1);
        }

        @Test
        @DisplayName("sin clave, dos altas son dos convenios: es el defecto que #606 describe")
        void sinClaveSonDos() {
            long titular = contribuyenteConDeuda("IDEM-2");

            Convenio primero = registrarPreconvenio(titular, 6, "20");
            Convenio segundo = registrarPreconvenio(titular, 6, "20");

            assertThat(segundo.numero()).isNotEqualTo(primero.numero());
            assertThat(conveniosDe(titular)).isEqualTo(2);
        }

        @Test
        @DisplayName("dos filas con la misma clave no caben: lo dice convenio_idempotencia_uq")
        void dosFilasConLaMismaClaveNoCaben() {
            long titular = contribuyenteConDeuda("IDEM-3");
            registrarPreconvenio(titular, 6, "20", "idem-fila-unica");

            assertThat(
                            sqlStateAlIntentar(
                                    () -> insertarConvenioDirecto(titular, "idem-fila-unica")))
                    .as("la garantia es el indice, no el SELECT previo del caso de uso")
                    .isEqualTo(VIOLACION_DE_UNICIDAD);
            assertThat(conveniosDe(titular)).isEqualTo(1);
        }

        @Test
        @DisplayName("con DIEZ hilos insertando la misma clave, solo una fila entra")
        @SuppressWarnings("checkstyle:IllegalCatch")
        void diezAltasSimultaneasConLaMismaClaveProducenUna() throws Exception {
            long titular = contribuyenteConDeuda("IDEM-4");

            // Se insertan filas que SOLO comparten la clave: el correlativo de
            // `convenio_correlativo` es un UPSERT que serializa a los diez hilos, asi que
            // medirlo con el caso de uso entero pasaria en verde con el indice degradado.
            // Es la leccion de #44 (`siguienteCorrelativo`) y de #52 (`documento_numero_uq`).
            int hilos = 10;
            CountDownLatch salida = new CountDownLatch(1);
            List<Callable<Boolean>> tareas = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                int cual = i;
                tareas.add(
                        () -> {
                            TenantContext.fijar(new MunicipalidadId(municipalidad));
                            OrigenContext.fijar(new Origen("cajero." + cual, null, null));
                            salida.await(10, TimeUnit.SECONDS);
                            try {
                                insertarConvenioDirectoEnSuTransaccion(
                                        titular, "idem-carrera", "F-2026-90000" + cual);
                                return true;
                            } catch (RuntimeException rechazada) {
                                return false;
                            }
                        });
            }

            ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
            int entradas = 0;
            try {
                List<Future<Boolean>> futuros = new ArrayList<>();
                for (Callable<Boolean> tarea : tareas) {
                    futuros.add(ejecutor.submit(tarea));
                }
                salida.countDown();
                for (Future<Boolean> futuro : futuros) {
                    if (Boolean.TRUE.equals(futuro.get(60, TimeUnit.SECONDS))) {
                        entradas++;
                    }
                }
            } finally {
                ejecutor.shutdownNow();
            }

            assertThat(entradas).as("convenio_idempotencia_uq: una sola gana").isEqualTo(1);
            assertThat(conveniosDe(titular)).isEqualTo(1);
        }

        @Test
        @DisplayName("una clave que registro el convenio de otro contribuyente se rechaza")
        void laClaveDeOtroSujetoSeRechaza() {
            long titular = contribuyenteConDeuda("IDEM-5");
            long otro = contribuyenteConDeuda("IDEM-6");
            registrarPreconvenio(titular, 6, "20", "idem-de-otro");

            assertThatThrownBy(() -> registrarPreconvenio(otro, 6, "20", "idem-de-otro"))
                    .isInstanceOf(RegistrarPreconvenio.ClaveDeOtraPeticion.class)
                    .hasMessageContaining("es de otro contribuyente");
            assertThat(conveniosDe(otro))
                    .as("y no se le abre ninguno: devolver el ajeno seria peor que fallar")
                    .isZero();
        }

        @Test
        @DisplayName("el cierre reenviado devuelve el acta de la primera vez, no un 409")
        void elCierreReenviadoDevuelveElActa() {
            long titular = contribuyenteConDeuda("IDEM-7");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, convenio);

            CerrarConvenio.Cerrado primero =
                    cerrarCon(
                            convenio,
                            TipoDeMovimientoDeConvenio.QUIEBRE,
                            "INCUMPLIMIENTO",
                            "idem-cierre-1");
            CerrarConvenio.Cerrado segundo =
                    cerrarCon(
                            convenio,
                            TipoDeMovimientoDeConvenio.QUIEBRE,
                            "INCUMPLIMIENTO",
                            "idem-cierre-1");

            assertThat(segundo.cierre().id())
                    .as("el mismo acta: sin la clave, el reenvio se estrellaba con un 409")
                    .isEqualTo(primero.cierre().id());
            assertThat(cierresDe(convenio)).isEqualTo(1);
            assertThat(deudaDe(titular, "PREDIAL"))
                    .as("y sobre todo: la deuda NO vuelve dos veces a su fase de origen")
                    .isEqualTo(PREDIAL);
            assertThat(segundo.devuelto())
                    .as(
                            "lo devuelto esta congelado en el acta; recomponer las cuotas exigiria"
                                    + " releer el libro a otra fecha (regla 9)")
                    .isNull();
        }

        @Test
        @DisplayName("una clave que cerro otro convenio se rechaza en vez de mentir")
        void laClaveDeOtroCierreSeRechaza() {
            long unTitular = contribuyenteConDeuda("IDEM-8");
            Convenio uno = registrarPreconvenio(unTitular, 6, "20");
            cobrarLaInicial(unTitular, uno);
            cerrarCon(uno, TipoDeMovimientoDeConvenio.QUIEBRE, "INCUMPLIMIENTO", "idem-cierre-2");

            long otroTitular = contribuyenteConDeuda("IDEM-9");
            Convenio otro = registrarPreconvenio(otroTitular, 6, "20");
            cobrarLaInicial(otroTitular, otro);

            assertThatThrownBy(
                            () ->
                                    cerrarCon(
                                            otro,
                                            TipoDeMovimientoDeConvenio.QUIEBRE,
                                            "INCUMPLIMIENTO",
                                            "idem-cierre-2"))
                    .isInstanceOf(CerrarConvenio.ClaveDeOtroActo.class);
            assertThat(cierresDe(otro))
                    .as("decir que se cerro un convenio que sigue vivo es peor que fallar")
                    .isZero();
        }

        @Test
        @DisplayName("dos actas con la misma clave no caben: convenio_movimiento_idempotencia_uq")
        void dosActasConLaMismaClaveNoCaben() {
            long unTitular = contribuyenteConDeuda("IDEM-10");
            Convenio uno = registrarPreconvenio(unTitular, 6, "20");
            cobrarLaInicial(unTitular, uno);
            cerrarCon(uno, TipoDeMovimientoDeConvenio.QUIEBRE, "INCUMPLIMIENTO", "idem-acta-unica");

            // Sobre OTRO convenio, para que lo unico que pueda chocar sea la clave:
            // `convenio_movimiento_cierre_uq` es por convenio y aqui no aplica.
            long otroTitular = contribuyenteConDeuda("IDEM-11");
            Convenio otro = registrarPreconvenio(otroTitular, 6, "20");

            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            insertarCierreDirecto(
                                                    otro.idGuardado(), "idem-acta-unica")))
                    .isEqualTo(VIOLACION_DE_UNICIDAD);
        }

        @Test
        @DisplayName("la reformulacion reenviada no abre un segundo preconvenio")
        void laReformulacionReenviadaNoAbreOtroPreconvenio() {
            long titular = contribuyenteConDeuda("IDEM-12");
            Convenio original = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, original);

            CerrarConvenio.Cerrado primera = reformular(titular, original, "idem-reformula");
            CerrarConvenio.Cerrado segunda = reformular(titular, original, "idem-reformula");

            assertThat(conveniosDe(titular))
                    .as("el original y su reformulado, no tres: el reenvio no abre otro")
                    .isEqualTo(2);
            Convenio nuevo = segunda.reformulado();
            assertThat(nuevo).isNotNull();
            Convenio primeroNuevo = primera.reformulado();
            assertThat(primeroNuevo).isNotNull();
            assertThat(nuevo.numero())
                    .as("y el reenvio devuelve el mismo preconvenio, no otro numero")
                    .isEqualTo(primeroNuevo.numero());
            assertThat(claveDelConvenio(nuevo.idGuardado()))
                    .as("la clave la reclama el acta de cierre; el preconvenio nace sin ella")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("La consulta")
    class DeLaConsulta {

        @Test
        @DisplayName("el estado del listado se deriva en SQL y coincide con el del dominio")
        void elEstadoDelListadoCoincide() {
            long titular = contribuyenteConDeuda("CONS-1");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");

            assertThat(unaFila(convenio).estado()).isEqualTo(EstadoDeConvenio.PRECONVENIO);
            cobrarLaInicial(titular, convenio);
            assertThat(unaFila(convenio).estado()).isEqualTo(EstadoDeConvenio.VIGENTE);
            quebrar(convenio, "INCUMPLIMIENTO");

            ConvenioEnConsulta fila = unaFila(convenio);
            assertThat(fila.estado())
                    .as("las dos derivaciones —SQL y dominio— tienen que dar lo mismo")
                    .isEqualTo(EstadoDeConvenio.QUEBRADO)
                    .isEqualTo(estadoDe(convenio));
            assertThat(fila.motivoDelCierre()).isEqualTo("INCUMPLIMIENTO");
        }

        @Test
        @DisplayName("cada cifra viaja con su fecha, y son dos distintas")
        void cadaCifraConSuFecha() {
            long titular = contribuyenteConDeuda("CONS-2");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, convenio);

            LocalDate mesQueViene = HOY.plusMonths(1);
            ConvenioEnConsulta fila = unaFila(convenio, mesQueViene);

            assertThat(fila.fechaCorte())
                    .as("la deuda acogida esta a la fecha de corte del convenio")
                    .isEqualTo(HOY);
            assertThat(fila.saldoA())
                    .as("y el saldo, a la fecha con que se pregunto: no salen de un now()")
                    .isEqualTo(mesQueViene);
            assertThat(fila.deudaAcogida()).isEqualTo(PREDIAL.mas(ARBITRIOS));
            assertThat(fila.pagadas()).isEqualTo(1);
            assertThat(fila.vencidas())
                    .as("un mes despues, la primera cuota ya vencio sin cobrarse")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("el saldo descuenta la inicial cobrada y nada mas")
        void elSaldoDescuentaLaInicial() {
            long titular = contribuyenteConDeuda("CONS-3");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");

            Dinero total = convenio.totalDelCronograma();
            assertThat(unaFila(convenio).saldo())
                    .as("sin la inicial cobrada, se debe el cronograma entero")
                    .isEqualTo(total);

            cobrarLaInicial(titular, convenio);
            assertThat(unaFila(convenio).saldo()).isEqualTo(total.menos(convenio.cuotaInicial()));
        }

        @Test
        @DisplayName("la ficha trae el cronograma, la deuda original y lo que le paso")
        void laFichaTraeElDetalle() {
            long titular = contribuyenteConDeuda("CONS-4");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, convenio);
            quebrar(convenio, "INCUMPLIMIENTO");

            ConsultaDeConvenios.Ficha ficha =
                    enTransaccion(() -> consulta.ficha(convenio.numero())).orElseThrow();

            assertThat(ficha.convenio().cronograma()).hasSize(7);
            assertThat(ficha.convenio().acogida())
                    .as("la deuda original, con la fase de la que salio cada cuota")
                    .hasSize(2)
                    .anySatisfy(fila -> assertThat(fila.faseOrigen()).isEqualTo("COACTIVA"));
            assertThat(ficha.movimientos()).hasSize(2);
            assertThat(ficha.cierre()).isNotNull();
            assertThat(ficha.cuotasPagadas()).isEqualTo(1);
        }

        @Test
        @DisplayName("el filtro por estado usa la misma derivacion que el listado")
        void elFiltroPorEstadoUsaLaMismaDerivacion() {
            long titular = contribuyenteConDeuda("CONS-5");
            Convenio convenio = registrarPreconvenio(titular, 6, "20");
            cobrarLaInicial(titular, convenio);

            assertThat(listar(convenio, EstadoDeConvenio.VIGENTE)).hasSize(1);
            assertThat(listar(convenio, EstadoDeConvenio.QUEBRADO)).isEmpty();
            quebrar(convenio, "INCUMPLIMIENTO");
            assertThat(listar(convenio, EstadoDeConvenio.VIGENTE)).isEmpty();
            assertThat(listar(convenio, EstadoDeConvenio.QUEBRADO)).hasSize(1);
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** {@code insufficient_privilege}: el SQLSTATE de un {@code REVOKE} que muerde. */
    private static final String PRIVILEGIO_INSUFICIENTE = "42501";

    /** {@code unique_violation}: el SQLSTATE de un indice unico que muerde. */
    private static final String VIOLACION_DE_UNICIDAD = "23505";

    private static Convenio registrarPreconvenio(long titular, int cuotas, String inicial) {
        return registrarPreconvenio(titular, cuotas, inicial, null);
    }

    private static Convenio registrarPreconvenio(
            long titular, int cuotas, String inicial, @Nullable String clave) {
        return preconvenios.registrar(
                peticionDe(titular, cuotas, inicial),
                clave,
                Observacion.de("Acogimiento a fraccionamiento, prueba de #35"));
    }

    private static RegistrarPreconvenio.Peticion peticionDe(
            long titular, int cuotas, String inicial) {
        return new RegistrarPreconvenio.Peticion(
                titular,
                List.of(LO_PREDIAL, LO_COACTIVO),
                TipoDeConvenio.ORDINARIO,
                HOY,
                HOY,
                cuotas,
                Alicuota.de(inicial),
                HOY.plusMonths(1),
                TipoDeGarantia.NO_REQUIERE,
                null,
                null,
                null);
    }

    private static Recibo cobrarLaInicial(long titular, Convenio convenio) {
        return cobrarLaInicial(titular, convenio, "cajero.prueba");
    }

    private static Recibo cobrarLaInicial(long titular, Convenio convenio, String cajero) {
        return cobrarDeuda.cobrar(
                new CobrarDeuda.Cobranza(
                        "C-35",
                        cajero,
                        titular,
                        List.of(),
                        FormaDePago.EFECTIVO,
                        TipoDePago.PRECONVENIO,
                        null,
                        HOY,
                        null,
                        convenio.numero().impreso()),
                Observacion.de("Cuota inicial del convenio, prueba de #35"));
    }

    private static CerrarConvenio.Cerrado quebrar(Convenio convenio, String motivo) {
        return cerrarCon(convenio, TipoDeMovimientoDeConvenio.QUIEBRE, motivo);
    }

    private static CerrarConvenio.Cerrado cerrarCon(
            Convenio convenio, TipoDeMovimientoDeConvenio tipo, String motivo) {
        return cerrarCon(convenio, tipo, motivo, null);
    }

    private static CerrarConvenio.Cerrado cerrarCon(
            Convenio convenio,
            TipoDeMovimientoDeConvenio tipo,
            String motivo,
            @Nullable String clave) {
        return cerrar.cerrar(
                new CerrarConvenio.Cierre(
                        convenio.numero(),
                        tipo,
                        HOY,
                        motivo,
                        "RESPONSABLE DE TESORERIA",
                        "MEMO-2026-035",
                        null),
                clave,
                Observacion.de("Se cierra el convenio, prueba de #35"));
    }

    private static void anularElRecibo(Recibo recibo) {
        AnularRecibo anular =
                envolver(
                        new AnularRecibo(
                                recibos,
                                movimientosDeRecibo,
                                new TurnoDeCajaRepositoryJdbc(jdbc),
                                envolver(
                                        new RegistroDeAbonosCuentaCorriente(
                                                new AsientoRepositoryJdbc(jdbc),
                                                new SaldoRepositoryJdbc(jdbc),
                                                envolver(registrarAsiento),
                                                new CalculoDeDeuda(new SinAcumulacion()),
                                                new PoliticaDeRedondeo(2, RoundingMode.HALF_UP))),
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ));
        // Un recibo de cuota inicial no abona en el libro -su efecto es el acogimiento-,
        // asi que no hay asientos que reversar y `reversarAbonos` lo dice. Se anula igual:
        // lo que importa aqui es que quede la fila de anulacion, que es lo que la
        // anulacion del convenio exige.
        try {
            anular.anular(
                    new AnularRecibo.Anulacion(
                            recibo.numero(), "ERROR AL COBRAR LA INICIAL", null, null),
                    Observacion.de("Se anula el recibo de la inicial"));
        } catch (RegistroDeAbonos.SinAbonosQueReversar sinAbonos) {
            // El recibo de la inicial no toco el libro: se registra la anulacion a mano,
            // que es lo que hara #36 cuando el arqueo distinga los dos casos.
            enTransaccion(
                    () ->
                            movimientosDeRecibo.registrar(
                                    kamayuk.rentas.tesoreria.dominio.MovimientoDeRecibo.anulacion(
                                            recibo,
                                            HOY,
                                            "ERROR AL COBRAR LA INICIAL",
                                            null,
                                            null,
                                            recibo.total(),
                                            Observacion.de("Se anula el recibo de la inicial"))));
        }
    }

    private static EstadoDeConvenio estadoDe(Convenio convenio) {
        return enTransaccion(
                () ->
                        EstadoDeConvenio.deLosMovimientos(
                                movimientos.deConvenio(convenio.idGuardado())));
    }

    private static ConvenioEnConsulta unaFila(Convenio convenio) {
        return unaFila(convenio, HOY);
    }

    private static ConvenioEnConsulta unaFila(Convenio convenio, LocalDate aLaFecha) {
        return enTransaccion(
                        () ->
                                consulta.listar(
                                        new CriterioDeConvenios(
                                                convenio.numero().impreso(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                aLaFecha),
                                        new kamayuk.rentas.compartido.Paginacion(
                                                0,
                                                20,
                                                "fecha",
                                                kamayuk.rentas.compartido.Paginacion.Direccion
                                                        .ASCENDENTE)))
                .contenido()
                .get(0);
    }

    private static List<ConvenioEnConsulta> listar(Convenio convenio, EstadoDeConvenio estado) {
        return enTransaccion(
                        () ->
                                consulta.listar(
                                        new CriterioDeConvenios(
                                                convenio.numero().impreso(),
                                                null,
                                                estado,
                                                null,
                                                null,
                                                HOY),
                                        new kamayuk.rentas.compartido.Paginacion(
                                                0,
                                                20,
                                                "fecha",
                                                kamayuk.rentas.compartido.Paginacion.Direccion
                                                        .ASCENDENTE)))
                .contenido();
    }

    private static ConstanciaDeNoAdeudo constancia(String codigo) {
        return enTransaccion(() -> deudas.constanciaDeNoAdeudo(codigo, HOY));
    }

    /**
     * Una fila del libro, con lo que hace falta para comparar dos estados <b>asiento por
     * asiento</b>: el identificador para saber que no desaparecio, y todo lo demas para saber que
     * no se edito.
     */
    private record FilaDelLibro(
            long id, String fase, String concepto, String tipo, String monto, String tributo) {}

    private static List<FilaDelLibro> libroDe(long contribuyenteId) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT id, fase, concepto, tipo, monto, tributo"
                                                + " FROM cuenta_corriente_asiento"
                                                + " WHERE contribuyente_id = :c ORDER BY id")
                                .param("c", contribuyenteId)
                                .query(
                                        (fila, numero) ->
                                                new FilaDelLibro(
                                                        fila.getLong("id"),
                                                        fila.getString("fase"),
                                                        fila.getString("concepto"),
                                                        // `tipo` es char(6) y PostgreSQL
                                                        // rellena con espacios: sin strip,
                                                        // 'CARGO ' no es igual a "CARGO" y
                                                        // TODO asiento contaria como abono.
                                                        // Es la misma llamada que hace
                                                        // AsientoRepositoryJdbc.
                                                        fila.getString("tipo").strip(),
                                                        fila.getBigDecimal("monto")
                                                                .stripTrailingZeros()
                                                                .toPlainString(),
                                                        fila.getString("tributo")))
                                .list());
    }

    /**
     * El neto del libro por (tributo, fase, concepto), recorriendo <b>cada</b> asiento: cargos
     * suman, abonos restan, que es el mismo signo que fija {@code TipoAsiento} en todo el libro.
     *
     * <p>Se calcula aqui, en la prueba, y no llamando a {@code CalculoDeDeuda}: comparar el libro
     * consigo mismo usando la funcion que se quiere proteger no probaria nada si esa funcion
     * cambiara.
     */
    private static Map<String, Dinero> netearPorFaseYConcepto(List<FilaDelLibro> filas) {
        Map<String, Dinero> neto = new LinkedHashMap<>();
        for (FilaDelLibro fila : filas) {
            String llave = fila.tributo() + "/" + fila.fase() + "/" + fila.concepto();
            Dinero monto = Dinero.de(fila.monto());
            Dinero acumulado = neto.getOrDefault(llave, Dinero.CERO);
            neto.put(
                    llave,
                    "CARGO".equals(fila.tipo()) ? acumulado.mas(monto) : acumulado.menos(monto));
        }
        // Las llaves que quedan en cero no describen nada: el par abono/cargo de un
        // movimiento de fase que se hizo y se deshizo tiene que poder desaparecer de la
        // comparacion, o el "antes" y el "despues" nunca serian iguales.
        neto.values().removeIf(Dinero::esCero);
        return neto;
    }

    private static Map<String, Dinero> deudaPorTributo(long contribuyenteId) {
        Map<String, Dinero> deuda = new LinkedHashMap<>();
        deuda.put("PREDIAL", deudaDe(contribuyenteId, "PREDIAL"));
        deuda.put("ARBITRIO", deudaDe(contribuyenteId, "ARBITRIO"));
        return deuda;
    }

    private static Map<String, Fase> fasePorTributo(long contribuyenteId) {
        Map<String, Fase> fases = new LinkedHashMap<>();
        fases.put("PREDIAL", faseDe(contribuyenteId, "PREDIAL"));
        fases.put("ARBITRIO", faseDe(contribuyenteId, "ARBITRIO"));
        return fases;
    }

    private static Dinero deudaDe(long contribuyenteId, String tributo) {
        return enTransaccion(
                () -> {
                    CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
                    List<Asiento> asientos =
                            new AsientoRepositoryJdbc(jdbc)
                                    .deLaObligacion(
                                            new kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo(
                                                    contribuyenteId,
                                                    tributo,
                                                    EJERCICIO,
                                                    0,
                                                    null,
                                                    null));
                    return calculo.deudaActualizadaA(
                                    asientos, HOY, new PoliticaDeRedondeo(2, RoundingMode.HALF_UP))
                            .total();
                });
    }

    /** La fase de la obligacion, leida de la <b>proyeccion</b>: la del ultimo asiento. */
    private static Fase faseDe(long contribuyenteId, String tributo) {
        String fase =
                enTransaccion(
                        () ->
                                jdbc.sql(
                                                "SELECT fase FROM saldo_proyectado"
                                                        + " WHERE contribuyente_id = :c"
                                                        + "   AND tributo = :t")
                                        .param("c", contribuyenteId)
                                        .param("t", tributo)
                                        .query(String.class)
                                        .single());
        return Fase.valueOf(fase);
    }

    private static long contarAsientos(long contribuyenteId) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT count(*) FROM cuenta_corriente_asiento"
                                                + " WHERE contribuyente_id = :c")
                                .param("c", contribuyenteId)
                                .query(Long.class)
                                .single());
    }

    private static long asientosCon(String documentoOrigen) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT count(*) FROM cuenta_corriente_asiento"
                                                + " WHERE documento_origen = :d")
                                .param("d", documentoOrigen)
                                .query(Long.class)
                                .single());
    }

    private static CerrarConvenio.Cerrado reformular(
            long titular, Convenio original, @Nullable String clave) {
        return cerrar.cerrar(
                new CerrarConvenio.Cierre(
                        original.numero(),
                        TipoDeMovimientoDeConvenio.REFORMULACION,
                        HOY,
                        "REFORMULADO A PEDIDO DEL CONTRIBUYENTE",
                        null,
                        null,
                        peticionDe(titular, 12, "0")),
                clave,
                Observacion.de("Se reformula el convenio, prueba de #606"));
    }

    private static long conveniosDe(long contribuyenteId) {
        return contar("convenio", "contribuyente_id", contribuyenteId, null);
    }

    private static @Nullable String claveDelConvenio(long convenioId) {
        return enTransaccion(
                () ->
                        jdbc.sql("SELECT clave_idempotencia FROM convenio WHERE id = :id")
                                .param("id", convenioId)
                                .query(String.class)
                                .optional()
                                .orElse(null));
    }

    /**
     * Una fila de `convenio` escrita por SQL directo, saltandose el caso de uso.
     *
     * <p>Es la unica forma de medir el indice unico de la clave: por el caso de uso, quien
     * serializa a los hilos es el UPSERT del correlativo, no el indice (#44, #52).
     */
    private static void insertarConvenioDirecto(long titular, String clave) {
        insertarConvenioDirecto(titular, clave, "F-2026-99999");
    }

    /** Como la anterior, pero abriendo la transaccion: es lo que hace cada hilo de la carrera. */
    private static void insertarConvenioDirectoEnSuTransaccion(
            long titular, String clave, String numero) {
        enTransaccion(
                () -> {
                    insertarConvenioDirecto(titular, clave, numero);
                    return null;
                });
    }

    private static void insertarConvenioDirecto(long titular, String clave, String numero) {
        jdbc.sql(
                        "INSERT INTO convenio (municipalidad_id, numero,"
                                + " contribuyente_id, tipo, fecha, fecha_corte,"
                                + " conjunto_id, interes_mensual, porcentaje_inicial,"
                                + " maximo_cuotas, monto_total, cuota_inicial,"
                                + " numero_cuotas, usuario_registro, observacion,"
                                + " fecha_registro, clave_idempotencia)"
                                + " VALUES (:muni, :numero, :titular, 'ORDINARIO',"
                                + " :fecha, :fecha, 1, 1, 20, 12, 100, 20, 6,"
                                + " 'prueba', 'insercion directa de la prueba', now(),"
                                + " :clave)")
                .param("muni", municipalidad)
                .param("numero", numero)
                .param("titular", titular)
                .param("fecha", HOY)
                .param("clave", clave)
                .update();
    }

    private static void insertarCierreDirecto(long convenioId, String clave) {
        jdbc.sql(
                        "INSERT INTO convenio_movimiento (municipalidad_id,"
                                + " convenio_id, tipo, fecha, motivo, importe,"
                                + " usuario_registro, fecha_registro, observacion,"
                                + " clave_idempotencia)"
                                + " VALUES (:muni, :c, 'QUIEBRE', :fecha, 'MOTIVO',"
                                + " 1, 'prueba', now(),"
                                + " 'insercion directa de la prueba', :clave)")
                .param("muni", municipalidad)
                .param("c", convenioId)
                .param("fecha", HOY)
                .param("clave", clave)
                .update();
    }

    private static long cuotasDe(long convenioId) {
        return contar("convenio_cuota", "convenio_id", convenioId, null);
    }

    private static long deudaAcogidaDe(long convenioId) {
        return contar("convenio_deuda", "convenio_id", convenioId, null);
    }

    private static long formalizacionesDe(Convenio convenio) {
        return contar(
                "convenio_movimiento",
                "convenio_id",
                convenio.idGuardado(),
                "tipo = 'FORMALIZACION'");
    }

    private static long cierresDe(Convenio convenio) {
        return contar(
                "convenio_movimiento",
                "convenio_id",
                convenio.idGuardado(),
                "tipo IN ('ANULACION','QUIEBRE','REFORMULACION')");
    }

    private static long contar(
            String tabla, String columna, long valor, @Nullable String condicion) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT count(*) FROM "
                                                + tabla
                                                + " WHERE "
                                                + columna
                                                + " = :v"
                                                + (condicion == null ? "" : " AND " + condicion))
                                .param("v", valor)
                                .query(Long.class)
                                .single());
    }

    private static void insertarMovimiento(
            long convenioId,
            String tipo,
            @Nullable String motivo,
            @Nullable Long reciboId,
            @Nullable Integer cuota) {
        enTransaccion(
                () ->
                        jdbc.sql(
                                        "INSERT INTO convenio_movimiento (municipalidad_id,"
                                                + " convenio_id, tipo, fecha, recibo_id, cuota, motivo,"
                                                + " importe, usuario_registro, fecha_registro,"
                                                + " observacion)"
                                                + " VALUES (:muni, :c, :tipo, :fecha, :recibo, :cuota,"
                                                + "  :motivo, 1, 'prueba', now(),"
                                                + "  'insercion directa de la prueba')")
                                .param("muni", municipalidad)
                                .param("c", convenioId)
                                .param("tipo", tipo)
                                .param("fecha", HOY)
                                .param("recibo", reciboId)
                                .param("cuota", cuota)
                                .param("motivo", motivo)
                                .update());
    }

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

    /**
     * Un contribuyente con dos deudas en <b>dos fases distintas</b>: 300 de predial en ordinaria y
     * 200 de arbitrios en coactiva.
     *
     * <p>Las dos fases no son adorno: son lo que hace que el quiebre tenga algo que equivocar. Con
     * todo en ordinaria, devolver «a ordinaria» pasaria aunque la fase de origen no se guardara.
     */
    private static long contribuyenteConDeuda(String sufijo) {
        long id = crearContribuyente(municipalidad, sufijo);
        asentarCargo(id, "PREDIAL", PREDIAL, Fase.ORDINARIA);
        asentarCargo(id, "ARBITRIO", ARBITRIOS, Fase.COACTIVA);
        return id;
    }

    private static void asentarCargo(
            long contribuyenteId, String tributo, Dinero monto, Fase fase) {
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        contribuyenteId,
                                        tributo,
                                        Concepto.INSOLUTO,
                                        TipoAsiento.CARGO,
                                        fase,
                                        null,
                                        null,
                                        null,
                                        null,
                                        monto,
                                        LocalDate.of(2026, 1, 2),
                                        "DETERMINACION DE LA PRUEBA"),
                                Observacion.de("Se asienta la deuda de la prueba")));
    }

    private static String codigoDe(long contribuyenteId) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT codigo_contribuyente FROM contribuyente"
                                                + " WHERE id = :id")
                                .param("id", contribuyenteId)
                                .query(String.class)
                                .single());
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
                sufijo,
                String.format("%08d", 40_000_000 + orden));
    }

    private static long crearArea(long muni, String codigo) {
        return insertarComoOwner(
                muni,
                "INSERT INTO area (municipalidad_id, codigo, nombre)"
                        + " VALUES (?, ?, 'Unidad de Rentas') RETURNING id",
                muni,
                codigo);
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
     * Los parametros de la prueba: un interes y un maximo de cuotas <b>de mentira</b>.
     *
     * <p>Que sean de mentira es el punto de #35: el mecanismo se prueba entero con las cifras como
     * argumento, y sus valores reales —de ordenanza local— los firma D-02b (#191). Si algun dia
     * este doble desapareciera y la prueba leyera el conjunto sellado de verdad, seguiria pasando
     * con otras cifras, porque ninguna asercion de aqui depende de cuanto valen.
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
