package kamayuk.rentas.tesoreria.dominio;

import java.util.Locale;

/**
 * Los dos valores de {@code convenio_tipo_check} (V3).
 *
 * <p>{@link #COACTIVO} es el fraccionamiento que se suscribe con un expediente coactivo abierto: la
 * pantalla del modulo de coactiva ({@code fraccionamiento_coactivo}) lo registra, y la diferencia
 * de fondo es a que fase vuelve la deuda si el convenio se quiebra —a coactiva, no a ordinaria—.
 * Eso no lo decide este campo sino {@code convenio_deuda.fase_origen}, cuota por cuota; el tipo es
 * la constancia administrativa de bajo que procedimiento se firmo.
 */
public enum TipoDeConvenio {
    ORDINARIO,
    COACTIVO;

    /** El tipo con ese nombre, sin distinguir mayusculas. */
    public static TipoDeConvenio porNombre(String texto) {
        return valueOf(texto.strip().toUpperCase(Locale.ROOT));
    }
}
