package kamayuk.rentas.tesoreria.dominio;

import java.util.Locale;
import java.util.Objects;

/**
 * Con que se pago: los cinco valores que admite {@code recibo_forma_pago_check} (V3), en el mismo
 * orden.
 *
 * <p>Agregar una forma aqui sin agregarla al {@code CHECK} de la base falla en ejecucion, y al
 * reves tambien. Los dos sitios se tocan juntos y el diff lo muestra.
 */
public enum FormaDePago {
    EFECTIVO,
    CHEQUE,
    DEPOSITO,
    TARJETA,
    TRANSFERENCIA;

    /** Traduce lo que llega por HTTP, con un mensaje que dice que se admite. */
    public static FormaDePago porNombre(String texto) {
        Objects.requireNonNull(texto, "La forma de pago es obligatoria");
        try {
            return valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocida) {
            throw new IllegalArgumentException(
                    "Forma de pago desconocida: '"
                            + texto
                            + "'. Se admite EFECTIVO, CHEQUE, DEPOSITO, TARJETA o TRANSFERENCIA");
        }
    }
}
