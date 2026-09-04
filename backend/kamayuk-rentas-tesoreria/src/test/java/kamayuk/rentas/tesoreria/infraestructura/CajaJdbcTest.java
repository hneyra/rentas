package kamayuk.rentas.tesoreria.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
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
import kamayuk.rentas.tesoreria.aplicacion.AbrirCaja;
import kamayuk.rentas.tesoreria.aplicacion.CobrarDeuda;
import kamayuk.rentas.tesoreria.aplicacion.CobrarTasa;
import kamayuk.rentas.tesoreria.dobles.SinConvenios;
import kamayuk.rentas.tesoreria.dominio.Caja;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.LineaDeTasaPedida;
import kamayuk.rentas.tesoreria.dominio.NumeroDeRecibo;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.tesoreria.dominio.ReciboRepository;
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
 * #33 — La caja contra PostgreSQL de verdad, conectada como {@code sgtm_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>Atomicidad</b> (AC 1). Se provoca el fallo con el recibo ya insertado y sus abonos ya
 *       asentados, y se cuenta: cero recibos, cero lineas, cero asientos, y el correlativo sin
 *       avanzar. Contra un doble esto solo probaria que el doble no guarda.
 *   <li><b>El doble cobro</b> (AC 3), seriado y con <b>hilos de verdad</b>. La segunda cobranza no
 *       trae su cifra en la mano: relee el libro con el abono de la primera dentro. Un doble pasa
 *       esta prueba haga lo que haga el codigo real.
 *   <li><b>Que {@code sgtm_app} no pueda actualizar un recibo</b> (AC 5). No es una convencion: es
 *       un {@code REVOKE} de V29, y se comprueba intentandolo por SQL directo.
 *   <li><b>El aislamiento</b> (AC 6). Con el contexto de B, la caja y los recibos de A no existen.
 *   <li><b>La numeracion sin huecos</b> bajo concurrencia, que es lo que el {@code UPSERT} del
 *       correlativo compra frente a un {@code SELECT} + {@code UPDATE}.
 * </ul>
 */
@DisplayName("#33 — La caja contra PostgreSQL")
class CajaJdbcTest {

    private static final LocalDate PAGO = LocalDate.of(2026, 3, 15);

    /**
     * 2026 y no 2025: {@code cuenta_corriente_asiento} se particiona por ejercicio y V2 solo
     * declara las particiones de 2026 y 2027. Un asiento de 2025 no falla «raro»: falla con «no
     * partition of relation found», que es justo lo que debe pasar.
     */
    private static final Ejercicio EJERCICIO_DEUDA = new Ejercicio(2026);

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-03-15T14:30:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static long areaId;
    private static long cajaId;
    private static JdbcClient jdbc;
    private static TransactionTemplate transaccion;
    private static TenantTransactionManager gestor;

    private static CajaRepositoryJdbc cajas;
    private static ReciboRepositoryJdbc recibos;
    private static TasaRepositoryJdbc tasas;
    private static RegistrarAsiento registrarAsiento;
    private static RegistroDeAbonos abonos;
    private static CobrarDeuda cobrarDeuda;
    private static CobrarTasa cobrarTasa;

    /**
     * Estatico a proposito: JUnit crea una instancia por metodo de prueba, asi que un contador de
     * instancia volveria a empezar en cada uno y dos contribuyentes distintos acabarian con el
     * mismo numero de documento.
     */
    private static final AtomicInteger CONTADOR = new AtomicInteger();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240101", "Municipalidad de la caja");
        otraMunicipalidad = crearMunicipalidad("240102", "Municipalidad vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        cajas = new CajaRepositoryJdbc(jdbc);
        recibos = new ReciboRepositoryJdbc(jdbc);
        tasas = new TasaRepositoryJdbc(jdbc);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        registrarAsiento = new RegistrarAsiento(asientos, saldos, auditoria, RELOJ);
        // La misma politica de mora y de redondeo que la aplicacion cablea hoy: sin
        // acumulacion. El interes devengado es cero, asi que lo que esta prueba mide es
        // el camino de la cobranza y no una regla de calculo -que sigue bloqueada por D-02-.
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, java.math.RoundingMode.HALF_UP);
        abonos =
                envolver(
                        new RegistroDeAbonosCuentaCorriente(
                                asientos, saldos, envolver(registrarAsiento), calculo, redondeo));

