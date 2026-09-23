package kamayuk.rentas.tesoreria.pagos;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #428 — una anulacion que llega antes que su cobro, o mientras este aun no confirma.
 *
 * <h2>El defecto</h2>
 *
 * <p>La anulacion buscaba que deshacer <b>por el numero del papel</b> —{@code "RECIBO " + numero}—
 * y no por el pago que nombra. Si el cobro todavia no estaba en el libro, no encontraba nada, lo
 * daba por terminado —{@code RECHAZADO}, que no se reintenta solo— y contestaba 201, que la caja
 * lee como «entregado». El cobro llegaba despues, <b>nadie comprobaba que ya lo hubieran
 * anulado</b>, y se imputaba: deuda extinguida con el dinero ya devuelto al contribuyente.
 *
 * <h2>La siembra que distingue</h2>
 *
 * <p>Dos recibos del MISMO contribuyente sobre dos obligaciones DISTINTAS —el predial del predio X
 * por 500,00 y el del predio Y por 180,00—, y el de 180,00 cobrado de antemano. Con una sola
 * obligacion, «se reverso algo» y «se reverso lo que habia que reversar» se leen igual: aqui la de
 * 500,00 tiene que volver a estar viva y la de 180,00 tiene que seguir en cero. Cada prueba usa sus
 * propios predios, asi que el orden en que JUnit las corra no cambia nada.
 *
 * <p>Contra PostgreSQL de verdad y como {@code kamayuk_app}, por lo mismo que {@code
 * PagoInyectadoDosVecesTest}: la carrera es de dos transacciones en el motor —una que todavia no
 * confirmo y otra que no la puede ver en {@code READ COMMITTED}—, y eso no existe en un doble.
 */
