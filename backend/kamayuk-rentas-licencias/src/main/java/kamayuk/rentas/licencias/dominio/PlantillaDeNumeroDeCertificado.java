package kamayuk.rentas.licencias.dominio;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Como se compone el numero impreso de un certificado (#54).
 *
 * <p>Es un <b>parametro</b> y no un formato escrito dentro del codigo porque <b>D-09 sigue
 * abierta</b>. Mismo precedente y mismo motivo que {@code PlantillaDeNumeroDeAnuncio} (#51), {@code
 * PlantillaDeNumeroDeLicencia} (#44) y {@code PlantillaDeNumeroDeExpediente} (#40): con la
 * composicion afuera, cerrar la decision es cambiar una plantilla; con la composicion adentro seria
 * cambiar la validacion, las consultas y las pruebas de todo lo que la use, y migrar la columna que
 * ya tiene numeros con el formato viejo.
 *
 * <p>{@code certificado_correlativo} (V51) guarda el correlativo <b>desnudo</b>, de modo que el dia
 * que la plantilla cambie el correlativo siga siendo el mismo.
 *
 * <h2>La marca {@code {tipo}}, que las otras tres plantillas no tienen</h2>
 *
 * <p>El correlativo de los certificados es <b>por tipo</b> —cada clase es un tramite del TUPA con
 * su propia serie—, asi que dos certificados de tipos distintos pueden llevar el mismo correlativo
 * en el mismo año. Sin la marca, los dos compondrian el mismo numero y {@code
 * certificado_numero_uq} rechazaria el segundo: por eso la plantilla <b>exige</b> {@code {tipo}} en
 * el constructor, en lugar de dejar que el defecto aparezca el dia que alguien pida dos
 * certificados distintos del mismo predio.
 *
 * @param plantilla el texto con sus marcas, {@code {tipo}}, {@code {ejercicio}} y {@code
 *     {correlativo[:N]}}
 */
public record PlantillaDeNumeroDeCertificado(String plantilla) {

    /** {@code certificado.numero varchar(20)} (V51). */
    public static final int NUMERO_MAXIMO = 20;

    private static final String MARCA_EJERCICIO = "{ejercicio}";

    private static final String MARCA_TIPO = "{tipo}";

    private static final Pattern MARCA_CORRELATIVO = Pattern.compile("\\{correlativo(?::(\\d+))?}");

    /**
     * La plantilla por omision mientras D-09 no se cierre: {@code CN-2026-000001}.
     *
     * <p>TODO D-09: contrastar con los certificados reales de la municipalidad piloto. Se elige la
     * forma que ya usan el valor ({@code OP-2026-000001}), el convenio, el expediente, la licencia
     * ({@code LF-2026-000001}) y la autorizacion de anuncio: un formato distinto en el mismo
     * sistema seria una decision, y aqui no hay ninguna que tomar todavia.
     */
    public static final PlantillaDeNumeroDeCertificado POR_OMISION =
            new PlantillaDeNumeroDeCertificado("C{tipo}-{ejercicio}-{correlativo:6}");

    public PlantillaDeNumeroDeCertificado {
        Objects.requireNonNull(plantilla, "La plantilla del numero es obligatoria (D-09)");
        if (!plantilla.contains(MARCA_EJERCICIO)) {
            throw new IllegalArgumentException(
                    "La plantilla tiene que llevar {ejercicio}: el correlativo se reinicia con el"
                            + " ejercicio, y sin el año dos certificados de años distintos"
                            + " compartirian numero");
        }
        if (!plantilla.contains(MARCA_TIPO)) {
            throw new IllegalArgumentException(
                    "La plantilla tiene que llevar {tipo}: el correlativo es POR TIPO, y sin el la"
                            + " numeracion 1 de un certificado y la 1 de otro compondrian el mismo"
                            + " numero");
        }
        if (!MARCA_CORRELATIVO.matcher(plantilla).find()) {
            throw new IllegalArgumentException(
                    "La plantilla tiene que llevar {correlativo} o {correlativo:N}");
        }
    }

    /**
     * El numero impreso de ese correlativo, para ese tipo y ese ejercicio.
     *
     * @throws IllegalArgumentException si el correlativo no es positivo o si el numero compuesto no
     *     cabe en la columna
     */
    public String componer(TipoDeCertificado tipo, Ejercicio ejercicio, long correlativo) {
        Objects.requireNonNull(tipo, "Un certificado se numera dentro de su tipo");
        Objects.requireNonNull(ejercicio, "Un certificado se numera dentro de un ejercicio");
        if (correlativo <= 0) {
            throw new IllegalArgumentException(
                    "El correlativo de un certificado empieza en 1; llego " + correlativo);
        }

        String compuesto =
                plantilla
                        .replace(MARCA_TIPO, abreviatura(tipo))
                        .replace(MARCA_EJERCICIO, String.valueOf(ejercicio.valor()));
        Matcher marca = MARCA_CORRELATIVO.matcher(compuesto);
        StringBuilder salida = new StringBuilder();
        while (marca.find()) {
            String digitos = marca.group(1);
            String texto =
                    digitos == null
                            ? String.valueOf(correlativo)
                            : String.format(
                                    Locale.ROOT,
                                    "%0" + Integer.parseInt(digitos) + "d",
                                    correlativo);
            marca.appendReplacement(salida, Matcher.quoteReplacement(texto));
        }
        marca.appendTail(salida);

        String numero = salida.toString();
        if (numero.length() > NUMERO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero '"
                            + numero
                            + "' excede los "
                            + NUMERO_MAXIMO
                            + " caracteres de certificado.numero");
        }
        return numero;
    }

    /**
     * La letra con que el tipo entra en el numero.
     *
     * <p>Es la inicial del nombre del tipo, y no una tabla de abreviaturas: una tabla seria una
     * decision sobre como numera la municipalidad, y esa decision es D-09. Lo unico que aqui hace
     * falta es que dos tipos no compongan el mismo numero, y para eso basta con que la letra sea
     * distinta —{@code N}, {@code Z}, {@code P}, {@code J}—.
     */
    private static String abreviatura(TipoDeCertificado tipo) {
        return tipo.name().substring(0, 1);
    }
}
