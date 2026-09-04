package kamayuk.rentas.tesoreria.pagos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
import org.junit.jupiter.api.AfterAll;
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
 * P5D AC 3 y AC 4 — el buzon de entrada de pagos, contra PostgreSQL real (ADR-0026 §3).
 *
 * <ul>
 *   <li><b>AC 3:</b> un pago inyectado dos veces con el mismo {@code pagoId} produce <b>UN solo
 *       asiento</b>. Y no solo secuencialmente: con diez hilos de verdad, que es donde el {@code
 *       if} de Java se cuela y el indice unico no.
 *   <li><b>AC 4:</b> una anulacion produce un <b>asiento de reversion</b> y ningun {@code DELETE}.
 *       Lo primero se mide contando filas del libro; lo segundo lo comprueba el escaner de fuentes
 *       —{@code cuenta_corriente_asiento} esta en {@code TABLAS_PROTEGIDAS}—, que es lo que el
 *       encargo pide: comprobarlo con el escaner y no leyendo el codigo.
 * </ul>
 *
 * <p>Contra PostgreSQL de verdad y como {@code sgtm_app}. Contra un doble no se puede demostrar ni
 * el indice unico, ni que diez hilos se serialicen en el motor, ni que la fila del buzon y el
 * asiento caigan en la misma transaccion.
 */
