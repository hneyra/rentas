package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * De que tipo es el aviso: si esta iluminado, y como (#51, RF-114).
 *
 * <p>Vocabulario del desplegable «Tipo Anuncio» de la pantalla {@code anuncios}. Es descriptivo: no
 * decide la tasa —eso lo hace {@link ClaseDeAnuncio}— pero si es un vocabulario cerrado, porque de
 * el depende que exigencias tecnicas se le piden al soporte y la pantalla lo pinta como
 * desplegable.
 */
public enum TipoDeAnuncio {

    /** Sin iluminacion propia ni externa. */
    AVISO_SIMPLE("Aviso simple"),

    /** Con luz propia. */
    AVISO_LUMINOSO("Aviso luminoso"),

    /** Iluminado desde fuera. */
    AVISO_ILUMINADO("Aviso iluminado"),

    /** Electronico, con contenido variable. */
    AVISO_ELECTRONICO("Aviso electronico");

    private final String etiqueta;

    TipoDeAnuncio(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public static TipoDeAnuncio porNombre(String nombre) {
        return valueOf(nombre.strip().replace(' ', '_').toUpperCase(Locale.ROOT));
    }
}
