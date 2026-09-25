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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
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
import kamayuk.rentas.tesoreria.dobles.CajaDeOrdenesDeMentira;
import kamayuk.rentas.tesoreria.infraestructura.PagoRecibidoRepositoryJdbc;
import org.jspecify.annotations.Nullable;
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
 * #431 — <b>un recibo que cobra ordenes de dos deudores abona a cada uno lo suyo</b>.
 *
 * <h2>El escenario</h2>
 *
 * <p>Una hija paga en una sola cobranza el predial 2026 de su madre A —una orden de 300,00 por el
 * predio 10— y el suyo, B —una orden de 200,00 por el predio 20—. Son dos emisiones, porque la
 * peticion lleva un solo contribuyente, y un solo recibo de 500,00: la caja toma el pagador de la
 * <b>primera</b> orden marcada y publica un solo {@code pagador.idExterno}.
 *
 * <p>Hasta #431 la referencia no llevaba al deudor —«una obligacion identificada por su deudor
 * haria imposible que un tercero pague la deuda de otro»—, asi que al volver el pago el deudor
 * salia del pagador: el libro buscaba las dos obligaciones bajo A, la del predio 20 no tenia saldo,
 * 300,00 no cuadraba con 500,00 y el pago quedaba {@code RECHAZADO}. Dinero en caja, y A y B
 * debiendolo todo.
 *
 * <h2>Por que la siembra tiene DOS deudores, y la de al lado no sirve</h2>
 *
 * <p>La muestra uniforme —dos lineas del mismo contribuyente— pasa con la implementacion de antes
 * de #431 y no distingue nada: el pagador es el deudor de todas las lineas. Lo que el defecto
 * necesita es que el deudor de una linea <b>no</b> sea el pagador, y eso solo lo da una siembra con
 * dos titulares distintos.
 *
 * <p>Contra PostgreSQL de verdad y como {@code kamayuk_app}, entrando por {@link RecibirPago}: lo
 * que se afirma es a nombre de quien quedan las FILAS del libro, y las ordenes se emiten por el
 * camino de produccion ({@link EmitirOrdenDeCobro}), no con una referencia escrita a mano.
 */