@DisplayName("#428 — La anulacion que llega antes que su cobro")
class LaAnulacionQueLlegaAntesQueSuCobroTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-03-16T14:00:00Z"), ZoneOffset.UTC);

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final String TRIBUTO = "PREDIAL";

    /** Lo que la caja manda al anular. La anulacion solo se admite el mismo dia del cobro. */
    private static final String MOTIVO_DE_LA_ANULACION = "ERROR EN EL IMPORTE COBRADO";

    /** Como se llama la excepcion de «todavia no», dicha como la devuelve {@link #intentar}. */
    private static final String TODAVIA_NO = "AnulacionAntesQueSuCobro";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long contribuyente;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static RecibirPago recibir;
    private static RegistrarAsiento registrarAsiento;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260102", "Municipalidad de la anulacion adelantada");

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
        // Los tres objetos con su proxy, igual que en produccion: ver `PagoInyectadoDosVecesTest`.
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
        fijarContextoDelPublicador();
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName(
            "orden invertido, secuencial: la anulacion sale «todavia no» sin dejar fila, y"
                    + " reintentada tras el cobro reversa el de 500,00 y no el de 180,00")
    void ordenInvertidoSecuencial() {
        long predioQueSeAnula = 101L;
        long predioQueNo = 102L;
        cargarDeuda(predioQueSeAnula, "S-500", "500.00");
        cargarDeuda(predioQueNo, "S-180", "180.00");
        assertThat(estadoDe(recibir(pagoDe(UUID.randomUUID(), "S-180", predioQueNo, "180.00"))))
                .isEqualTo("APLICADO");

        UUID cobro = UUID.randomUUID();
        UUID anulacion = UUID.randomUUID();

        assertThat(intentar(() -> recibir(anulacionDe(anulacion, cobro, "S-500", "500.00"))))
                .as(
                        "E2 llega antes que E1: el cobro que nombra no esta en el buzon. Eso es"
                                + " «todavia no», y no «nunca»: si queda RECHAZADO con 201 la caja"
                                + " lo marca ENTREGADO y no lo vuelve a mandar")
                .isEqualTo(TODAVIA_NO);
        assertThat(filasDeBuzonCon(anulacion))
                .as(
                        "y la transaccion se deshace ENTERA: ni la fila de la anulacion queda, o el"
                                + " reintento de la caja recibiria 409 «ya lo tengo»")
                .isZero();

        assertThat(estadoDe(recibir(pagoDe(cobro, "S-500", predioQueSeAnula, "500.00"))))
                .as("E1 llega despues y se imputa: todavia no hay ninguna anulacion registrada")
                .isEqualTo("APLICADO");
        assertThat(deudaViva(predioQueSeAnula)).isEqualByComparingTo("0.00");

        PagoRecibido anulada = recibir(anulacionDe(anulacion, cobro, "S-500", "500.00"));
        assertThat(anulada.estado())
                .as("la caja reintenta E2, y ahora el cobro esta APLICADO: se reversa")
                .isEqualTo(EstadoDelPagoRecibido.APLICADO);
        assertThat(anulada.asientos()).isPositive();

        assertThat(deudaViva(predioQueSeAnula))
                .as(
                        "la deuda de 500,00 vuelve a estar viva: la caja devolvio el dinero, y el"
                                + " libro no puede seguir diciendo que se pago")
                .isEqualByComparingTo("500.00");
        assertThat(deudaViva(predioQueNo))
                .as("y la de 180,00, que es de otro recibo, sigue pagada")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName(
            "orden invertido, concurrente: E1 espera un candado sin confirmar, E2 no queda"
                    + " RECHAZADO, y al soltarlo los dos terminan APLICADOS")
    void ordenInvertidoConcurrente() throws Exception {
        long predioQueSeAnula = 201L;
        long predioQueNo = 202L;
        cargarDeuda(predioQueSeAnula, "C-500", "500.00");
        cargarDeuda(predioQueNo, "C-180", "180.00");
        assertThat(estadoDe(recibir(pagoDe(UUID.randomUUID(), "C-180", predioQueNo, "180.00"))))
                .isEqualTo("APLICADO");

        UUID cobro = UUID.randomUUID();
        UUID anulacion = UUID.randomUUID();
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        Connection candado = base.conexion(BaseDeDatosDePrueba.APP);
        try {
            // Hilo 1: otra transaccion sostiene el `FOR UPDATE` de la obligacion de 500,00 —la
            // espera de candado de un dia de vencimiento, que es uno de los dos disparadores que
            // el issue midio—.
            ContextoDeTenant.fijar(candado, municipalidad);
            assertThat(bloquearLaObligacion(candado, predioQueSeAnula))
                    .as("el candado tiene que caer sobre alguna fila, o la prueba no espera nada")
                    .isPositive();
            int quienSostiene = pidDe(candado);

            // Hilo 2: E1 entra, inserta su fila EN_TRANSITO y se queda esperando el candado.
            Future<String> elCobro =
                    hilos.submit(
                            () ->
                                    enElHiloDelPublicador(
                                            () ->
                                                    intentar(
                                                            () ->
                                                                    recibir(
                                                                            pagoDe(
                                                                                    cobro,
                                                                                    "C-500",
                                                                                    predioQueSeAnula,
                                                                                    "500.00")))));
            esperarQueAlguienEspereA(quienSostiene);

            // Hilo 3: E2, con E1 todavia sin confirmar.
            String laAnulacion =
                    hilos.submit(
                                    () ->
                                            enElHiloDelPublicador(
                                                    () ->
                                                            intentar(
                                                                    () ->
                                                                            recibir(
                                                                                    anulacionDe(
                                                                                            anulacion,
                                                                                            cobro,
                                                                                            "C-500",
                                                                                            "500.00")))))
                            .get(30, TimeUnit.SECONDS);
            assertThat(laAnulacion)
                    .as(
                            "E1 no confirmo todavia, asi que E2 no lo ve: eso es «todavia no», y"
                                    + " quedar RECHAZADO aqui es la deuda extinguida sin dinero")
                    .isEqualTo(TODAVIA_NO);
            assertThat(filasDeBuzonCon(anulacion)).isZero();

            // Se suelta el candado.
            candado.rollback();
            assertThat(elCobro.get(30, TimeUnit.SECONDS)).isEqualTo("APLICADO");
        } finally {
            candado.rollback();
            candado.close();
            hilos.shutdownNow();
        }

        assertThat(estadoDe(recibir(anulacionDe(anulacion, cobro, "C-500", "500.00"))))
                .as("la caja reintenta E2 en la vuelta siguiente, y ahora si hay que reversar")
                .isEqualTo("APLICADO");
        assertThat(deudaViva(predioQueSeAnula)).isEqualByComparingTo("500.00");
        assertThat(deudaViva(predioQueNo)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName(
            "fila heredada: con una anulacion RECHAZADA ya en el buzon, el cobro se rechaza"
                    + " «anulado antes de imputarse» y no toca el libro")
    void filaHeredada() throws SQLException {
        long predioQueSeAnula = 301L;
        long predioQueNo = 302L;
        cargarDeuda(predioQueSeAnula, "H-500", "500.00");
        cargarDeuda(predioQueNo, "H-180", "180.00");
        assertThat(estadoDe(recibir(pagoDe(UUID.randomUUID(), "H-180", predioQueNo, "180.00"))))
                .isEqualTo("APLICADO");

        UUID cobro = UUID.randomUUID();
        // A mano y por SQL, sin pasar por el codigo: es la fila que el codigo de antes de #428
        // dejaba —la anulacion RECHAZADA con 201— y la que ya puede haber en una base de verdad.
        insertarAnulacionRechazada(UUID.randomUUID(), cobro, "H-500");

        PagoRecibido cobrado = recibir(pagoDe(cobro, "H-500", predioQueSeAnula, "500.00"));

        assertThat(cobrado.estado())
                .as(
                        "la caja ya devolvio este dinero: imputarlo extinguiria 500,00 de deuda con"
                                + " un recibo anulado")
                .isEqualTo(EstadoDelPagoRecibido.RECHAZADO);
        assertThat(cobrado.motivo()).contains("anulado antes de imputarse");
        assertThat(cobrado.asientos()).isZero();
        assertThat(asientosCon("RECIBO 001-H-500")).as("ni un asiento del cobro").isZero();
        assertThat(deudaViva(predioQueSeAnula)).isEqualByComparingTo("500.00");
        assertThat(deudaViva(predioQueNo)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName(
            "y si el cobro que nombra fue RECHAZADO, la anulacion no espera: no hay nada que"
                    + " deshacer, y se rechaza diciendolo")
    void siElCobroFueRechazadoNoHayNadaQueEsperar() {
        long predioSinDeuda = 401L;
        UUID cobro = UUID.randomUUID();
        // Sin deuda cargada en ese predio: el cobro no tiene contra que imputarse.
        assertThat(estadoDe(recibir(pagoDe(cobro, "R-90", predioSinDeuda, "90.00"))))
                .isEqualTo("RECHAZADO");

        UUID anulacion = UUID.randomUUID();
        PagoRecibido anulada = recibir(anulacionDe(anulacion, cobro, "R-90", "90.00"));

        assertThat(anulada.estado())
                .as(
                        "«todavia no» aqui seria para siempre: un 503 que la caja reintentaria hasta"
                                + " darlo por muerto, por un cobro que nunca toco el libro")
                .isEqualTo(EstadoDelPagoRecibido.RECHAZADO);
        assertThat(anulada.motivo())
                .as("y el motivo dice por que: el cobro que anula fue rechazado")
                .contains(cobro.toString())
                .contains("RECHAZADO");
        assertThat(asientosCon("ANULACION RECIBO 001-R-90")).isZero();
    }

    // ------------------------------------------------------------------

    /** Como lo llama {@code PagoController}: sin transaccion abierta. */
    private static PagoRecibido recibir(PagoRecibido pago) {
        return recibir.recibir(pago).pago();
    }

    /**
     * Lo que sale de intentarlo, como una palabra: el estado en que quedo el pago, o «todavia no».
     *
     * <p>Una palabra y no el objeto porque el rojo tiene que leerse solo: «expected
     * AnulacionAntesQueSuCobro but was RECHAZADO» dice el defecto entero. Cualquier otra excepcion
     * sale tal cual: no es un resultado, es un fallo de la prueba.
     */
    private static String intentar(Supplier<PagoRecibido> que) {
        try {
            return que.get().estado().name();
        } catch (RecibirPago.AnulacionAntesQueSuCobro todaviaNo) {
            return todaviaNo.getClass().getSimpleName();
        }
    }

    private static String estadoDe(PagoRecibido pago) {
        return pago.estado().name();
    }

    private static <T> T enElHiloDelPublicador(Supplier<T> que) {
        fijarContextoDelPublicador();
        try {
            return que.get();
        } finally {
            TenantContext.limpiar();
        }
    }

    private static void fijarContextoDelPublicador() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("publicador.caja", null, null));
    }

    private static PagoRecibido pagoDe(UUID pagoId, String sufijo, long predio, String importe) {
        ReferenciaDeObligacion referencia =
                new ReferenciaDeObligacion(TRIBUTO, EJERCICIO, predio, null, HOY);
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
                null,
                null,
                Dinero.de(importe),
                List.of(referencia),
                cuerpo,
                RELOJ.instant());
    }

    private static PagoRecibido anulacionDe(
            UUID pagoId, UUID original, String sufijo, String importe) {
        return PagoRecibido.enTransito(
                pagoId,
                TipoDePagoRecibido.PAGO_ANULADO,
                original,
                "caja",
                "001-" + sufijo,
                contribuyente,
                HOY,
                MOTIVO_DE_LA_ANULACION,
                HOY,
                Dinero.de(importe),
                List.of(),
                cuerpoDeLaAnulacion(pagoId, original, importe),
                RELOJ.instant());
    }

    private static String cuerpoDeLaAnulacion(UUID pagoId, UUID original, String importe) {
        return "{\"pagoId\":\""
                + pagoId
                + "\",\"tipo\":\"PAGO_ANULADO\",\"pagoOriginalId\":\""
                + original
                + "\",\"motivo\":\""
                + MOTIVO_DE_LA_ANULACION
                + "\",\"fecha\":\""
                + HOY
                + "\",\"total\":\""
                + importe
                + "\"}";
    }

    /** La fila que el codigo de antes de #428 dejaba: la anulacion RECHAZADA, con su motivo. */
    private static void insertarAnulacionRechazada(UUID pagoId, UUID original, String sufijo)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO pago_recibido (municipalidad_id, pago_id, tipo,"
                                    + " pago_original_id, sistema_caja, recibo_numero,"
                                    + " contribuyente_id, fecha_pago, motivo_anulacion,"
                                    + " fecha_anulacion, total, cuerpo, estado, asientos,"
                                    + " recibido_en, motivo)"
                                    + " VALUES (?, ?, 'PAGO_ANULADO', ?, 'caja', ?, ?, ?, ?, ?,"
                                    + " 500.00, CAST(? AS jsonb), 'RECHAZADO', 0, ?, ?)")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setObject(2, pagoId);
                sentencia.setObject(3, original);
                sentencia.setString(4, "001-" + sufijo);
                sentencia.setLong(5, contribuyente);
                sentencia.setObject(6, HOY);
                sentencia.setString(7, MOTIVO_DE_LA_ANULACION);
                sentencia.setObject(8, HOY);
                sentencia.setString(9, cuerpoDeLaAnulacion(pagoId, original, "500.00"));
                sentencia.setTimestamp(10, Timestamp.from(RELOJ.instant()));
                sentencia.setString(
                        11,
                        "El documento 'RECIBO 001-"
                                + sufijo
                                + "' no origino ningun asiento reversable: o nunca toco el libro,"
                                + " o sus abonos ya se reversaron");
                assertThat(sentencia.executeUpdate()).isEqualTo(1);
            }
            app.commit();
        }
    }

    /** Un cargo del predial de ese predio, contra el que abonar. */
    private static void cargarDeuda(long predio, String sufijo, String importe) {
        transaccion.execute(
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
                                        predio,
                                        null,
                                        null,
                                        Dinero.de(importe),
                                        HOY.minusMonths(1),
                                        "EMISION " + sufijo),
                                Observacion.de("emision de la prueba de #428 " + sufijo)));
    }

    /**
     * Lo que el LIBRO dice que se debe de esa obligacion: cargos menos abonos.
     *
     * <p>Del libro y no de la proyeccion, que es derivada: si la reversion escribiera sus asientos
     * y la proyeccion se quedara atras, esta cifra lo diria y la otra no.
     */
    private static BigDecimal deudaViva(long predio) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT coalesce(sum(CASE WHEN tipo = 'CARGO' THEN monto::numeric"
                                    + " ELSE -monto::numeric END), 0)"
                                    + " FROM cuenta_corriente_asiento"
                                    + " WHERE contribuyente_id = ? AND predio_id = ?")) {
                sentencia.setLong(1, contribuyente);
                sentencia.setLong(2, predio);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    return fila.getBigDecimal(1);
                }
            }
        } catch (SQLException noSePudo) {
            throw new IllegalStateException("No se pudo leer la deuda", noSePudo);
        }
    }

    private static int asientosCon(String documento) {
        return contar(
                "SELECT count(*) FROM cuenta_corriente_asiento WHERE documento_origen = ?",
                documento);
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

    /** El mismo {@code FOR UPDATE} que {@code SaldoRepositoryJdbc.bloquear}, desde fuera. */
    private static int bloquearLaObligacion(Connection conexion, long predio) throws SQLException {
        try (PreparedStatement sentencia =
                conexion.prepareStatement(
                        "SELECT id FROM saldo_proyectado WHERE contribuyente_id = ?"
                                + " AND tributo = ? AND predio_id = ? FOR UPDATE")) {
            sentencia.setLong(1, contribuyente);
            sentencia.setString(2, TRIBUTO);
            sentencia.setLong(3, predio);
            int filas = 0;
            try (ResultSet fila = sentencia.executeQuery()) {
                while (fila.next()) {
                    filas++;
                }
            }
            return filas;
        }
    }

    private static int pidDe(Connection conexion) throws SQLException {
        try (PreparedStatement sentencia = conexion.prepareStatement("SELECT pg_backend_pid()");
                ResultSet fila = sentencia.executeQuery()) {
            fila.next();
            return fila.getInt(1);
        }
    }

    /**
     * Espera a que otra sesion este bloqueada por la que sostiene el candado.
     *
     * <p>Se pregunta al motor —{@code pg_blocking_pids}— y no se duerme un rato: un {@code sleep}
     * que casi siempre basta hace una prueba que casi siempre mide lo que dice.
     */
    private static void esperarQueAlguienEspereA(int quienSostiene) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT count(*) FROM pg_stat_activity"
                                        + " WHERE ? = ANY (pg_blocking_pids(pid))")) {
            sentencia.setInt(1, quienSostiene);
            while (System.nanoTime() < limite) {
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    if (fila.getInt(1) > 0) {
                        return;
                    }
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        throw new AssertionError(
                "El cobro no llego a esperar el candado en 20 s: la prueba no estaria midiendo la"
                        + " ventana en que E1 todavia no confirmo");
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
                                    + " VALUES (?, 'C-00428', 'NATURAL', 'DNI', '70428428',"
                                    + "         'MENGANO DE TAL', true, 'prueba') RETURNING id",
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
