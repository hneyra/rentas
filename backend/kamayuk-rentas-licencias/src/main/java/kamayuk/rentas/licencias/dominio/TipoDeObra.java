package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * Que obra autoriza la licencia (#48, RF-113).
 *
 * <p>El vocabulario es el del desplegable «OBRA» de la pantalla {@code fue_edificacion} y el del
 * {@code CHECK} de {@code licencia_edificacion.tipo_obra} (V43).
 */
public enum TipoDeObra {
    EDIFICACION_NUEVA("Edificacion nueva"),
    AMPLIACION("Ampliacion"),
    REMODELACION("Remodelacion"),
    DEMOLICION("Demolicion"),
    CERCO("Cerco"),
    PUESTA_EN_VALOR("Puesta en valor");

    private final String etiqueta;

    TipoDeObra(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public static TipoDeObra porNombre(String nombre) {
        return valueOf(nombre.strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
    }
}
