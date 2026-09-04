package kamayuk.rentas.coactiva.dominio;

import java.util.Locale;

/**
 * Que clase de acto registra una fila del historial del expediente (V33, {@code
 * expediente_movimiento.tipo}).
 *
 * <p>Tres y no dos: la apertura se distingue del cambio de estado aunque las dos lleven estado,
 * porque solo puede haber <b>una</b> —{@code expediente_movimiento_apertura_uq}— y porque es la
 * unica que no tiene un estado anterior que explicar.
 */
public enum TipoDeMovimientoDelExpediente {

    /** La apertura del expediente, con los valores importados. Una sola por expediente. */
    APERTURA,

    /** Un cambio de estado del procedimiento (RF-100). */
    ESTADO,

    /** Un cambio de la direccion referencial del obligado (RF-106). */
    DIRECCION;

    /** Si el movimiento lleva estado y no direccion. */
    public boolean llevaEstado() {
        return this != DIRECCION;
    }

    /**
     * El tipo cuyo nombre coincide, sin distinguir mayusculas.
     *
     * @throws IllegalArgumentException si no es ninguno de los tres
     */
    public static TipoDeMovimientoDelExpediente porNombre(String nombre) {
        String normalizado = nombre.strip().toUpperCase(Locale.ROOT);
        for (TipoDeMovimientoDelExpediente tipo : values()) {
            if (tipo.name().equals(normalizado)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException(
                "Tipo de movimiento desconocido: '"
                        + nombre
                        + "'. Se admite APERTURA, ESTADO o DIRECCION");
    }
}
