package kamayuk.rentas.dominio;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Placa de rodaje de un vehiculo.
 *
 * <p>Identifica al vehiculo en el patrimonio vehicular y es la clave con la que se busca una
 * papeleta de transito. Se normaliza al construirla —recortada, en mayusculas y sin espacios
 * interiores— porque es un dato que se teclea a mano en una via publica y llega escrito de todas
 * las formas posibles: {@code "abc 123"}, {@code "ABC-123"}, {@code " ABC123 "}. Las tres son la
 * misma placa, y si no se normalizan son tres vehiculos.
 *
 * <p>El guion se conserva tal como se escribio: es separador de lectura, no parte del dato, y
 * quitarlo cambiaria el texto que la papeleta impresa debe reproducir.
 *
 * <p>La composicion admitida cubre los formatos que han convivido en el parque peruano —tres letras
 * y tres digitos, letra-digito-letra y tres digitos, y las placas de motocicleta— sin intentar
 * decidir cual rige hoy: el ancho es el de la columna {@code placa varchar(10)}, y lo que se exige
 * es que haya letras y digitos y ningun espacio.
 */
public record Placa(String valor) implements Comparable<Placa> {

    private static final int LARGO_MINIMO = 5;
    private static final int LARGO_MAXIMO = 10;

    /** Bloques alfanumericos en mayusculas separados como mucho por un guion. */
    private static final Pattern COMPOSICION = Pattern.compile("^[0-9A-Z]+(-[0-9A-Z]+)?$");

    private static final Pattern TIENE_LETRA = Pattern.compile("[A-Z]");
    private static final Pattern TIENE_DIGITO = Pattern.compile("[0-9]");

    public Placa {
        Objects.requireNonNull(valor, "La placa es obligatoria");
        valor = valor.strip().toUpperCase(Locale.ROOT).replace(" ", "");
        if (valor.length() < LARGO_MINIMO || valor.length() > LARGO_MAXIMO) {
            throw new IllegalArgumentException(
                    "Placa de longitud invalida: '"
                            + valor
                            + "'. Se admite de "
                            + LARGO_MINIMO
                            + " a "
                            + LARGO_MAXIMO
                            + " caracteres");
        }
        if (!COMPOSICION.matcher(valor).matches()
                || !TIENE_LETRA.matcher(valor).find()
                || !TIENE_DIGITO.matcher(valor).find()) {
            throw new IllegalArgumentException(
                    "Placa invalida: '"
                            + valor
                            + "'. Se admiten letras y digitos, con un guion opcional, y debe llevar"
                            + " al menos una letra y un digito");
        }
    }

    public static Placa de(String texto) {
        return new Placa(texto);
    }

    /** La placa sin su guion. Es la forma con la que se compara y se busca. */
    public String sinSeparador() {
        return valor.replace("-", "");
    }

    /** Dos placas son la misma aunque una lleve guion y la otra no: el separador es de lectura. */
    @Override
    public boolean equals(Object otra) {
        return otra instanceof Placa placa && sinSeparador().equals(placa.sinSeparador());
    }

    @Override
    public int hashCode() {
        return sinSeparador().hashCode();
    }

    @Override
    public int compareTo(Placa otra) {
        return sinSeparador().compareTo(otra.sinSeparador());
    }

    @Override
    public String toString() {
        return valor;
    }
}
