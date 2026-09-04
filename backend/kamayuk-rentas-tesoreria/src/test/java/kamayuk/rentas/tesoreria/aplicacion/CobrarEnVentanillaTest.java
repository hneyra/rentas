package kamayuk.rentas.tesoreria.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.dobles.CajasEnMemoria;
import kamayuk.rentas.tesoreria.dobles.LibroDeMentira;
import kamayuk.rentas.tesoreria.dobles.RecibosEnMemoria;
import kamayuk.rentas.tesoreria.dobles.SinConvenios;
import kamayuk.rentas.tesoreria.dobles.TasasEnMemoria;
import kamayuk.rentas.tesoreria.dobles.TurnosEnMemoria;
import kamayuk.rentas.tesoreria.dominio.Caja;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.LineaDeTasaPedida;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.tesoreria.dominio.Tasa;
import kamayuk.rentas.tesoreria.dominio.TipoDePago;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #33 — Las decisiones de la caja, con dobles: sin base de datos y sin reloj del sistema.
 *
 * <p>Lo que <b>no</b> se prueba aqui, y por eso existe {@code CajaJdbcTest}: la atomicidad, el
 * bloqueo del turno, el {@code REVOKE UPDATE} sobre el recibo y el aislamiento entre
 * municipalidades. Ninguna de esas cuatro cosas se puede demostrar contra un doble; contra un doble
 * solo se demuestra que el codigo hace lo que el doble deja hacer.
 */
@DisplayName("#33 — Caja tributaria y caja de tasas")
class CobrarEnVentanillaTest {