@DisplayName("P5D AC 3 y AC 4 — el buzon de pagos")
class PagoInyectadoDosVecesTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-03-16T14:00:00Z"), ZoneOffset.UTC);

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final String TRIBUTO = "PREDIAL";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long contribuyente;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;
    private static JdbcClient jdbc;

    private static PagoRecibidoRepository buzon;
    private static RecibirPago recibir;
    private static RegistrarAsiento registrarAsiento;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260101", "Municipalidad del buzon");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        registrarAsiento = envolver(new RegistrarAsiento(asientos, saldos, auditoria, RELOJ));
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);
        RegistroDeAbonos abonos =
                envolver(
                        new RegistroDeAbonosCuentaCorriente(
                                asientos, saldos, registrarAsiento, calculo, redondeo));

        buzon = new PagoRecibidoRepositoryJdbc(jdbc);
        // Los TRES objetos, cada uno con su proxy: `RecibirPago` no abre transaccion,
        // `ImputacionDelPago` abre la suya y `RechazoDelPago` abre una NUEVA. Montarlos en un solo
        // objeto es lo que hizo fallar esta prueba dos veces — la auto-invocacion no pasa por el
        // proxy y la anotacion no se aplica (#536, #430).
        recibir =
                new RecibirPago(
                        envolver(new ImputacionDelPago(buzon, abonos, RELOJ)),
                        envolver(new RechazoDelPago(buzon)));

        contribuyente = sembrarContribuyente();
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
        OrigenContext.fijar(new Origen("publicador.caja", null, null));
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 3 — un pago inyectado dos veces produce UN solo asiento")
    class UnSoloAsiento {

        @Test
        @DisplayName("secuencialmente: el segundo intento no imputa y devuelve el que ya estaba")
        void secuencialmenteNoImputaDosVeces() {
            cargarDeuda("AC3-A", "500.00");
            UUID pagoId = UUID.randomUUID();

            PagoRecibido primero =
                    sinTransaccion(() -> recibir.recibir(pagoDe(pagoId, "AC3-A", "500.00")).pago());
            assertThat(primero.estado()).isEqualTo(EstadoDelPagoRecibido.APLICADO);
            assertThat(primero.asientos()).isEqualTo(1);

            int trasElPrimero = abonosCon("RECIBO 001-AC3-A");

            PagoRecibido segundo =
                    sinTransaccion(() -> recibir.recibir(pagoDe(pagoId, "AC3-A", "500.00")).pago());
            assertThat(segundo.pagoId()).isEqualTo(pagoId);

            assertThat(abonosCon("RECIBO 001-AC3-A"))
                    .as(
                            "el mismo pagoId, dos veces: UN solo asiento. La garantia es"
                                    + " `pago_recibido_uq` y no un `if`")
                    .isEqualTo(trasElPrimero)
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("con DIEZ hilos: sigue habiendo un solo asiento y una sola fila de buzon")
        void conDiezHilosSigueHabiendoUno() throws Exception {
            cargarDeuda("AC3-B", "700.00");
            UUID pagoId = UUID.randomUUID();

            // Diez hilos de verdad, arrancando a la vez. Es donde una comprobacion previa en Java
            // se cuela: los diez leen «no esta» y los diez imputan (#188, #44, #52).
            int hilos = 10;
            CountDownLatch salida = new CountDownLatch(1);
            AtomicInteger fallos = new AtomicInteger();
            ExecutorService piscina = Executors.newFixedThreadPool(hilos);
            try {
                List<Callable<Void>> tareas = new java.util.ArrayList<>(hilos);
                for (int i = 0; i < hilos; i++) {
                    tareas.add(
                            () -> {
                                TenantContext.fijar(new MunicipalidadId(municipalidad));
                                OrigenContext.fijar(new Origen("publicador.caja", null, null));
                                salida.await(10, TimeUnit.SECONDS);
                                try {
                                    // SIN transaccion, igual que el borde HTTP: ver
                                    // `sinTransaccion`.
                                    recibir.recibir(pagoDe(pagoId, "AC3-B", "700.00"));
                                } catch (org.springframework.dao.DataAccessException
                                        | IllegalStateException choque) {
                                    // Un choque de clave es un resultado legitimo de la carrera:
                                    // significa que otro hilo llego primero. Lo que NO puede pasar
                                    // es que haya dos asientos, y eso se mide abajo.
                                    fallos.incrementAndGet();
                                } finally {
                                    TenantContext.limpiar();
                                }
                                return null;
                            });
                }
                List<Future<Void>> futuros = new java.util.ArrayList<>(hilos);
                for (Callable<Void> tarea : tareas) {
                    futuros.add(piscina.submit(tarea));
                }
                salida.countDown();
                for (Future<Void> futuro : futuros) {
                    futuro.get(30, TimeUnit.SECONDS);
                }
            } finally {
                piscina.shutdownNow();
            }

            assertThat(abonosCon("RECIBO 001-AC3-B"))
                    .as(
                            "diez entregas simultaneas del mismo pago: UN asiento. Dos serian el"
                                    + " doble de deuda extinguida, y ninguna cifra pareceria mal")
                    .isEqualTo(1);
            assertThat(filasDeBuzonCon(pagoId)).as("y una sola fila en el buzon").isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("AC 4 — una anulacion REVERSA, y no borra")
    class LaAnulacionReversa {

        @Test
        @DisplayName("produce asientos de reversion y deja los originales en pie")
        void produceAsientosDeReversion() {
            cargarDeuda("AC4-A", "300.00");
            UUID pago = UUID.randomUUID();
            sinTransaccion(() -> recibir.recibir(pagoDe(pago, "AC4-A", "300.00")).pago());

            int asientosAntes = asientosDelContribuyente();
            int abonosDelCobro = abonosCon("RECIBO 001-AC4-A");
            assertThat(abonosDelCobro).isEqualTo(1);

            PagoRecibido anulado =
                    sinTransaccion(
                            () ->
                                    recibir.recibir(
                                                    anulacionDe(
                                                            UUID.randomUUID(),
                                                            pago,
                                                            "AC4-A",
                                                            "300.00"))
                                            .pago());

            assertThat(anulado.estado()).isEqualTo(EstadoDelPagoRecibido.APLICADO);
            assertThat(anulado.asientos())
                    .as("la reversion deja sus propios asientos")
                    .isGreaterThan(0);
            assertThat(abonosCon("RECIBO 001-AC4-A"))
                    .as(
                            "los asientos del cobro SIGUEN AHI: el libro es inmutable (ADR-0006) y"
                                    + " lo que se hace es escribir los contrarios, no quitar los que"
                                    + " habia")
                    .isEqualTo(abonosDelCobro);
            assertThat(asientosDelContribuyente())
                    .as("y el libro tiene MAS filas que antes, no menos")
                    .isGreaterThan(asientosAntes);
            assertThat(asientosCon("ANULACION RECIBO 001-AC4-A"))
                    .as("marcados con el documento de la anulacion, para poder encontrarlos")
                    .isEqualTo(anulado.asientos());
        }

        @Test
        @DisplayName("una anulacion inyectada dos veces tampoco reversa dos veces")
        void laAnulacionTambienEsIdempotente() {
            cargarDeuda("AC4-B", "250.00");
            UUID pago = UUID.randomUUID();
            sinTransaccion(() -> recibir.recibir(pagoDe(pago, "AC4-B", "250.00")).pago());

            UUID anulacion = UUID.randomUUID();
            sinTransaccion(
                    () -> recibir.recibir(anulacionDe(anulacion, pago, "AC4-B", "250.00")).pago());
            int trasLaPrimera = asientosCon("ANULACION RECIBO 001-AC4-B");

            sinTransaccion(
                    () -> recibir.recibir(anulacionDe(anulacion, pago, "AC4-B", "250.00")).pago());

            assertThat(asientosCon("ANULACION RECIBO 001-AC4-B"))
                    .as(
                            "reversar dos veces dejaria al contribuyente debiendo el doble de lo"
                                    + " que pago — el defecto que #34 midio con cuatro anulaciones"
                                    + " donde debe haber una")
                    .isEqualTo(trasLaPrimera);
        }
    }

    @Nested
    @DisplayName("Y un pago que no se puede imputar no se pierde")
    class ElQueNoSePuedeImputar {

        @Test
        @DisplayName("queda RECHAZADO con su motivo, y no deja ningun asiento")
        void quedaRechazadoConSuMotivo() {
            // Sin cargar deuda: no hay nada que abonar.
            // Con un tributo PROPIO, y no es un detalle: las otras pruebas de esta clase cargan
            // deuda de PREDIAL para el mismo contribuyente y ejercicio, asi que un pago de PREDIAL
            // «sin deuda» encontraria la de ellas y se aplicaria. La prueba pasaria a medir el
            // orden de ejecucion en vez del rechazo.
            UUID pagoId = UUID.randomUUID();
            PagoRecibido rechazado =
                    sinTransaccion(
                            () ->
                                    recibir.recibir(
                                                    pagoDe(
                                                            pagoId,
                                                            "AC-SIN-DEUDA",
                                                            "100.00",
                                                            "ARBITRIOS"))
                                            .pago());

            assertThat(rechazado.estado()).isEqualTo(EstadoDelPagoRecibido.RECHAZADO);
            assertThat(rechazado.motivo()).isNotNull();
            assertThat(rechazado.asientos()).isZero();
            assertThat(abonosCon("RECIBO 001-AC-SIN-DEUDA"))
                    .as("no se asienta nada a medias")
                    .isZero();
            assertThat(filasDeBuzonCon(pagoId))
                    .as(
                            "y la fila del buzon SIGUE: es dinero cobrado que alguien tiene que"
                                    + " mirar, y la conciliacion del dia lo cuenta")
                    .isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------

    private static PagoRecibido pagoDe(UUID pagoId, String sufijo, String importe) {
        return pagoDe(pagoId, sufijo, importe, TRIBUTO);
    }

    private static PagoRecibido pagoDe(UUID pagoId, String sufijo, String importe, String tributo) {
        ReferenciaDeObligacion referencia =
                new ReferenciaDeObligacion(tributo, EJERCICIO, null, null, HOY);
        String cuerpo =
                "{\"pagoId\":\""
                        + pagoId
                        + "\",\"tipo\":\"PAGO_REGISTRADO\",\"sistemaOrigen\":\"rentas\","
                        + "\"total\":\""
                        + importe
                        + "\",\"ordenes\":[{\"referenciaExterna\":\""
                        + referencia.texto()
                        + "\",\"importe\":\""
                        + importe
                        + "\"}]}";
        return PagoRecibido.enTransito(
                pagoId,
                TipoDePagoRecibido.PAGO_REGISTRADO,
                null,
                "caja",
                "001-" + sufijo,
                contribuyente,
                HOY,
                Dinero.de(importe),
                List.of(referencia),
                cuerpo,
                RELOJ.instant());
    }

    private static PagoRecibido anulacionDe(
            UUID pagoId, UUID original, String sufijo, String importe) {
        String cuerpo =
                "{\"pagoId\":\""
                        + pagoId
                        + "\",\"tipo\":\"PAGO_ANULADO\",\"pagoOriginalId\":\""
                        + original
                        + "\",\"total\":\""
                        + importe
                        + "\"}";
        return PagoRecibido.enTransito(
                pagoId,
                TipoDePagoRecibido.PAGO_ANULADO,
                original,
                "caja",
                "001-" + sufijo,
                contribuyente,
                HOY,
                Dinero.de(importe),
                List.of(),
                cuerpo,
                RELOJ.instant());
    }

    /** Un cargo en el libro contra el que abonar. */
    private static void cargarDeuda(String sufijo, String importe) {
        enTransaccion(
                () ->
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
                                        Dinero.de(importe),
                                        HOY.minusMonths(1),
                                        "EMISION " + sufijo),
                                Observacion.de("emision de la prueba del buzon " + sufijo)));
    }

    private static int abonosCon(String documento) {
        return contar(
                "SELECT count(*) FROM cuenta_corriente_asiento"
                        + " WHERE documento_origen = ? AND tipo = 'ABONO'",
                documento);
    }

    private static int asientosCon(String documento) {
        return contar(
                "SELECT count(*) FROM cuenta_corriente_asiento WHERE documento_origen = ?",
                documento);
    }

    private static int asientosDelContribuyente() {
        return contar(
                "SELECT count(*) FROM cuenta_corriente_asiento WHERE contribuyente_id = "
                        + contribuyente);
    }

    private static int filasDeBuzonCon(UUID pagoId) {
        return contar(
                "SELECT count(*) FROM pago_recibido WHERE pago_id = CAST(? AS uuid)",
                pagoId.toString());
    }

    private static int contar(String sql, Object... valores) {
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

    /**
     * Como se llama a {@code recibir} en produccion: <b>SIN transaccion abierta</b>.
     *
     * <p>No es una comodidad de la prueba. Envolverlo en una —que es lo que esta clase hacia al
     * escribirse— produce un INTERBLOQUEO: la transaccion de fuera se queda abierta con la fila del
     * buzon insertada y marcada <i>rollback-only</i>, y el rechazo abre una nueva que intenta
     * insertar el mismo {@code pago_id} y espera a que la primera termine. Se cuelga sin un solo
     * mensaje; lo delato {@code pg_stat_activity}. Desde entonces {@code RecibirPago} lo rechaza en
     * el acto, y esta prueba lo llama como lo llama {@code PagoController}.
     */
    private static <T> T sinTransaccion(java.util.function.Supplier<T> que) {
        return que.get();
    }

    private static <T> T enTransaccion(java.util.function.Supplier<T> que) {
        return java.util.Objects.requireNonNull(transaccion.execute(estado -> que.get()));
    }

    private static long sembrarContribuyente() throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            long id =
                    insertar(
                            app,
                            "INSERT INTO contribuyente (municipalidad_id,"
                                    + " codigo_contribuyente, tipo_persona, tipo_documento,"
                                    + " numero_documento, nombre_razon_social, activo,"
                                    + " usuario_registro)"
                                    + " VALUES (?, 'C-00001', 'NATURAL', 'DNI', '70123456',"
                                    + "         'FULANO DE TAL', true, 'prueba') RETURNING id",
                            municipalidad);
            app.commit();
            return id;
        }
    }

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            long id =
                    insertar(
                            owner,
                            "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                    + " VALUES (?, ?, 'DISTRITAL') RETURNING id",
                            ubigeo,
                            nombre);
            owner.commit();
            return id;
        }
    }

    private static long insertar(Connection conexion, String sql, Object... valores)
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
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }
}
