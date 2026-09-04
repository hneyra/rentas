package kamayuk.rentas.tesoreria.dominio;

import java.util.Locale;

/**
 * Que le paso a un recibo: los dos valores de {@code recibo_movimiento.tipo} (V30, #34).
 *
 * <p>Son los dos actos que el manual admite sobre un recibo ya emitido, y ninguno de los dos lo
 * toca: se <b>agregan</b>. El recibo no se edita (V29) porque el contribuyente se lleva el papel.
 *
 * <p>La diferencia entre ellos que la base sostiene: {@link #ANULACION} ocurre <b>una vez</b>
 * —{@code recibo_movimiento_anulacion_uq} es un indice unico parcial sobre ella—, y {@link
 * #DUPLICADO} tantas veces como haga falta. Es el mismo reparto que {@code valor_movimiento} hace
 * entre {@code PCO} y las respuestas de coactiva (V28).
 */
public enum TipoDeMovimientoDeRecibo {

    /** Deja el recibo sin efecto y devuelve la deuda al libro (RF-083). */
    ANULACION,

    /** Una reimpresion del recibo, marcada como tal (RF-082). */
    DUPLICADO;

    /** Traduce lo que llega por HTTP, con un mensaje que dice que se admite. */
    public static TipoDeMovimientoDeRecibo porNombre(String texto) {
        try {
            return valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new IllegalArgumentException(
                    "Movimiento de recibo desconocido: '"
                            + texto
                            + "'. Se admite ANULACION o DUPLICADO");
        }
    }
}