    private static final LocalDate PAGO = LocalDate.of(2026, 3, 15);
    private static final Clock RELOJ =
            Clock.fixed(PAGO.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final Caja CAJA = new Caja(1L, "C-01", "Caja tributaria", "001", null, true);
    private static final Caja CAJA_DE_BAJA = new Caja(2L, "C-02", "Caja vieja", "002", null, false);

    private static final SeleccionDeObligacion PREDIAL_2025 =
            new SeleccionDeObligacion("PREDIAL", new Ejercicio(2025), 55L, null);
    private static final SeleccionDeObligacion PREDIAL_2024 =
            new SeleccionDeObligacion("PREDIAL", new Ejercicio(2024), 55L, null);

    private final CajasEnMemoria cajas = new CajasEnMemoria().con(CAJA).con(CAJA_DE_BAJA);
    private final TurnosEnMemoria turnos = new TurnosEnMemoria();
    private final RecibosEnMemoria recibos = new RecibosEnMemoria();
    private final LibroDeMentira libro = new LibroDeMentira();
    private final TasasEnMemoria tasas = new TasasEnMemoria();

    private final AbrirCaja abrirCaja =
            new AbrirCaja(cajas, turnos, (RegistroDeAuditoria registro) -> {}, RELOJ);
    private final CobrarDeuda cobrarDeuda =
            new CobrarDeuda(
                    abrirCaja,
                    libro,
                    recibos,
                    SinConvenios.formalizador(RELOJ),
                    (RegistroDeAuditoria registro) -> {},
                    RELOJ);
    private final CobrarTasa cobrarTasa =
            new CobrarTasa(abrirCaja, tasas, recibos, (RegistroDeAuditoria registro) -> {}, RELOJ);

    @Nested
    @DisplayName("La apertura del turno")
    class DelTurno {

        @Test
        @DisplayName("dos cobranzas del mismo cajero y dia abren UN turno, no dos")
        void abrirDosVecesNoDuplica() {
            libro.con(PREDIAL_2025, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);
            libro.con(PREDIAL_2024, Dinero.de("80.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

            cobrarDeuda.cobrar(cobranzaDe(List.of(PREDIAL_2025), null), porQue());
            cobrarDeuda.cobrar(cobranzaDe(List.of(PREDIAL_2024), null), porQue());

            assertThat(turnos.cuantos()).isEqualTo(1);
            assertThat(recibos.emitidos()).hasSize(2);
        }

        @Test
        @DisplayName("contra un turno cerrado no se cobra: su arqueo ya se firmo")
        void unTurnoCerradoNoCobra() {
            turnos.conTurnoCerrado(1L, "cajero.prueba", PAGO);
            libro.con(PREDIAL_2025, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

            assertThatThrownBy(
                            () ->
                                    cobrarDeuda.cobrar(
                                            cobranzaDe(List.of(PREDIAL_2025), null), porQue()))
                    .isInstanceOf(AbrirCaja.TurnoCerrado.class)
                    .hasMessageContaining("ya se cerro");
            assertThat(recibos.emitidos()).isEmpty();
        }

        @Test
        @DisplayName("una caja dada de baja no cobra")
        void unaCajaDeBajaNoCobra() {
            libro.con(PREDIAL_2025, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

            assertThatThrownBy(
                            () ->
                                    cobrarDeuda.cobrar(
                                            new CobrarDeuda.Cobranza(
                                                    "C-02",
                                                    "cajero.prueba",
                                                    7L,
                                                    List.of(PREDIAL_2025),
                                                    FormaDePago.EFECTIVO,
                                                    TipoDePago.NORMAL,
                                                    null,
                                                    PAGO,
                                                    null,
                                                    null),
                                            porQue()))
                    .isInstanceOf(AbrirCaja.CajaDeBaja.class);
        }
    }

    @Nested
    @DisplayName("La cobranza")
    class DeLaCobranza {

        @Test
        @DisplayName("el importe sale del libro, no de la peticion: la caja no tiene donde ponerlo")
        void elImporteSaleDelLibro() {
            libro.con(
                    PREDIAL_2025,
                    Dinero.de("100.00"),
                    Dinero.de("5.00"),
                    Dinero.de("12.30"),
                    Dinero.de("2.70"));

            Recibo emitido = cobrarDeuda.cobrar(cobranzaDe(List.of(PREDIAL_2025), null), porQue());

            assertThat(emitido.total()).isEqualTo(Dinero.de("120.00"));
            assertThat(emitido.lineas())
                    .singleElement()
                    .satisfies(
                            linea -> {
                                assertThat(linea.insoluto()).isEqualTo(Dinero.de("100.00"));
                                assertThat(linea.interes()).isEqualTo(Dinero.de("12.30"));
                            });
        }

        @Test
        @DisplayName("el recibo dice a que fecha estaba actualizada la deuda cobrada (RNF-075)")
        void elReciboLlevaLaFechaDeLaDeuda() {
            libro.con(PREDIAL_2025, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

            LocalDate ayer = PAGO.minusDays(1);
            Recibo emitido =
                    cobrarDeuda.cobrar(
                            new CobrarDeuda.Cobranza(
                                    "C-01",
                                    "cajero.prueba",
                                    7L,
                                    List.of(PREDIAL_2025),
                                    FormaDePago.EFECTIVO,
                                    TipoDePago.NORMAL,
                                    null,
                                    ayer,
                                    null,
                                    null),
                            porQue());

            assertThat(emitido.actualizadoA())
                    .as("la fecha del recibo es la de pago con que se releyo la deuda, no hoy")
                    .isEqualTo(ayer);
        }

        @Test
        @DisplayName("cobrar la misma deuda dos veces: la segunda no encuentra nada (409)")
        void cobrarDosVecesNoEncuentraNada() {
            libro.con(PREDIAL_2025, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

            cobrarDeuda.cobrar(cobranzaDe(List.of(PREDIAL_2025), null), porQue());

            assertThatThrownBy(
                            () ->
                                    cobrarDeuda.cobrar(
                                            cobranzaDe(List.of(PREDIAL_2025), null), porQue()))
                    .isInstanceOf(CobrarDeuda.NadaQueCobrar.class);
            assertThat(recibos.emitidos()).hasSize(1);
        }

        @Test
        @DisplayName("reenviar el mismo intento devuelve el recibo de la primera vez")
        void elReenvioDevuelveElMismoRecibo() {
            libro.con(PREDIAL_2025, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

            Recibo primero =
                    cobrarDeuda.cobrar(cobranzaDe(List.of(PREDIAL_2025), "clave-1"), porQue());
            Recibo repetido =
                    cobrarDeuda.cobrar(cobranzaDe(List.of(PREDIAL_2025), "clave-1"), porQue());

            assertThat(repetido.id()).isEqualTo(primero.id());
            assertThat(repetido.numero()).isEqualTo(primero.numero());
            assertThat(recibos.emitidos()).hasSize(1);
        }

        @Test
        @DisplayName("el beneficio declarado se guarda, pero no descuenta un centimo (D-02b)")
        void elBeneficioNoDescuenta() {
            libro.con(PREDIAL_2025, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

            Recibo emitido =
                    cobrarDeuda.cobrar(
                            new CobrarDeuda.Cobranza(
                                    "C-01",
                                    "cajero.prueba",
                                    7L,
                                    List.of(PREDIAL_2025),
                                    FormaDePago.EFECTIVO,
                                    TipoDePago.NORMAL,
                                    "ORD. 012-2026-MPS — 100 % INTERESES",
                                    PAGO,
                                    null,
                                    null),
                            porQue());

            assertThat(emitido.campaniaBeneficio()).contains("ORD. 012-2026-MPS");
            assertThat(emitido.total())
                    .as("el importe es el integro: aplicar el descuento esta bloqueado por D-02b")
                    .isEqualTo(Dinero.de("100.00"));
        }

        @Test
        @DisplayName("los asientos del libro llevan el numero del recibo que los explica")
        void elLibroSabeQueReciboLoOrigino() {
            libro.con(PREDIAL_2025, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

            Recibo emitido = cobrarDeuda.cobrar(cobranzaDe(List.of(PREDIAL_2025), null), porQue());

            assertThat(libro.documentosOrigen())
                    .singleElement()
                    .isEqualTo("RECIBO " + emitido.numero().impreso());
        }

        @Test
        @DisplayName("una modalidad que #33 no escribe se rechaza en vez de cobrarse como normal")
        void unTipoDePagoNoImplementadoSeRechaza() {
            libro.con(PREDIAL_2025, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

            assertThatThrownBy(
                            () ->
                                    cobrarDeuda.cobrar(
                                            new CobrarDeuda.Cobranza(
                                                    "C-01",
                                                    "cajero.prueba",
                                                    7L,
                                                    List.of(PREDIAL_2025),
                                                    FormaDePago.EFECTIVO,
                                                    TipoDePago.A_CUENTA,
                                                    null,
                                                    PAGO,
                                                    null,
                                                    null),
                                            porQue()))
                    .isInstanceOf(CobrarDeuda.TipoDePagoNoImplementado.class);
            assertThat(recibos.emitidos()).isEmpty();
        }

        @Test
        @DisplayName("la misma obligacion marcada dos veces se rechaza: seria cobrarla de mas")
        void laMismaObligacionDosVecesSeRechaza() {
            libro.con(PREDIAL_2025, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

            assertThatThrownBy(
                            () ->
                                    cobrarDeuda.cobrar(
                                            cobranzaDe(List.of(PREDIAL_2025, PREDIAL_2025), null),
                                            porQue()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dos veces");
        }

        @Test
        @DisplayName("sin observacion no se cobra (regla 10)")
        void sinObservacionNoSeCobra() {
            libro.con(PREDIAL_2025, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

            assertThatThrownBy(
                            () -> cobrarDeuda.cobrar(cobranzaDe(List.of(PREDIAL_2025), null), null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("regla 10");
        }
    }

    @Nested
    @DisplayName("La caja de tasas")
    class DeLasTasas {

        @Test
        @DisplayName("el precio sale de la tabla, y cobrar tres cuesta tres veces la tarifa")
        void elPrecioSaleDeLaTabla() {
            tasas.con(tasa("T-001", Dinero.de("12.50"), LocalDate.of(2026, 1, 1), null));

            Recibo emitido =
                    cobrarTasa.cobrar(
                            new CobrarTasa.CobroDeTasas(
                                    "C-01",
                                    "cajero.prueba",
                                    7L,
                                    List.of(new LineaDeTasaPedida("T-001", 3)),
                                    FormaDePago.EFECTIVO,
                                    PAGO,
                                    null),
                            porQue());

            assertThat(emitido.total()).isEqualTo(Dinero.de("37.50"));
            assertThat(emitido.lineas())
                    .singleElement()
                    .satisfies(
                            linea -> {
                                assertThat(linea.cantidad()).isEqualTo(3);
                                assertThat(linea.precioUnitario()).isEqualTo(Dinero.de("12.50"));
                                assertThat(linea.reajuste()).isEqualTo(Dinero.CERO);
                            });
            assertThat(emitido.tipoDePago()).isEqualTo(TipoDePago.TASA);
        }

        @Test
        @DisplayName("cobra la tarifa vigente a la fecha, no la ultima registrada")
        void cobraLaTarifaVigenteALaFecha() {
            LocalDate julio = LocalDate.of(2026, 7, 1);
            tasas.con(
                            tasa(
                                    "T-001",
                                    Dinero.de("12.50"),
                                    LocalDate.of(2026, 1, 1),
                                    julio.minusDays(1)))
                    .con(tasa("T-001", Dinero.de("20.00"), julio, null));

            Recibo enMarzo =
                    cobrarTasa.cobrar(
                            new CobrarTasa.CobroDeTasas(
                                    "C-01",
                                    "cajero.prueba",
                                    7L,
                                    List.of(new LineaDeTasaPedida("T-001", 1)),
                                    FormaDePago.EFECTIVO,
                                    PAGO,
                                    null),
                            porQue());

            assertThat(enMarzo.total())
                    .as("una cobranza de marzo no paga la tarifa que rige desde julio")
                    .isEqualTo(Dinero.de("12.50"));
        }

        @Test
        @DisplayName("un concepto sin tarifa vigente no se cobra")
        void sinTarifaVigenteNoSeCobra() {
            tasas.con(tasa("T-001", Dinero.de("12.50"), LocalDate.of(2026, 7, 1), null));

            assertThatThrownBy(
                            () ->
                                    cobrarTasa.cobrar(
                                            new CobrarTasa.CobroDeTasas(
                                                    "C-01",
                                                    "cajero.prueba",
                                                    7L,
                                                    List.of(new LineaDeTasaPedida("T-001", 1)),
                                                    FormaDePago.EFECTIVO,
                                                    PAGO,
                                                    null),
                                            porQue()))
                    .isInstanceOf(CobrarTasa.TasaSinTarifaVigente.class);
            assertThat(recibos.emitidos()).isEmpty();
        }
    }

    // ------------------------------------------------------------------

    private static CobrarDeuda.Cobranza cobranzaDe(
            List<SeleccionDeObligacion> obligaciones, String clave) {
        return new CobrarDeuda.Cobranza(
                "C-01",
                "cajero.prueba",
                7L,
                obligaciones,
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

    private static Tasa tasa(String codigo, Dinero importe, LocalDate desde, LocalDate hasta) {
        return new Tasa(
                codigo.hashCode() & 0xffffL,
                codigo,
                "Concepto del TUPA de la prueba",
                9L,
                "1.3.1.1.1.1",
                importe,
                desde,
                hasta,
                "TUPA 2026, ordenanza de la prueba");
    }
}
