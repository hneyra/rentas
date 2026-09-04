package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * Que clase de licencia de funcionamiento es (#44, RF-110).
 *
 * <p>El vocabulario es el del desplegable «Tipo de licencia» de la pantalla {@code
 * licencia_funcionamiento}, y {@code licencia_tipo_ck} (V37) lo repite en la base. Que este en los
 * dos sitios no es duplicacion ociosa: un {@code INSERT} por SQL directo se salta esta enumeracion,
 * y el {@code CHECK} no.
 */
public enum TipoDeLicencia {

    /** La ordinaria: rige mientras el giro y el establecimiento no cambien. */
    DEFINITIVA("Definitiva"),

    /** Con plazo determinado; su {@code vigencia_hasta} es obligatoria. */
    TEMPORAL("Temporal"),

    /** La del cesionario que opera dentro del establecimiento de otro titular. */
    CESIONARIA("Cesionaria");

    private final String etiqueta;

    TipoDeLicencia(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    /** Como se escribe en el papel. */
    public String etiqueta() {
        return etiqueta;
    }

    /**
     * Una licencia temporal sin fecha de vencimiento no seria temporal.
     *
     * <p>Vive aqui y no en el caso de uso porque es una propiedad del tipo, no del tramite: sea
     * cual sea la pantalla que la registre, una temporal sin plazo es una definitiva mal rotulada.
     */
    public boolean exigeVigencia() {
        return this == TEMPORAL;
    }

    /** El tipo con ese nombre, tal como lo guarda la base. */
    public static TipoDeLicencia porNombre(String nombre) {
        return valueOf(nombre.strip().toUpperCase(Locale.ROOT));
    }
}
