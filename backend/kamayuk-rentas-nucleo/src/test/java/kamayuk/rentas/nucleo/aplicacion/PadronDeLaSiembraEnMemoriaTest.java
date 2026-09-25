package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.nucleo.dominio.TipoTransferencia;
import kamayuk.rentas.nucleo.dominio.Transferencia;
import kamayuk.rentas.nucleo.dominio.TransferenciaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El doble en memoria contesta lo que el puerto promete, no otra cosa (#473).
 *
 * <p>{@link TransferenciaRepository#vehiculosQueTransfirioDesde} dice «con fecha igual o posterior
 * a {@code fecha}», y el JDBC lo escribe con {@code >=} desde la ronda 1 de #329. El doble se quedo
 * con {@code isAfter}, que es estricto: una prueba que lo usara para el vendedor del mismo 1 de
 * enero veria desaparecer el vehiculo de su calculo, y el rojo acusaria a la regla del art. 31 en
 * vez de al doble. Hasta #473 ninguna prueba lo ejercia.
 *
 * <p>La siembra que distingue son tres transferencias del mismo transferente alrededor del borde:
 * la del 31 de diciembre no entra, la del 1 de enero SI —es la que el {@code isAfter} perdia— y la
 * de junio entra con cualquiera de las dos lecturas.
 */
@DisplayName("#473 — El doble de las transferencias cuenta la fecha misma, como el puerto")
class PadronDeLaSiembraEnMemoriaTest {

    private static final LocalDate PRIMERO_DE_ENERO = LocalDate.of(2026, 1, 1);

    @Test
    @DisplayName("vehiculosQueTransfirioDesde incluye la transferencia fechada ese mismo dia")
    void laFechaMismaCuenta() {
        PadronDeLaSiembraEnMemoria padron = new PadronDeLaSiembraEnMemoria();
        long vendedor = padron.sembrarContribuyente("C-000001", "VENDEDOR, DEL BORDE");
        long comprador = padron.sembrarContribuyente("C-000002", "COMPRADOR, DEL BORDE");
        long deDiciembre = padron.sembrarVehiculo(Placa.de("DIC-311"), vendedor);
        long deEnero = padron.sembrarVehiculo(Placa.de("ENE-011"), vendedor);
        long deJunio = padron.sembrarVehiculo(Placa.de("JUN-101"), vendedor);

        TransferenciaRepository registro = padron.registroDeTransferencias();
        registro.insertar(venta(deDiciembre, vendedor, comprador, LocalDate.of(2025, 12, 31)));
        registro.insertar(venta(deEnero, vendedor, comprador, PRIMERO_DE_ENERO));
        registro.insertar(venta(deJunio, vendedor, comprador, LocalDate.of(2026, 6, 10)));

        assertThat(registro.vehiculosQueTransfirioDesde(vendedor, PRIMERO_DE_ENERO))
                .as(
                        "la del 1 de enero cuenta —el puerto dice «igual o posterior» y el JDBC"
                                + " escribe >=—; la del 31 de diciembre no")
                .containsExactlyInAnyOrder(deEnero, deJunio);
        assertThat(registro.vehiculosQueTransfirioDesde(comprador, PRIMERO_DE_ENERO))
                .as("y solo las del transferente que se pregunta")
                .isEmpty();
    }

    private static Transferencia venta(
            long vehiculoId, long transferente, long adquiriente, LocalDate fecha) {
        return Transferencia.deVehiculo(
                vehiculoId,
                transferente,
                adquiriente,
                TipoTransferencia.COMPRA_VENTA,
                fecha,
                Dinero.de("10000.00"),
                false,
                "Tarjeta de propiedad",
                Observacion.de("Venta sembrada para la prueba del doble"));
    }
}
