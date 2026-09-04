package kamayuk.rentas.tesoreria.dominio;

import java.util.Locale;

/**
 * Los cuatro valores de {@code convenio_movimiento_tipo_check} (V31), en el mismo orden.
 *
 * <p>Si algun dia aparece un tipo mas, la insercion fallaria en tiempo de ejecucion, que es tarde;
 * agregarlo aqui sin agregarlo al {@code CHECK} de la base falla igual de tarde, en sentido
 * contrario. Los dos sitios se tocan juntos, y el diff lo muestra.
 */
public enum TipoDeMovimientoDeConvenio {

    /** El cobro de la cuota inicial pone el convenio en vigor y acoge la deuda. */
    FORMALIZACION,

    /** El convenio no debio existir: se deja sin efecto y la deuda vuelve a su fase. */
    ANULACION,

    /** El convenio se incumplio: lo pendiente vuelve a su fase de origen (RF-086). */
    QUIEBRE,

    /** El convenio se sustituye por otro sobre el saldo pendiente (RF-085). */
    REFORMULACION;

    /** Los tres que cierran un convenio devolviendo su deuda. */
    public boolean cierra() {
        return this != FORMALIZACION;
    }

    public static TipoDeMovimientoDeConvenio porNombre(String texto) {
        return valueOf(texto.strip().toUpperCase(Locale.ROOT));
    }
}