        AbrirCaja abrirCaja =
                envolver(
                        new AbrirCaja(
                                cajas, new TurnoDeCajaRepositoryJdbc(jdbc), auditoria, RELOJ));
        cobrarDeuda =
                envolver(
                        new CobrarDeuda(
                                abrirCaja,
                                abonos,
                                recibos,
                                SinConvenios.formalizador(RELOJ),
                                auditoria,
                                RELOJ));
        cobrarTasa = envolver(new CobrarTasa(abrirCaja, tasas, recibos, auditoria, RELOJ));

        areaId = crearArea(municipalidad, "A-01");
        cajaId = crearCaja(municipalidad, "C-01", "001", areaId);
        crearArea(otraMunicipalidad, "A-01");
        crearCaja(otraMunicipalidad, "C-01", "001", null);
    }

    /**
     * Envuelve el caso de uso en un proxy transaccional <b>de verdad</b>.
     *
     * <p>Lo que se quiere verificar es la anotacion {@code @Transactional} del codigo de
     * produccion. Si la prueba abriera la transaccion ella misma con un {@code
     * TransactionTemplate}, quitarle la anotacion al caso de uso no pondria nada en rojo y la
     * prueba de atomicidad estaria midiendo la transaccion de la prueba.
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
    static void cerrar() {
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
    @DisplayName("AC 1 — La cobranza es atomica")
    class DeLaAtomicidad {

        @Test
        @DisplayName("si algo falla despues del recibo, no queda ni el recibo ni sus abonos")
        void unFalloAMitadNoDejaNada() {
            long contribuyente = contribuyenteConDeuda("ATOM-1", Dinero.de("300.00"));
            long asientosAntes = contarAsientos(contribuyente);
            long ultimoAntes = correlativoDe("001");

            // El recibo y su detalle YA estan insertados y los abonos YA estan asentados
            // cuando esto revienta: es el peor momento posible, el que deja el dinero
            // cobrado sin papel o el papel sin dinero si la transaccion no cubriera todo.
            CobrarDeuda conFalloAlFinal =
                    envolver(
                            new CobrarDeuda(
                                    envolver(
                                            new AbrirCaja(
                                                    cajas,
                                                    new TurnoDeCajaRepositoryJdbc(jdbc),
                                                    new AuditoriaJdbc(jdbc, RELOJ),
                                                    RELOJ)),
                                    abonos,
                                    new ReciboQueRevientaAlEmitir(recibos),
                                    SinConvenios.formalizador(RELOJ),
                                    new AuditoriaJdbc(jdbc, RELOJ),
                                    RELOJ));

            assertThatThrownBy(
                            () ->
                                    conFalloAlFinal.cobrar(
                                            cobranza(contribuyente, "C-01", null), porQue()))
                    .isInstanceOf(FalloSimulado.class);

            assertThat(contarRecibos(contribuyente))
                    .as("cero recibos: la transaccion se llevo el que ya estaba insertado")
                    .isZero();
            assertThat(contarLineasDeRecibo(contribuyente)).as("cero lineas de detalle").isZero();
            assertThat(contarAsientos(contribuyente))
                    .as("cero asientos nuevos: el abono se fue con el recibo")
                    .isEqualTo(asientosAntes);
            assertThat(correlativoDe("001"))
                    .as("el correlativo tampoco avanza: es una fila, no una secuencia")
                    .isEqualTo(ultimoAntes);
        }

        @Test
        @DisplayName("cuando todo sale bien, el recibo, su detalle y los abonos estan los tres")
        void loQueSeCobraQuedaEntero() {
            long contribuyente = contribuyenteConDeuda("ATOM-2", Dinero.de("250.00"));

            Recibo emitido = cobrarDeuda.cobrar(cobranza(contribuyente, "C-01", null), porQue());

            assertThat(emitido.total()).isEqualTo(Dinero.de("250.00"));
            assertThat(contarRecibos(contribuyente)).isEqualTo(1);
            assertThat(contarLineasDeRecibo(contribuyente)).isEqualTo(1);
            assertThat(deudaHoy(contribuyente))
                    .as("y la deuda quedo en cero: el abono llego al libro")
                    .isEqualTo(Dinero.CERO);
        }
    }

    @Nested
    @DisplayName("AC 2 y 4 — El importe sale del libro, y con su fecha")
    class DelImporte {

        @Test
        @DisplayName("el recibo cobra lo que el libro dice, y dice a que fecha lo dijo")
        void elReciboLlevaLaFechaDeLaDeuda() {
            long contribuyente = contribuyenteConDeuda("FECHA-1", Dinero.de("120.00"));

            Recibo emitido = cobrarDeuda.cobrar(cobranza(contribuyente, "C-01", null), porQue());

            assertThat(emitido.total()).isEqualTo(Dinero.de("120.00"));
            assertThat(emitido.actualizadoA()).isEqualTo(PAGO);
            assertThat(
                            enTransaccion(
                                            () ->
                                                    jdbc.sql(
                                                                    "SELECT actualizado_a FROM"
                                                                            + " recibo WHERE id = :id")
                                                            .param("id", emitido.id())
                                                            .query(java.sql.Date.class)
                                                            .single())
                                    .toLocalDate())
                    .as("y la fecha esta en la base, no solo en la respuesta (RNF-075)")
                    .isEqualTo(PAGO);
        }

        @Test
        @DisplayName("el desglose del recibo es el que el libro devolvio, congelado")
        void elDesgloseEstaCongelado() {
            long contribuyente = contribuyenteConDeuda("FECHA-2", Dinero.de("90.00"));
            Recibo emitido = cobrarDeuda.cobrar(cobranza(contribuyente, "C-01", null), porQue());

            assertThat(
                            enTransaccion(
                                    () ->
                                            jdbc.sql(
                                                            "SELECT insoluto FROM recibo_detalle"
                                                                    + " WHERE recibo_id = :id")
                                                    .param("id", emitido.id())
                                                    .query(java.math.BigDecimal.class)
                                                    .single()))
                    .isEqualByComparingTo("90.00");
        }
    }

    @Nested
    @DisplayName("AC 3 — Cobrar dos veces la misma deuda es imposible")
    class DelDobleCobro {

        @Test
        @DisplayName("seriadas: la segunda ve el abono de la primera y no encuentra nada")
        void laSegundaVeElAbonoDeLaPrimera() {
            long contribuyente = contribuyenteConDeuda("DOBLE-1", Dinero.de("100.00"));

            cobrarDeuda.cobrar(cobranza(contribuyente, "C-01", null), porQue());

            assertThatThrownBy(
                            () ->
                                    cobrarDeuda.cobrar(
                                            cobranza(contribuyente, "C-01", null), porQue()))
                    .isInstanceOf(CobrarDeuda.NadaQueCobrar.class)
                    .hasMessageContaining("tenia deuda");
            assertThat(contarRecibos(contribuyente)).isEqualTo(1);
        }

        @Test
        @DisplayName("con hilos y DIEZ ventanillas distintas: solo una cobra")
        void diezVentanillasSimultaneasProducenUnCobro() throws Exception {
            long contribuyente = contribuyenteConDeuda("DOBLE-2", Dinero.de("500.00"));

            // Diez CAJAS distintas, con su propia serie y su propio cajero. Es deliberado, y
            // costo dos intentos encontrarlo:
            //
            //  - con una sola caja y un solo cajero, el turno los serializa;
            //  - con una sola caja y diez cajeros, los serializa el contador de la serie, que
            //    la cobranza bloquea al reservar el numero.
            //
            // Las dos versiones pasaban en verde con el `FOR UPDATE` del saldo QUITADO, o sea
            // que no median lo que dicen medir. Con diez cajas y diez series, lo unico que
            // queda entre las diez cobranzas y el doble cobro es el bloqueo de las filas de
            // saldo_proyectado.
            int hilos = 10;
            for (int i = 0; i < hilos; i++) {
                crearCajaDeLaSerie("C-CONC" + i, "S" + i);
            }
            CountDownLatch salida = new CountDownLatch(1);
            List<Callable<Boolean>> tareas = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                String cajero = "cajero." + i;
                String caja = "C-CONC" + i;
                tareas.add(
                        () -> {
                            // El contexto de tenant y el origen son ThreadLocal: cada hilo
                            // empieza sin ellos, igual que empezaria una peticion.
                            TenantContext.fijar(new MunicipalidadId(municipalidad));
                            OrigenContext.fijar(new Origen(cajero, null, null));
                            salida.await(10, TimeUnit.SECONDS);
                            try {
                                cobrarDeuda.cobrar(
                                        cobranza(contribuyente, caja, cajero, null), porQue());
                                return true;
                            } catch (CobrarDeuda.NadaQueCobrar yaPagado) {
                                return false;
                            }
                        });
            }

            ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
            int cobradas = 0;
            try {
                List<Future<Boolean>> futuros = new ArrayList<>();
                for (Callable<Boolean> tarea : tareas) {
                    futuros.add(ejecutor.submit(tarea));
                }
                salida.countDown();
                for (Future<Boolean> futuro : futuros) {
                    if (Boolean.TRUE.equals(futuro.get(60, TimeUnit.SECONDS))) {
                        cobradas++;
                    }
                }
            } finally {
                ejecutor.shutdownNow();
            }

            assertThat(cobradas)
                    .as("solo una encuentra deuda: el bloqueo del saldo serializa a las diez")
                    .isEqualTo(1);
            assertThat(contarRecibos(contribuyente)).isEqualTo(1);
            assertThat(deudaHoy(contribuyente)).isEqualTo(Dinero.CERO);
        }

        @Test
        @DisplayName("reenviar el mismo intento devuelve el recibo de la primera vez")
        void elReenvioNoEmiteOtro() {
            long contribuyente = contribuyenteConDeuda("DOBLE-3", Dinero.de("70.00"));
            String clave = "idem-" + contribuyente;

            Recibo primero = cobrarDeuda.cobrar(cobranza(contribuyente, "C-01", clave), porQue());
            Recibo repetido = cobrarDeuda.cobrar(cobranza(contribuyente, "C-01", clave), porQue());

            assertThat(repetido.id()).isEqualTo(primero.id());
            assertThat(repetido.numero()).isEqualTo(primero.numero());
            assertThat(contarRecibos(contribuyente)).isEqualTo(1);
        }

        @Test
        @DisplayName("la base rechaza dos recibos con la misma clave, aunque se inserten a mano")
        void laBaseRechazaLaClaveRepetida() {
            long contribuyente = contribuyenteConDeuda("DOBLE-4", Dinero.de("40.00"));
            String clave = "idem-directo-" + contribuyente;
            cobrarDeuda.cobrar(cobranza(contribuyente, "C-01", clave), porQue());

            assertThatThrownBy(
                            () ->
                                    transaccion.execute(
                                            estado -> {
                                                TenantContext.fijar(
                                                        new MunicipalidadId(municipalidad));
                                                return jdbc.sql(
                                                                "INSERT INTO recibo"
                                                                        + " (municipalidad_id, serie,"
                                                                        + "  numero, caja_id, cajero,"
                                                                        + "  contribuyente_id,"
                                                                        + "  forma_pago, total,"
                                                                        + "  actualizado_a,"
                                                                        + "  clave_idempotencia,"
                                                                        + "  usuario_registro,"
                                                                        + "  observacion)"
                                                                        + " VALUES (:muni, '001',"
                                                                        + "  999999, :caja, 'x', :con,"
                                                                        + "  'EFECTIVO', 1, :fecha,"
                                                                        + "  :clave, 'x', 'x')")
                                                        .param("muni", municipalidad)
                                                        .param("caja", cajaId)
                                                        .param("con", contribuyente)
                                                        .param("fecha", PAGO)
                                                        .param("clave", clave)
                                                        .update();
                                            }))
                    .as("recibo_idempotencia_uq es la garantia final, no la lectura previa")
                    .hasStackTraceContaining("recibo_idempotencia_uq");
        }
    }

    @Nested
    @DisplayName("AC 5 — El recibo no se edita ni se borra")
    class DeLaInmutabilidad {

        @Test
        @DisplayName("sgtm_app no tiene privilegio para actualizar un recibo (V29)")
        void noSePuedeActualizarUnRecibo() {
            long contribuyente = contribuyenteConDeuda("INMUT-1", Dinero.de("30.00"));
            Recibo emitido = cobrarDeuda.cobrar(cobranza(contribuyente, "C-01", null), porQue());

            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            // El total y no `estado`: V30 retiro esa columna
                                            // porque decia EMITIDO para siempre. La regla es la
                                            // misma y ahora se mide sobre una columna que existe.
                                            jdbc.sql(
                                                            "UPDATE recibo SET total = 1"
                                                                    + " WHERE id = :id")
                                                    .param("id", emitido.id())
                                                    .update()))
                    .as("anular es agregar un movimiento (#34), no reescribir el papel")
                    .isEqualTo(PRIVILEGIO_INSUFICIENTE);
        }

        @Test
        @DisplayName("sgtm_app tampoco puede actualizar el detalle congelado")
        void noSePuedeActualizarElDetalle() {
            long contribuyente = contribuyenteConDeuda("INMUT-2", Dinero.de("30.00"));
            Recibo emitido = cobrarDeuda.cobrar(cobranza(contribuyente, "C-01", null), porQue());

            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            jdbc.sql(
                                                            "UPDATE recibo_detalle SET monto = 1"
                                                                    + " WHERE recibo_id = :id")
                                                    .param("id", emitido.id())
                                                    .update()))
                    .isEqualTo(PRIVILEGIO_INSUFICIENTE);
        }

        @Test
        @DisplayName("ni borrarlo: RNF-051 no le dio nunca el privilegio")
        void noSePuedeBorrarUnRecibo() {
            long contribuyente = contribuyenteConDeuda("INMUT-3", Dinero.de("30.00"));
            Recibo emitido = cobrarDeuda.cobrar(cobranza(contribuyente, "C-01", null), porQue());

            assertThat(
                            sqlStateAlIntentar(
                                    () ->
                                            jdbc.sql("DELETE FROM recibo WHERE id = :id")
                                                    .param("id", emitido.id())
                                                    .update()))
                    .isEqualTo(PRIVILEGIO_INSUFICIENTE);
        }
    }

    @Nested
    @DisplayName("AC 6 — La caja de una municipalidad no ve la de otra")
    class DelAislamiento {

        @Test
        @DisplayName("desde B, el recibo de A no existe")
        void desdeBElReciboDeANoExiste() {
            long contribuyente = contribuyenteConDeuda("RLS-1", Dinero.de("60.00"));
            Recibo emitido = cobrarDeuda.cobrar(cobranza(contribuyente, "C-01", null), porQue());

            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
            java.util.Optional<Recibo> desdeB =
                    transaccion.execute(
                            estado -> {
                                TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
                                return recibos.porNumero(emitido.numero());
                            });

            assertThat(desdeB)
                    .as("la misma serie y el mismo numero existen en A; desde B, no hay fila")
                    .isEmpty();
        }

        @Test
        @DisplayName("desde B, la deuda de A no se puede cobrar: su contribuyente no existe")
        void desdeBNoSeCobraLaDeudaDeA() {
            long contribuyente = contribuyenteConDeuda("RLS-2", Dinero.de("60.00"));

            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
            assertThatThrownBy(
                            () ->
                                    cobrarDeuda.cobrar(
                                            cobranza(contribuyente, "C-01", null), porQue()))
                    .as("RLS no deja ver ni el saldo ni los asientos del contribuyente de A")
                    .isInstanceOf(CobrarDeuda.NadaQueCobrar.class);
        }
    }

    @Nested
    @DisplayName("La numeracion")
    class DeLaNumeracion {

        @Test
        @DisplayName("veinte reservas concurrentes de la misma serie dan veinte numeros seguidos")
        void veinteReservasNoDejanHuecos() throws Exception {
            long antes = correlativoDe("NUM");
            Caja deLaSerie = crearCajaDeLaSerie("C-NUM", "NUM");

            int hilos = 20;
            CountDownLatch salida = new CountDownLatch(1);
            List<Callable<Long>> tareas = new ArrayList<>();
            for (int i = 0; i < hilos; i++) {
                tareas.add(
                        () -> {
                            TenantContext.fijar(new MunicipalidadId(municipalidad));
                            salida.await(10, TimeUnit.SECONDS);
                            return enTransaccion(() -> recibos.siguienteNumero(deLaSerie)).numero();
                        });
            }

            ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
            List<Long> numeros = new ArrayList<>();
            try {
                List<Future<Long>> futuros = new ArrayList<>();
                for (Callable<Long> tarea : tareas) {
                    futuros.add(ejecutor.submit(tarea));
                }
                salida.countDown();
                for (Future<Long> futuro : futuros) {
                    numeros.add(futuro.get(60, TimeUnit.SECONDS));
                }
            } finally {
                ejecutor.shutdownNow();
            }

            assertThat(numeros).doesNotHaveDuplicates().hasSize(hilos);
            assertThat(numeros.stream().sorted().toList())
                    .as("sin huecos: el UPSERT bloquea la fila, no la lee y la escribe aparte")
                    .isEqualTo(
                            java.util.stream.LongStream.rangeClosed(antes + 1, antes + hilos)
                                    .boxed()
                                    .toList());
        }

        @Test
        @DisplayName("dos cajas distintas no comparten correlativo: cada una tiene su serie")
        void dosCajasNoSePisan() {
            long contribuyente = contribuyenteConDeuda("SERIE-1", Dinero.de("40.00"));
            long otro = contribuyenteConDeuda("SERIE-2", Dinero.de("40.00"));
            crearCajaDeLaSerie("C-OTRA", "OTRA");

            Recibo enUna = cobrarDeuda.cobrar(cobranza(contribuyente, "C-01", null), porQue());
            Recibo enOtra = cobrarDeuda.cobrar(cobranza(otro, "C-OTRA", null), porQue());

            assertThat(enUna.numero().serie()).isEqualTo("001");
            assertThat(enOtra.numero().serie()).isEqualTo("OTRA");
            assertThat(enOtra.numero().numero())
                    .as("la caja nueva empieza en 1 aunque la otra lleve varios")
                    .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("La caja de tasas")
    class DeLasTasas {

        @Test
        @DisplayName("cobra la tarifa registrada y la base comprueba la multiplicacion")
        void cobraLaTarifaRegistrada() {
            long contribuyente = crearContribuyente(municipalidad, "TASA-1");
            crearTasa("T-100", Dinero.de("12.50"), LocalDate.of(2026, 1, 1));

            Recibo emitido =
                    cobrarTasa.cobrar(
                            new CobrarTasa.CobroDeTasas(
                                    "C-01",
                                    "cajero.prueba",
                                    contribuyente,
                                    List.of(new LineaDeTasaPedida("T-100", 4)),
                                    FormaDePago.EFECTIVO,
                                    PAGO,
                                    null),
                            porQue());

            assertThat(emitido.total()).isEqualTo(Dinero.de("50.00"));
            assertThat(emitido.tipoDePago()).isEqualTo(TipoDePago.TASA);
            assertThat(contarAsientos(contribuyente))
                    .as("un derecho de tramite no es deuda tributaria: no toca el libro")
                    .isZero();
        }

        @Test
        @DisplayName("la base rechaza una linea de tasa cuyo monto no es cantidad x precio")
        void laBaseComprubaLaMultiplicacion() {
            long contribuyente = crearContribuyente(municipalidad, "TASA-2");
            crearTasa("T-200", Dinero.de("10.00"), LocalDate.of(2026, 1, 1));
            Recibo emitido =
                    cobrarTasa.cobrar(
                            new CobrarTasa.CobroDeTasas(
                                    "C-01",
                                    "cajero.prueba",
                                    contribuyente,
                                    List.of(new LineaDeTasaPedida("T-200", 2)),
                                    FormaDePago.EFECTIVO,
                                    PAGO,
                                    null),
                            porQue());

            long tasaId =
                    enTransaccion(
                            () ->
                                    jdbc.sql(
                                                    "SELECT tasa_id FROM recibo_detalle"
                                                            + " WHERE recibo_id = :id")
                                            .param("id", emitido.id())
                                            .query(Long.class)
                                            .single());

            assertThatThrownBy(
                            () ->
                                    enTransaccion(
                                            () ->
                                                    jdbc.sql(
                                                                    "INSERT INTO recibo_detalle"
                                                                            + " (municipalidad_id,"
                                                                            + "  recibo_id, tributo,"
                                                                            + "  concepto, tasa_id,"
                                                                            + "  cantidad,"
                                                                            + "  precio_unitario,"
                                                                            + "  monto, insoluto)"
                                                                            + " VALUES (:muni, :rec,"
                                                                            + "  'T-200', 'TASA',"
                                                                            + "  :tasa, 2, 10.00,"
                                                                            + "  15.00, 15.00)")
                                                            .param("muni", municipalidad)
                                                            .param("rec", emitido.id())
                                                            .param("tasa", tasaId)
                                                            .update()))
                    .as("2 x 10.00 no son 15.00, y eso no depende de que la aplicacion lo mire")
                    .hasStackTraceContaining("recibo_detalle_tasa_ck");
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Un {@link ReciboRepository} que emite de verdad y despues revienta. */
    private record ReciboQueRevientaAlEmitir(ReciboRepository real) implements ReciboRepository {

        @Override
        public NumeroDeRecibo siguienteNumero(Caja caja) {
            return real.siguienteNumero(caja);
        }

        @Override
        public Recibo emitir(Recibo recibo, @Nullable String claveDeIdempotencia) {
            Recibo emitido = real.emitir(recibo, claveDeIdempotencia);
            throw new FalloSimulado(emitido.numero().impreso());
        }

        @Override
        public java.util.Optional<Recibo> porClaveDeIdempotencia(String clave) {
            return real.porClaveDeIdempotencia(clave);
        }

        @Override
        public java.util.Optional<Recibo> porNumero(NumeroDeRecibo numero) {
            return real.porNumero(numero);
        }

        @Override
        public kamayuk.rentas.compartido.Pagina<kamayuk.rentas.tesoreria.dominio.ReciboEnConsulta>
                buscar(
                        kamayuk.rentas.tesoreria.dominio.CriterioDeRecibos criterio,
                        kamayuk.rentas.compartido.Paginacion paginacion) {
            return real.buscar(criterio, paginacion);
        }
    }

    /** El fallo que la prueba provoca a mitad de la cobranza. */
    private static final class FalloSimulado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        FalloSimulado(String numero) {
            super("Fallo provocado despues de insertar el recibo " + numero);
        }
    }

    private CobrarDeuda.Cobranza cobranza(
            long contribuyenteId, String codigoDeCaja, @Nullable String clave) {
        return cobranza(contribuyenteId, codigoDeCaja, "cajero.prueba", clave);
    }

    private CobrarDeuda.Cobranza cobranza(
            long contribuyenteId, String codigoDeCaja, String cajero, @Nullable String clave) {
        return new CobrarDeuda.Cobranza(
                codigoDeCaja,
                cajero,
                contribuyenteId,
                List.of(new SeleccionDeObligacion("PREDIAL", EJERCICIO_DEUDA, null, null)),
                FormaDePago.EFECTIVO,
                TipoDePago.NORMAL,
                null,
                PAGO,
                clave,
                null);
    }

    private static Observacion porQue() {
        return Observacion.de("Cobranza en ventanilla, prueba de #33");
    }

    /** {@code insufficient_privilege}: el SQLSTATE de un {@code REVOKE} que muerde. */
    private static final String PRIVILEGIO_INSUFICIENTE = "42501";

    /**
     * El SQLSTATE con el que la base rechaza la sentencia, o {@code null} si la deja pasar.
     *
     * <p>Se compara el codigo y no el texto del mensaje a proposito: PostgreSQL lo traduce al
     * idioma del servidor, y una prueba que buscara «permission denied» se pondria verde por el
     * motivo equivocado en un motor en castellano -o roja sin que nada este mal-.
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
    private long contribuyenteConDeuda(String sufijo, Dinero monto) {
        long id = crearContribuyente(municipalidad, sufijo);
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO_DEUDA,
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

    private Dinero deudaHoy(long contribuyenteId) {
        return enTransaccion(
                () -> {
                    CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
                    List<Asiento> asientos =
                            new AsientoRepositoryJdbc(jdbc)
                                    .deLaObligacion(
                                            new kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo(
                                                    contribuyenteId,
                                                    "PREDIAL",
                                                    EJERCICIO_DEUDA,
                                                    0,
                                                    null,
                                                    null));
                    return calculo.deudaActualizadaA(
                                    asientos,
                                    PAGO,
                                    new PoliticaDeRedondeo(2, java.math.RoundingMode.HALF_UP))
                            .total();
                });
    }

    private long contarRecibos(long contribuyenteId) {
        return enTransaccion(
                () ->
                        jdbc.sql("SELECT count(*) FROM recibo WHERE contribuyente_id = :c")
                                .param("c", contribuyenteId)
                                .query(Long.class)
                                .single());
    }

    private long contarLineasDeRecibo(long contribuyenteId) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT count(*) FROM recibo_detalle d"
                                                + " JOIN recibo r ON r.id = d.recibo_id"
                                                + " WHERE r.contribuyente_id = :c")
                                .param("c", contribuyenteId)
                                .query(Long.class)
                                .single());
    }

    private long contarAsientos(long contribuyenteId) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT count(*) FROM cuenta_corriente_asiento"
                                                + " WHERE contribuyente_id = :c")
                                .param("c", contribuyenteId)
                                .query(Long.class)
                                .single());
    }

    private long correlativoDe(String serie) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT coalesce(max(ultimo), 0) FROM recibo_correlativo"
                                                + " WHERE serie = :serie")
                                .param("serie", serie)
                                .query(Long.class)
                                .single());
    }

    private Caja crearCajaDeLaSerie(String codigo, String serie) {
        return enTransaccion(
                () ->
                        cajas.porCodigo(codigo)
                                .orElseGet(
                                        () -> {
                                            crearCaja(municipalidad, codigo, serie, areaId);
                                            return cajas.porCodigo(codigo).orElseThrow();
                                        }));
    }

    private long crearContribuyente(long muni, String sufijo) {
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
                String.format("%08d", 10_000_000 + orden));
    }

    private void crearTasa(String codigo, Dinero importe, LocalDate desde) {
        insertarComoOwner(
                municipalidad,
                "INSERT INTO tasa (municipalidad_id, codigo, descripcion, area_id,"
                        + " partida_presupuestal, importe, vigencia_desde, documento_fuente)"
                        + " VALUES (?, ?, 'Concepto del TUPA', ?, '1.3.1.1.1.1', ?, ?,"
                        + "         'TUPA 2026 de la prueba') RETURNING id",
                municipalidad,
                codigo,
                areaId,
                importe.valor(),
                desde);
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

    /**
     * Inserta una fila de siembra como {@code sgtm_owner}, con el contexto de tenant fijado.
     *
     * <p>Fijarlo no es opcional aunque quien escriba sea el dueno de la tabla: {@code FORCE ROW
     * LEVEL SECURITY} alcanza tambien al dueno, y sin contexto la insercion falla con «unrecognized
     * configuration parameter» —que es exactamente lo que debe pasar (DAT-01 §0)—.
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
}
