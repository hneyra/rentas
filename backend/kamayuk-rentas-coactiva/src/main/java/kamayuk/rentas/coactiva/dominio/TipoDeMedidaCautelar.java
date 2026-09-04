package kamayuk.rentas.coactiva.dominio;

import java.text.Normalizer;
import java.util.Locale;

/**
 * En que forma se traba la medida cautelar que la REC-2 ordena (#41, RF-101).
 *
 * <p>Son las formas de embargo del art. 33 de la Ley 26979, y el vocabulario es el del desplegable
 * «Tipo de medida» de la pantalla {@code proceso_coactivo}: el prototipo manda. La restriccion de
 * {@code acto_coactivo.medida} (V34) admite exactamente estas cuatro.
 *
 * <p><b>La forma no es una etiqueta.</b> De ella depende a quien se notifica la medida —al banco en
 * la retencion, a los Registros Publicos en la inscripcion, al depositario en el deposito— y que se
 * puede hacer despues con el bien. Un acto que no la declarara dejaria una medida trabada sin decir
 * sobre que.
 */
public enum TipoDeMedidaCautelar {

    /** Embargo en forma de retencion: sobre fondos en poder de terceros. */
    RETENCION("EMBARGO EN FORMA DE RETENCION"),

    /** Embargo en forma de inscripcion: se anota en el registro del bien. */
    INSCRIPCION("EMBARGO EN FORMA DE INSCRIPCION"),

    /** Embargo en forma de deposito, con o sin extraccion. */
    DEPOSITO("EMBARGO EN FORMA DE DEPOSITO"),

    /** Embargo en forma de intervencion: en recaudacion, informacion o administracion. */
    INTERVENCION("EMBARGO EN FORMA DE INTERVENCION");

    private final String etiqueta;

    TipoDeMedidaCautelar(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    /** Como lo escribe la pantalla, y como se imprime en la resolucion. */
    public String etiqueta() {
        return etiqueta;
    }

    /**
     * La medida cuyo nombre o etiqueta coincide.
     *
     * <p>Acepta las dos formas porque las dos circulan: el nombre corto en el cuerpo JSON y la
     * etiqueta larga en el desplegable del prototipo. Traducirlas en el borde del endpoint seria
     * tener dos traductores que un dia difieren.
     *
     * <p><b>Y las acepta con tilde.</b> El desplegable dice «EMBARGO EN FORMA DE RETENCIÓN», y lo
     * que llega por HTTP es lo que el desplegable dice. Los identificadores del codigo no llevan
     * tilde (CLAUDE.md §Idioma), asi que la comparacion las quita en vez de exigir que quien teclea
     * acierte con el acento.
     *
     * @throws IllegalArgumentException si no es ninguna de las cuatro
     */
    public static TipoDeMedidaCautelar porNombre(String nombre) {
        String normalizado = sinTildes(nombre.strip().toUpperCase(Locale.ROOT));
        for (TipoDeMedidaCautelar medida : values()) {
            if (medida.name().equals(normalizado) || medida.etiqueta.equals(normalizado)) {
                return medida;
            }
        }
        throw new IllegalArgumentException(
                "Tipo de medida cautelar desconocido: '"
                        + nombre
                        + "'. Se admite RETENCION, INSCRIPCION, DEPOSITO o INTERVENCION (art. 33"
                        + " de la Ley 26979)");
    }

    private static String sinTildes(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
