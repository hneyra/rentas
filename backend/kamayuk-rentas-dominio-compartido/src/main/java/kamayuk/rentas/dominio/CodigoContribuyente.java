package kamayuk.rentas.dominio;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * El «codigo unico» del manual (cap. 2, §Registro de Contribuyentes): el identificador con el que
 * se localizan <b>todas</b> las obligaciones de una persona, sea cual sea el tributo.
 *
 * <p>Es la clave que atraviesa el sistema entero —predial, arbitrios, vehicular, papeletas,
 * licencias, coactiva— y por eso viaja como tipo y no como cadena: una cadena se compara con
 * espacios de mas, se busca en minusculas y devuelve vacio, y el contribuyente «no existe».
 *
 * <p>Se normaliza al construirlo: recortado y en mayusculas. El ancho es el de la columna {@code
 * codigo_contribuyente varchar(20)}.
 *
 * <p><b>No es el numero de documento.</b> El documento identifica a la persona ante el Estado; el
 * codigo la identifica ante <i>esta</i> municipalidad, y una persona puede cambiar de documento sin
 * cambiar de codigo. Ver {@link DocumentoIdentidad}.
 */
public record CodigoContribuyente(String valor) implements Comparable<CodigoContribuyente> {

    private static final int MAXIMO = 20;

    /**
     * Digitos, letras y guion. La composicion exacta la fija cada municipalidad —el manual no la
     * impone—, asi que aqui se exige lo unico que si consta: que sea un identificador compacto y
     * sin espacios, para que dos escrituras del mismo codigo no produzcan dos contribuyentes.
     */
    private static final Pattern COMPOSICION = Pattern.compile("^[0-9A-Z-]{1,20}$");

    public CodigoContribuyente {
        Objects.requireNonNull(valor, "El codigo de contribuyente es obligatorio");
        valor = valor.strip().toUpperCase(Locale.ROOT);
        if (valor.length() > MAXIMO) {
            throw new IllegalArgumentException(
                    "El codigo de contribuyente excede " + MAXIMO + " caracteres: " + valor);
        }
        if (!COMPOSICION.matcher(valor).matches()) {
            throw new IllegalArgumentException(
                    "Codigo de contribuyente invalido: '"
                            + valor
                            + "'. Se admiten digitos, letras y guion, sin espacios");
        }
    }

    public static CodigoContribuyente de(String texto) {
        return new CodigoContribuyente(texto);
    }

    @Override
    public int compareTo(CodigoContribuyente otro) {
        return valor.compareTo(otro.valor);
    }

    @Override
    public String toString() {
        return valor;
    }
}
