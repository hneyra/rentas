package kamayuk.rentas.cuentacorriente.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Un alta o una baja de deuda, y los asientos que produce (#24, RF-043, RF-044). */
@DisplayName("#24 — Movimiento de deuda")
class MovimientoDeDeudaTest {

    private static final ClaveDeSaldo OBLIGACION =
            new ClaveDeSaldo(1L, "PREDIAL", new Ejercicio(2026), 1, null, null);
    private static final LocalDate FECHA = LocalDate.of(2026, 5, 10);

    @Test
    @DisplayName("un alta produce CARGOS, uno por cada parte con importe")
    void unAltaProduceCargos() {
        MovimientoDeDeuda alta =
                movimiento(
                        SentidoDelMovimiento.ALTA,
                        Dinero.de(1000),
                        Dinero.CERO,
                        Dinero.de(50),
                        Dinero.CERO);

        assertThat(alta.enAsientos())
                .hasSize(2)
                .allSatisfy(asiento -> assertThat(asiento.tipo()).isEqualTo(TipoAsiento.CARGO))
                .extracting(Asiento::concepto)
                .containsExactly(Concepto.INSOLUTO, Concepto.INTERES);
    }

    @Test
    @DisplayName("una baja produce ABONOS")
    void unaBajaProduceAbonos() {
        MovimientoDeDeuda baja =
                movimiento(
                        SentidoDelMovimiento.BAJA,
                        Dinero.de(300),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO);

        assertThat(baja.enAsientos())
                .singleElement()
                .satisfies(
                        asiento -> {
                            assertThat(asiento.tipo()).isEqualTo(TipoAsiento.ABONO);
                            assertThat(asiento.concepto()).isEqualTo(Concepto.INSOLUTO);
                            assertThat(asiento.monto()).isEqualTo(Dinero.de(300));
                        });
    }

    @Test
    @DisplayName("las partes en cero no producen asiento: un asiento de cero no dice nada")
    void lasPartesEnCeroNoProducenAsiento() {
        MovimientoDeDeuda alta =
                movimiento(
                        SentidoDelMovimiento.ALTA,
                        Dinero.de(100),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO);

        assertThat(alta.enAsientos()).hasSize(1);
    }

    @Test
    @DisplayName("sin sustento documental no se construye (RF-043, RF-044)")
    void sinSustentoDocumentalNoSeConstruye() {
        assertThatThrownBy(
                        () ->
                                new MovimientoDeDeuda(
                                        SentidoDelMovimiento.ALTA,
                                        OBLIGACION,
                                        Dinero.de(100),
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        Fase.ORDINARIA,
                                        FECHA,
                                        "   ",
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sustento documental");
    }

    @Test
    @DisplayName("un movimiento sin ningun importe no mueve nada y se rechaza")
    void sinNingunImporteSeRechaza() {
        assertThatThrownBy(
                        () ->
                                movimiento(
                                        SentidoDelMovimiento.ALTA,
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        Dinero.CERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no mueve nada");
    }

    @Test
    @DisplayName("una parte en negativo se rechaza: el signo lo pone el sentido, no el importe")
    void unaParteEnNegativoSeRechaza() {
        assertThatThrownBy(
                        () ->
                                movimiento(
                                        SentidoDelMovimiento.BAJA,
                                        Dinero.de(100).negado(),
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        Dinero.CERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negativo");
    }

    @Test
    @DisplayName("el total es la suma de las cuatro partes")
    void elTotalEsLaSumaDeLasCuatroPartes() {
        MovimientoDeDeuda alta =
                movimiento(
                        SentidoDelMovimiento.ALTA,
                        Dinero.de("100.50"),
                        Dinero.de("10.25"),
                        Dinero.de("5.15"),
                        Dinero.de("2.10"));

        assertThat(alta.total()).isEqualTo(Dinero.de("118.00"));
    }

    @Test
    @DisplayName("los asientos heredan la obligacion entera: tributo, ejercicio, cuota y unidad")
    void losAsientosHeredanLaObligacion() {
        ClaveDeSaldo conPredio =
                new ClaveDeSaldo(7L, "ARBITRIO", new Ejercicio(2027), 3, 42L, null);
        MovimientoDeDeuda alta =
                new MovimientoDeDeuda(
                        SentidoDelMovimiento.ALTA,
                        conPredio,
                        Dinero.de(80),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO,
                        Fase.COACTIVA,
                        FECHA,
                        "RES-2026-0001",
                        "EXP-9");

        assertThat(alta.enAsientos())
                .singleElement()
                .satisfies(
                        asiento -> {
                            assertThat(asiento.contribuyenteId()).isEqualTo(7L);
                            assertThat(asiento.tributo()).isEqualTo("ARBITRIO");
                            assertThat(asiento.ejercicio()).isEqualTo(new Ejercicio(2027));
                            assertThat(asiento.periodo()).isEqualTo(3);
                            assertThat(asiento.predioId()).isEqualTo(42L);
                            assertThat(asiento.fase()).isEqualTo(Fase.COACTIVA);
                            assertThat(asiento.referenciaExterna()).isEqualTo("EXP-9");
                        });
    }

    private static MovimientoDeDeuda movimiento(
            SentidoDelMovimiento sentido,
            Dinero insoluto,
            Dinero reajuste,
            Dinero interes,
            Dinero gasto) {
        return new MovimientoDeDeuda(
                sentido,
                OBLIGACION,
                insoluto,
                reajuste,
                interes,
                gasto,
                Fase.ORDINARIA,
                FECHA,
                "RES-2026-0001",
                null,
                // Una baja declara su causal y un alta no la lleva (#684): las dos direcciones
                // las comprueba `LaCausalDelActo`, aqui solo se compone el movimiento valido.
                sentido == SentidoDelMovimiento.BAJA ? CausalDeBaja.ERROR_MATERIAL : null);
    }
}