@DisplayName("#431 — un recibo con ordenes de dos deudores abona a cada uno lo suyo")
class ReciboConDosDeudoresTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-23T14:00:00Z"), ZoneOffset.UTC);

    private static final LocalDate HOY = LocalDate.of(2026, 9, 23);

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final String TRIBUTO = "PREDIAL";

    private static final long PREDIO_DE_A = 10L;

    private static final long PREDIO_DE_B = 20L;

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static AsientoRepositoryJdbc asientos;
    private static RegistrarAsiento registrarAsiento;
    private static RecibirPago recibir;
    private static EmitirOrdenDeCobro emisor;
    private static ConsultaDeDeudaPublica libro;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260431", "Municipalidad de los dos deudores");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        registrarAsiento = envolver(new RegistrarAsiento(asientos, saldos, auditoria, RELOJ));
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);
        RegistroDeAbonos abonos =
                envolver(
                        new RegistroDeAbonosCuentaCorriente(
                                asientos, saldos, registrarAsiento, calculo, redondeo));

        PagoRecibidoRepository buzon = new PagoRecibidoRepositoryJdbc(jdbc);
        // Los tres objetos con su proxy, como en `PagoInyectadoDosVecesTest`: `RecibirPago` no
        // abre transaccion, `ImputacionDelPago` abre la suya y `RechazoDelPago` una NUEVA.
        recibir =
                new RecibirPago(
                        envolver(new ImputacionDelPago(buzon, abonos, RELOJ)),
                        envolver(new RechazoDelPago(buzon)));

        libro =
                envolver(
                        new ConsultaDeDeudaCuentaCorriente(
                                new ConsultarDeuda(asientos, saldos, calculo, redondeo, RELOJ)));
        emisor = new EmitirOrdenDeCobro(libro, new CajaDeOrdenesDeMentira());
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
    @DisplayName("Deudores mezclados — A debe el predio 10 y B el predio 20")
    class DeudoresMezclados {

        @Test
        @DisplayName(
                "la orden de A va primero: el pago queda APLICADO y cada abono es de su deudor")
        void conLaOrdenDeAPrimero() {
            long madre = sembrarContribuyente();
            long hija = sembrarContribuyente();
            cargarDeuda(madre, PREDIO_DE_A, "300.00");
            cargarDeuda(hija, PREDIO_DE_B, "200.00");

            ReferenciaDeObligacion deA = emitir(madre, PREDIO_DE_A);
            ReferenciaDeObligacion deB = emitir(hija, PREDIO_DE_B);
            String documento = "RECIBO 001-431-A";

            // La caja toma el pagador de la PRIMERA orden marcada: A.
            PagoRecibido pago =
                    recibir.recibir(pago(madre, "500.00", documento, List.of(deA, deB))).pago();

            assertThat(pago.estado())
                    .as(
                            "hasta #431 el libro buscaba las dos obligaciones bajo el pagador: la"
                                    + " del predio de B no tenia saldo bajo A, 300,00 no cuadraba"
                                    + " con 500,00 y el pago quedaba RECHAZADO. Motivo: %s",
                            pago.motivo())
                    .isEqualTo(EstadoDelPagoRecibido.APLICADO);
            assertThat(abonosDe(documento))
                    .as("cada asiento a nombre de su deudor, y no los dos a nombre de quien pago")
                    .containsExactlyInAnyOrder(
                            new Abono(madre, PREDIO_DE_A, Dinero.de("300.00")),
                            new Abono(hija, PREDIO_DE_B, Dinero.de("200.00")));
            assertThat(deudaDe(madre, PREDIO_DE_A)).isEqualTo(Dinero.CERO);
            assertThat(deudaDe(hija, PREDIO_DE_B)).isEqualTo(Dinero.CERO);
        }

        @Test
        @DisplayName("y con la de B primero, lo mismo: el pagador no decide de quien es la deuda")
        void conLaOrdenDeBPrimero() {
            long madre = sembrarContribuyente();
            long hija = sembrarContribuyente();
            cargarDeuda(madre, PREDIO_DE_A, "300.00");
            cargarDeuda(hija, PREDIO_DE_B, "200.00");

            ReferenciaDeObligacion deA = emitir(madre, PREDIO_DE_A);
            ReferenciaDeObligacion deB = emitir(hija, PREDIO_DE_B);
            String documento = "RECIBO 001-431-B";

            PagoRecibido pago =
                    recibir.recibir(pago(hija, "500.00", documento, List.of(deB, deA))).pago();

            assertThat(pago.estado())
                    .as(
                            "con B de pagador el rechazo era el mismo: 200,00 contra 500,00. %s",
                            pago.motivo())
                    .isEqualTo(EstadoDelPagoRecibido.APLICADO);
            assertThat(abonosDe(documento))
                    .containsExactlyInAnyOrder(
                            new Abono(madre, PREDIO_DE_A, Dinero.de("300.00")),
                            new Abono(hija, PREDIO_DE_B, Dinero.de("200.00")));
        }
    }

    @Nested
    @DisplayName("Condominos — A y B deben el MISMO predio 10, con importes distintos")
    class Condominos {

        @Test
        @DisplayName(
                "emitiendo el mismo dia reciben dos ordenes distintas, y un solo recibo abona a"
                        + " cada uno lo suyo")
        void dosOrdenesDistintasYCadaAbonoASuDeudor() {
            long a = sembrarContribuyente();
            long b = sembrarContribuyente();
            cargarDeuda(a, PREDIO_DE_A, "300.00");
            cargarDeuda(b, PREDIO_DE_A, "200.00");

            EmitirOrdenDeCobro.Emitida deA = emitirOrden(a, PREDIO_DE_A);
            EmitirOrdenDeCobro.Emitida deB = emitirOrden(b, PREDIO_DE_A);

            assertThat(deB.ordenId())
                    .as(
                            "con la misma referencia —PREDIAL|2026|10||fecha— la caja devolvia a B"
                                    + " la orden de A (nueva = false), con el importe de A y A como"
                                    + " pagador: pagarla extinguia la deuda del otro")
                    .isNotEqualTo(deA.ordenId());
            assertThat(deB.nueva()).isTrue();

            String documento = "RECIBO 001-431-C";
            PagoRecibido pago =
                    recibir.recibir(
                                    pago(
                                            a,
                                            "500.00",
                                            documento,
                                            List.of(deA.referencia(), deB.referencia())))
                            .pago();

            assertThat(pago.estado())
                    .as("motivo: %s", pago.motivo())
                    .isEqualTo(EstadoDelPagoRecibido.APLICADO);
            assertThat(abonosDe(documento))
                    .containsExactlyInAnyOrder(
                            new Abono(a, PREDIO_DE_A, Dinero.de("300.00")),
                            new Abono(b, PREDIO_DE_A, Dinero.de("200.00")));
        }
    }

    @Nested
    @DisplayName("Y un pago en vuelo con la referencia de antes de #431 se sigue leyendo")
    class LaReferenciaDeCincoPartes {

        @Test
        @DisplayName("sin deudor en la referencia, el deudor sigue saliendo del pagador")
        void elDeudorSaleDelPagador() {
            long titular = sembrarContribuyente();
            cargarDeuda(titular, PREDIO_DE_A, "300.00");
            String documento = "RECIBO 001-431-D";

            // Tal como la componia `texto()` antes de #431: cinco partes, sin deudor. Cambiar el
            // formato no puede dejar sin leer los pagos que ya estaban en camino.
            ReferenciaDeObligacion enVuelo =
                    ReferenciaDeObligacion.leer("PREDIAL|2026|" + PREDIO_DE_A + "||" + HOY);

            PagoRecibido pago =
                    recibir.recibir(pago(titular, "300.00", documento, List.of(enVuelo))).pago();

            assertThat(pago.estado())
                    .as("motivo: %s", pago.motivo())
                    .isEqualTo(EstadoDelPagoRecibido.APLICADO);
            assertThat(abonosDe(documento))
                    .containsExactly(new Abono(titular, PREDIO_DE_A, Dinero.de("300.00")));
        }
    }

    @Nested
    @DisplayName("Con el pagador ANONIMO —la caja no dice a que contribuyente le cobro—")
    class ElPagadorAnonimo {

        @Test
        @DisplayName(
                "si las referencias traen al deudor, el pago se imputa a cada uno: el pagador no"
                        + " hace falta")
        void conSeisPartesSeImputaACadaDeudor() {
            long madre = sembrarContribuyente();
            long hija = sembrarContribuyente();
            cargarDeuda(madre, PREDIO_DE_A, "300.00");
            cargarDeuda(hija, PREDIO_DE_B, "200.00");

            ReferenciaDeObligacion deA = emitir(madre, PREDIO_DE_A);
            ReferenciaDeObligacion deB = emitir(hija, PREDIO_DE_B);
            String documento = "RECIBO 001-431-E";

            PagoRecibido pago =
                    recibir.recibir(pago(null, "500.00", documento, List.of(deA, deB))).pago();

            assertThat(pago.estado())
                    .as(
                            "hasta #431 un pago sin pagador quedaba RECHAZADO siempre, porque el"
                                    + " pagador era el deudor de todas las lineas. Desde #431 el"
                                    + " deudor viaja en cada referencia, y el pagador solo decide"
                                    + " en una de cinco partes. Motivo: %s",
                            pago.motivo())
                    .isEqualTo(EstadoDelPagoRecibido.APLICADO);
            assertThat(abonosDe(documento))
                    .containsExactlyInAnyOrder(
                            new Abono(madre, PREDIO_DE_A, Dinero.de("300.00")),
                            new Abono(hija, PREDIO_DE_B, Dinero.de("200.00")));
        }

        @Test
        @DisplayName(
                "con una referencia de cinco partes no hay a nombre de quien asentar: RECHAZADO,"
                        + " y el libro intacto")
        void conCincoPartesQuedaRechazado() {
            long titular = sembrarContribuyente();
            cargarDeuda(titular, PREDIO_DE_A, "300.00");
            String documento = "RECIBO 001-431-F";

            // La referencia de antes de #431: sin deudor. Y sin pagador, el respaldo tampoco lo da.
            ReferenciaDeObligacion enVuelo =
                    ReferenciaDeObligacion.leer("PREDIAL|2026|" + PREDIO_DE_A + "||" + HOY);

            PagoRecibido pago =
                    recibir.recibir(pago(null, "300.00", documento, List.of(enVuelo))).pago();

            assertThat(pago.estado())
                    .as(
                            "sin deudor en la referencia y sin pagador no hay de quien sea el"
                                    + " abono: SinDeudaQueAbonar, que RecibirPago deja RECHAZADO"
                                    + " con su motivo. Motivo: %s",
                            pago.motivo())
                    .isEqualTo(EstadoDelPagoRecibido.RECHAZADO);
            assertThat(pago.motivo()).contains("no lleva deudor").contains("pagador anonimo");
            assertThat(abonosDe(documento)).as("el libro no se toca").isEmpty();
            assertThat(deudaDe(titular, PREDIO_DE_A)).isEqualTo(Dinero.de("300.00"));
        }
    }

    // ------------------------------------------------------------------

    /** Un abono del libro, reducido a lo que el issue pregunta: de quien, de que, y cuanto. */
    private record Abono(long contribuyenteId, long predioId, Dinero monto) {}

    private static ReferenciaDeObligacion emitir(long contribuyenteId, long predio) {
        return emitirOrden(contribuyenteId, predio).referencia();
    }

    /** Por el camino de produccion: el importe y la referencia los compone la emision. */
    private static EmitirOrdenDeCobro.Emitida emitirOrden(long contribuyenteId, long predio) {
        EmitirOrdenDeCobro.Emision emision =
                emisor.emitir(
                        new EmitirOrdenDeCobro.Peticion(
                                contribuyenteId,
                                List.of(
                                        new SeleccionDeObligacion(
                                                TRIBUTO, EJERCICIO, predio, null)),
                                HOY,
                                null,
                                null,
                                null),
                        Observacion.de("emision de la prueba de #431"));
        return emision.emitidas().get(0);
    }

    /**
     * El pago tal como la caja lo publica: UN pagador —el de la primera orden— y una linea por
     * orden. El buzon relee las referencias del cuerpo congelado, asi que van en el cuerpo.
     */
    private static PagoRecibido pago(
            @Nullable Long pagador,
            String total,
            String documento,
            List<ReferenciaDeObligacion> referencias) {
        UUID pagoId = UUID.randomUUID();
        String recibo = documento.substring("RECIBO ".length());
        StringBuilder ordenes = new StringBuilder();
        for (ReferenciaDeObligacion referencia : referencias) {
            if (ordenes.length() > 0) {
                ordenes.append(',');
            }
            ordenes.append("{\"referenciaExterna\":\"").append(referencia.texto()).append("\"}");
        }
        String cuerpo =
                "{\"pagoId\":\""
                        + pagoId
                        + "\",\"tipo\":\"PAGO_REGISTRADO\",\"sistemaOrigen\":\"rentas\","
                        + "\"total\":\""
                        + total
                        + "\",\"pagador\":{\"idExterno\":"
                        + pagador
                        + "},\"ordenes\":["
                        + ordenes
                        + "]}";
        return PagoRecibido.enTransito(
                pagoId,
                TipoDePagoRecibido.PAGO_REGISTRADO,
                null,
                "caja",
                recibo,
                pagador,
                HOY,
                null,
                null,
                Dinero.de(total),
                referencias,
                cuerpo,
                RELOJ.instant());
    }

    private static void cargarDeuda(long contribuyenteId, long predio, String importe) {
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        contribuyenteId,
                                        TRIBUTO,
                                        Concepto.INSOLUTO,
                                        TipoAsiento.CARGO,
                                        Fase.ORDINARIA,
                                        null,
                                        predio,
                                        null,
                                        null,
                                        Dinero.de(importe),
                                        HOY.withDayOfYear(1),
                                        "EMISION 431-" + contribuyenteId),
                                Observacion.de("emision de la prueba de #431")));
    }

    /** Los abonos que ese recibo dejo en el libro, con su deudor. */
    private static List<Abono> abonosDe(String documento) {
        List<Asiento> delDocumento = enTransaccion(() -> asientos.porDocumentoOrigen(documento));
        List<Abono> abonos = new ArrayList<>();
        for (Asiento asiento : delDocumento) {
            if (asiento.tipo() == TipoAsiento.ABONO) {
                abonos.add(
                        new Abono(
                                asiento.contribuyenteId(),
                                java.util.Objects.requireNonNull(asiento.predioId()),
                                asiento.monto()));
            }
        }
        return abonos;
    }

    /** Lo que ese deudor sigue debiendo de ese predio, por el mismo puerto que valora la orden. */
    private static Dinero deudaDe(long contribuyenteId, long predio) {
        return libro.todasDe(contribuyenteId, HOY).stream()
                .filter(o -> o.predioId() != null && predio == o.predioId())
                .map(ObligacionPublica::total)
                .reduce(Dinero.CERO, Dinero::mas);
    }

    // ------------------------------------------------------------------

    private static <T> T enTransaccion(java.util.function.Supplier<T> que) {
        return java.util.Objects.requireNonNull(transaccion.execute(estado -> que.get()));
    }

    private static long sembrarContribuyente() {
        int n = SIGUIENTE.getAndIncrement();
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            long id =
                    insertar(
                            app,
                            "INSERT INTO contribuyente (municipalidad_id,"
                                    + " codigo_contribuyente, tipo_persona, tipo_documento,"
                                    + " numero_documento, nombre_razon_social, activo,"
                                    + " usuario_registro)"
                                    + " VALUES (?, ?, 'NATURAL', 'DNI', ?, ?, true, 'prueba')"
                                    + " RETURNING id",
                            municipalidad,
                            "C-431" + String.format("%03d", n),
                            String.format("70431%03d", n),
                            "DEUDOR " + n + " DE LA PRUEBA");
            app.commit();
            return id;
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo sembrar el contribuyente " + n, e);
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
