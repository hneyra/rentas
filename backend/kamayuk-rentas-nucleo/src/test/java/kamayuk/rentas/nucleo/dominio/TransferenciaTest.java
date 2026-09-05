package kamayuk.rentas.nucleo.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code Transferencia} en dominio puro: sin base, sin Spring (#29). */
@DisplayName("#29 — Transferencia de predio y de vehiculo")
class TransferenciaTest {

    private static final Observacion OBSERVACION = Observacion.de("Se registra para la prueba");

    @Test
    @DisplayName("dePredio construye una transferencia de predio, con el porcentaje transferido")
    void dePredioConstruyeUnaTransferenciaDePredio() {
        Transferencia transferencia =
                Transferencia.dePredio(
                        10L,
                        1L,
                        2L,
                        TipoTransferencia.COMPRA_VENTA,
                        LocalDate.of(2026, 3, 1),
                        Dinero.de("150000.00"),
                        Porcentaje.de("40"),
                        true,
                        "Escritura publica N.° 001-2026",
                        OBSERVACION);

        assertThat(transferencia.objeto()).isEqualTo(ObjetoDeTransferencia.PREDIO);
        assertThat(transferencia.predioId()).isEqualTo(10L);
        assertThat(transferencia.vehiculoId()).isNull();
        assertThat(transferencia.tipoTransferencia())
                .as("el tipo se guarda en mayusculas, como los demas catalogos de texto libre")
                .isEqualTo(TipoTransferencia.COMPRA_VENTA);
        assertThat(transferencia.porcentajeTransferido()).isEqualTo(Porcentaje.de("40"));
    }

    @Test
    @DisplayName("deVehiculo siempre transfiere el total: un vehiculo no tiene copropietarios")
    void deVehiculoSiempreEsElTotal() {
        Transferencia transferencia =
                Transferencia.deVehiculo(
                        5L,
                        1L,
                        2L,
                        TipoTransferencia.COMPRA_VENTA,
                        LocalDate.of(2026, 3, 1),
                        Dinero.de("15000.00"),
                        false,
                        "Tarjeta de propiedad",
                        OBSERVACION);

        assertThat(transferencia.objeto()).isEqualTo(ObjetoDeTransferencia.VEHICULO);
        assertThat(transferencia.vehiculoId()).isEqualTo(5L);
        assertThat(transferencia.predioId()).isNull();
        assertThat(transferencia.porcentajeTransferido()).isEqualTo(Porcentaje.total());
    }

    @Test
    @DisplayName("una transferencia de predio sin predio no se construye")
    void unaTransferenciaDePredioSinPredioNoSeConstruye() {
        assertThatThrownBy(
                        () ->
                                new Transferencia(
                                        null,
                                        ObjetoDeTransferencia.PREDIO,
                                        null,
                                        null,
                                        1L,
                                        2L,
                                        TipoTransferencia.COMPRA_VENTA,
                                        LocalDate.of(2026, 3, 1),
                                        Dinero.de("100"),
                                        Porcentaje.total(),
                                        true,
                                        "Escritura",
                                        OBSERVACION,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("una transferencia de predio con vehiculo no se construye")
    void unaTransferenciaDePredioConVehiculoNoSeConstruye() {
        assertThatThrownBy(
                        () ->
                                new Transferencia(
                                        null,
                                        ObjetoDeTransferencia.PREDIO,
                                        10L,
                                        5L,
                                        1L,
                                        2L,
                                        TipoTransferencia.COMPRA_VENTA,
                                        LocalDate.of(2026, 3, 1),
                                        Dinero.de("100"),
                                        Porcentaje.total(),
                                        true,
                                        "Escritura",
                                        OBSERVACION,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no lleva vehiculo");
    }

    @Test
    @DisplayName("el transferente y el adquiriente no pueden ser el mismo contribuyente")
    void elTransferenteYElAdquirienteNoPuedenSerElMismo() {
        assertThatThrownBy(
                        () ->
                                Transferencia.dePredio(
                                        10L,
                                        1L,
                                        1L,
                                        TipoTransferencia.COMPRA_VENTA,
                                        LocalDate.of(2026, 3, 1),
                                        Dinero.de("100"),
                                        Porcentaje.total(),
                                        true,
                                        "Escritura",
                                        OBSERVACION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no pueden ser el mismo contribuyente");
    }

    @Test
    @DisplayName("un valor de transferencia negativo no se construye")
    void unValorNegativoNoSeConstruye() {
        assertThatThrownBy(
                        () ->
                                Transferencia.dePredio(
                                        10L,
                                        1L,
                                        2L,
                                        TipoTransferencia.COMPRA_VENTA,
                                        LocalDate.of(2026, 3, 1),
                                        Dinero.de("-1"),
                                        Porcentaje.total(),
                                        true,
                                        "Escritura",
                                        OBSERVACION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no puede ser negativo");
    }

    @Test
    @DisplayName("sin observacion no se construye (regla 10)")
    void sinObservacionNoSeConstruye() {
        assertThatThrownBy(
                        () ->
                                new Transferencia(
                                        null,
                                        ObjetoDeTransferencia.VEHICULO,
                                        null,
                                        5L,
                                        1L,
                                        2L,
                                        TipoTransferencia.COMPRA_VENTA,
                                        LocalDate.of(2026, 3, 1),
                                        Dinero.de("100"),
                                        Porcentaje.total(),
                                        false,
                                        "Tarjeta de propiedad",
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("regla 10");
    }
}
