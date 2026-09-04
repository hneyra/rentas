package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * Los cinco tramites del FUE (#48, RF-113).
 *
 * <p>El vocabulario es el del desplegable «Tipo Tramite» de la pantalla {@code fue_edificacion}, no
 * uno inventado aqui, y el mismo que el {@code CHECK} de {@code licencia_edificacion.tipo_tramite}
 * (V43).
 */
public enum TipoDeTramiteDeEdificacion {

    /**
     * Consulta previa del anteproyecto. <b>No produce licencia</b>: se resuelve con una
     * conformidad, y por eso no se puede emitir.
     */
    ANTEPROYECTO_EN_CONSULTA("Anteproyecto en consulta", false, false),

    /** La licencia de obra propiamente dicha. */
    LICENCIA_DE_OBRA("Licencia de obra", true, false),

    /**
     * Amplia una licencia ya otorgada. <b>Referencia la original y no la sustituye</b> (AC 3): es
     * un expediente nuevo, con su numero y su propia vigencia.
     */
    AMPLIACION_DE_LICENCIA("Ampliacion de licencia", true, true),

    /**
     * Prorroga el plazo de una licencia ya otorgada. No numera de nuevo: agrega un tramo de
     * vigencia a la original, y los dos quedan (AC 4).
     */
    REVALIDACION_DE_LICENCIA("Revalidacion de licencia", false, true),

    /** Regulariza una obra ya ejecutada sin licencia. */
    REGULARIZACION_DE_LICENCIA("Regularizacion de licencia", true, false);

    private final String etiqueta;
    private final boolean emiteLicencia;
    private final boolean exigeOriginal;

    TipoDeTramiteDeEdificacion(String etiqueta, boolean emiteLicencia, boolean exigeOriginal) {
        this.etiqueta = etiqueta;
        this.emiteLicencia = emiteLicencia;
        this.exigeOriginal = exigeOriginal;
    }

    public String etiqueta() {
        return etiqueta;
    }

    /** Si de este tramite sale una licencia con su numero. */
    public boolean emiteLicencia() {
        return emiteLicencia;
    }

    /** Si el tramite se apoya en una licencia anterior, que tiene que nombrar. */
    public boolean exigeLicenciaOriginal() {
        return exigeOriginal;
    }

    public static TipoDeTramiteDeEdificacion porNombre(String nombre) {
        return valueOf(nombre.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }
}
