package kamayuk.rentas.nucleo.dominio.vehicular;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.nucleo.dominio.ObjetoDeTransferencia;
import kamayuk.rentas.nucleo.dominio.TipoTransferencia;
import kamayuk.rentas.nucleo.dominio.Transferencia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La adquisicion es la del propietario al 1 de enero (#330). La siembra que distingue es una
 * transferencia con un precio DISTINTO del de la columna del vehiculo: con una sola adquisicion,
 * leer siempre la columna y leer la del propietario dan lo mismo.
 */
@DisplayName(
        "#330 — AdquisicionDelPropietario: el precio al que entro el contribuyente del ejercicio")
class AdquisicionDelPropietarioTest {

    private static final long A = 1L;
    private static final long B = 2L;
    private static final long VEHICULO = 7L;
    private static final Dinero DEL_PRIMERO = Dinero.de("30000.00");
    private static final Ejercicio E2026 = new Ejercicio(2026);

    @Test
    @DisplayName("sin transferencias, la del vehiculo")
    void sinTransferenciasEsLaDelVehiculo() {
        assertThat(AdquisicionDelPropietario.de(A, DEL_PRIMERO, List.of(), E2026))
                .contains(DEL_PRIMERO);
    }

    @Test
    @DisplayName("comprado en 2025 en 60 000,00: 2026 usa lo que pago el comprador, no la columna")
    void compradoAntesDelEjercicio() {
        List<Transferencia> historia = List.of(venta(1L, LocalDate.of(2025, 6, 10), "60000.00"));

        assertThat(AdquisicionDelPropietario.de(B, DEL_PRIMERO, historia, E2026))
                .contains(Dinero.de("60000.00"));
    }

    @Test
    @DisplayName("vendido en junio de 2026: 2026 sigue usando la del vendedor")
    void vendidoDentroDelEjercicio() {
        List<Transferencia> historia = List.of(venta(1L, LocalDate.of(2026, 6, 10), "60000.00"));

        assertThat(AdquisicionDelPropietario.de(A, DEL_PRIMERO, historia, E2026))
                .contains(DEL_PRIMERO);
    }

    @Test
    @DisplayName("vendido el mismo 1 de enero: ese ejercicio usa la del vendedor (art. 31)")
    void vendidoElPrimeroDeEnero() {
        List<Transferencia> historia = List.of(venta(1L, LocalDate.of(2026, 1, 1), "60000.00"));

        assertThat(AdquisicionDelPropietario.de(A, DEL_PRIMERO, historia, E2026))
                .contains(DEL_PRIMERO);
        assertThat(AdquisicionDelPropietario.de(B, DEL_PRIMERO, historia, new Ejercicio(2027)))
                .contains(Dinero.de("60000.00"));
    }

    @Test
    @DisplayName("una transferencia sin precio no es un valor de adquisicion: no se sabe")
    void sinPrecioNoSeSabe() {
        List<Transferencia> historia = List.of(venta(1L, LocalDate.of(2025, 6, 10), "0.00"));

        assertThat(AdquisicionDelPropietario.de(B, DEL_PRIMERO, historia, E2026)).isEmpty();
    }

    private static Transferencia venta(long id, LocalDate fecha, String precio) {
        return new Transferencia(
                id,
                ObjetoDeTransferencia.VEHICULO,
                null,
                VEHICULO,
                A,
                B,
                TipoTransferencia.COMPRA_VENTA,
                fecha,
                Dinero.de(precio),
                Porcentaje.total(),
                false,
                "Tarjeta de propiedad",
                Observacion.de("Compraventa de prueba"),
                "jefe.rentas");
    }
}
