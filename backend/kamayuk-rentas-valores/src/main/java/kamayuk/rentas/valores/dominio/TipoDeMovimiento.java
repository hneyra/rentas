package kamayuk.rentas.valores.dominio;

import java.util.Locale;

/**
 * El movimiento de un valor hacia el area de cobranza coactiva (RF-095, V28 {@code
 * valor_movimiento.tipo}).
 *
 * <p>Los tres codigos son los del manual. #39 solo produce {@link #PCO}: {@link #ACO} y {@link
 * #RCO} son la respuesta de coactiva y los escribe #40, cuando exista el expediente que responde.
 */
public enum TipoDeMovimiento {

    /** Pase a coactivas: lo que hace exigible el expediente. */
    PCO("Pase a coactivas"),

    /** Aceptado en coactivas. */
    ACO("Aceptado en coactivas"),

    /** Rechazado en coactivas. */
    RCO("Rechazado en coactivas");

    private final String descripcion;

    TipoDeMovimiento(String descripcion) {
        this.descripcion = descripcion;
    }

    public String descripcion() {
        return descripcion;
    }

    /**
     * El tipo cuyo nombre coincide, sin distinguir mayusculas.
     *
     * @throws IllegalArgumentException si no es PCO, ACO ni RCO
     */
    public static TipoDeMovimiento porCodigo(String codigo) {
        String normalizado = codigo.strip().toUpperCase(Locale.ROOT);
        for (TipoDeMovimiento tipo : values()) {
            if (tipo.name().equals(normalizado)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException(
                "Tipo de movimiento desconocido: '" + codigo + "'. Se admite PCO, ACO o RCO");
    }
}
