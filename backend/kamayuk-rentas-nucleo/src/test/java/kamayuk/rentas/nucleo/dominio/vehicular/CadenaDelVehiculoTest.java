package kamayuk.rentas.nucleo.dominio.vehicular;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.nucleo.dominio.ObjetoDeTransferencia;
import kamayuk.rentas.nucleo.dominio.TipoTransferencia;
import kamayuk.rentas.nucleo.dominio.Transferencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La cadena de un vehiculo solo crece hacia adelante, como funcion pura (#473).
 *
 * <p>La siembra que distingue es la del issue —A→B el 10/06 y una fechada el 01/03— con el borde de
 * la misma fecha, que es lo que separa {@code isBefore} de {@code !isAfter}. Y el historico va
 * <b>desordenado</b> en una de ellas: la regla toma la ultima por fecha, no la ultima de la lista.
 */
@DisplayName("#473 — CadenaDelVehiculo: ninguna transferencia antes de la ultima")
class CadenaDelVehiculoTest {

    private static final long A = 11L;
    private static final long B = 22L;
    private static final long C = 33L;
    private static final LocalDate DIEZ_DE_JUNIO = LocalDate.of(2026, 6, 10);

    @Test
    @DisplayName("sin historico, cualquier fecha entra")
    void sinHistoricoCualquierFechaEntra() {
        assertThatCode(
                        () ->
                                CadenaDelVehiculo.exigirQueSigaALaUltima(
                                        List.of(), LocalDate.of(1990, 1, 1)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A->B el 10/06 y una del 01/03: se rechaza nombrando las dos fechas")
    void unaAnteriorALaUltimaSeRechaza() {
        List<Transferencia> historia = List.of(de(1L, A, B, DIEZ_DE_JUNIO));

        assertThatThrownBy(
                        () ->
                                CadenaDelVehiculo.exigirQueSigaALaUltima(
                                        historia, LocalDate.of(2026, 3, 1)))
                .isInstanceOf(CadenaDelVehiculo.TransferenciaAnteriorALaUltima.class)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2026-03-01")
                .hasMessageContaining("2026-06-10")
                .satisfies(
                        error -> {
                            var anterior = (CadenaDelVehiculo.TransferenciaAnteriorALaUltima) error;
                            assertThat(anterior.fecha()).isEqualTo(LocalDate.of(2026, 3, 1));
                            assertThat(anterior.ultima()).isEqualTo(DIEZ_DE_JUNIO);
                        });
    }

    @Test
    @DisplayName("la misma fecha que la ultima entra: dos actos del mismo dia van por su registro")
    void laMismaFechaEntra() {
        List<Transferencia> historia = List.of(de(1L, A, B, DIEZ_DE_JUNIO));

        assertThatCode(() -> CadenaDelVehiculo.exigirQueSigaALaUltima(historia, DIEZ_DE_JUNIO))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("una posterior entra")
    void unaPosteriorEntra() {
        List<Transferencia> historia = List.of(de(1L, A, B, DIEZ_DE_JUNIO));

        assertThatCode(
                        () ->
                                CadenaDelVehiculo.exigirQueSigaALaUltima(
                                        historia, DIEZ_DE_JUNIO.plusDays(1)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("la ultima es la de fecha mayor, no la ultima de la lista")
    void laUltimaEsLaDeFechaMayor() {
        List<Transferencia> desordenada =
                List.of(de(2L, B, C, DIEZ_DE_JUNIO), de(1L, A, B, LocalDate.of(2026, 2, 1)));

        assertThatThrownBy(
                        () ->
                                CadenaDelVehiculo.exigirQueSigaALaUltima(
                                        desordenada, LocalDate.of(2026, 3, 1)))
                .as(
                        "el 01/03 va despues del 01/02 que cierra la lista, pero antes del 10/06,"
                                + " que es la ultima de verdad")
                .isInstanceOf(CadenaDelVehiculo.TransferenciaAnteriorALaUltima.class)
                .hasMessageContaining("2026-06-10");
    }

    private static Transferencia de(long id, long transferente, long adquiriente, LocalDate fecha) {
        return new Transferencia(
                id,
                ObjetoDeTransferencia.VEHICULO,
                null,
                7L,
                transferente,
                adquiriente,
                TipoTransferencia.COMPRA_VENTA,
                fecha,
                Dinero.de("60000.00"),
                Porcentaje.total(),
                false,
                "Tarjeta de propiedad",
                Observacion.de("Compraventa de prueba"),
                "jefe.rentas");
    }
}
