package kamayuk.rentas.licencias.dominio;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Como se compone el numero impreso de una licencia de funcionamiento.
 *
 * <p>Es un <b>parametro</b> y no un formato escrito dentro del codigo porque <b>D-09 sigue
 * abierta</b>: «numeracion de valores y expedientes: correlativo por municipalidad y ejercicio, con
 * que formato y que reinicio». Mismo precedente que {@code PlantillaDeNumeroDeExpediente} (#40) y
 * {@code ComposicionCatastral} (D-10), y por el mismo motivo: con la composicion afuera, cerrar la
 * decision es cambiar una plantilla; con la composicion adentro, seria cambiar la validacion, las
 * consultas y las pruebas de todo lo que la use, y migrar la columna que ya tiene numeros con el
 * formato viejo.
 *
 * <p>{@code licencia_correlativo} (V37) guarda el correlativo <b>desnudo</b>, de modo que el dia
 * que la plantilla cambie el correlativo siga siendo el mismo.
 *
 * <h2>Compone y no analiza, a diferencia de la del expediente</h2>
 *
 * <p>{@code PlantillaDeNumeroDeExpediente} lleva ademas un {@code analizar}, porque el numero del
 * expediente llega por la ruta HTTP y hay que sacarle el ejercicio y el correlativo. Aqui no hace
 * falta: la ruta identifica la licencia por su numero <b>completo</b> y la consulta lo compara tal
 * cual, sin descomponerlo. Si algun dia hiciera falta, el analizador va <b>aqui</b>, junto al
 * compositor y contra la misma plantilla; escribirlo en el endpoint seria tener dos formatos que un
 * dia difieren, que es exactamente el defecto que #40 documento.
 *
 * @param plantilla el texto con sus marcas, {@code {ejercicio}} y {@code {correlativo[:N]}}
 */
public record PlantillaDeNumeroDeLicencia(String plantilla) {

    /** {@code licencia_funcionamiento.numero varchar(20)} (V4). */
    public static final int NUMERO_MAXIMO = 20;

    private static final String MARCA_EJERCICIO = "{ejercicio}";

    private static final Pattern MARCA_CORRELATIVO = Pattern.compile("\\{correlativo(?::(\\d+))?}");

    /**
     * La plantilla por omision mientras D-09 no se cierre: {@code LF-2026-000001}.
     *
     * <p>TODO D-09: contrastar con las licencias reales de la municipalidad piloto. Se elige la
     * forma que ya usan el valor ({@code OP-2026-000001}), el convenio ({@code F-2026-000123}) y el
     * expediente ({@code EXP-2026-000001}): un cuarto formato distinto en el mismo sistema seria
     * una decision, y aqui no hay ninguna que tomar todavia. <b>Ninguna de las cuatro esta
     * verificada contra el piloto</b>, y por eso las cuatro son cambiables sin migrar codigo.
     */
    public static final PlantillaDeNumeroDeLicencia POR_OMISION =
            new PlantillaDeNumeroDeLicencia("LF-{ejercicio}-{correlativo:6}");

    public PlantillaDeNumeroDeLicencia {
        Objects.requireNonNull(plantilla, "La plantilla del numero es obligatoria (D-09)");
        if (!plantilla.contains(MARCA_EJERCICIO)) {
            throw new IllegalArgumentException(
                    "La plantilla tiene que llevar {ejercicio}: el correlativo se reinicia con el"
                            + " ejercicio, y sin el año dos licencias de años distintos"
                            + " compartirian numero");
        }
        if (!MARCA_CORRELATIVO.matcher(plantilla).find()) {
            throw new IllegalArgumentException(
                    "La plantilla tiene que llevar {correlativo} o {correlativo:N}");
        }
    }

    /**
     * El numero impreso de ese correlativo en ese ejercicio.
     *
     * @throws IllegalArgumentException si el correlativo no es positivo o si el numero compuesto no
     *     cabe en la columna
     */
    public String componer(Ejercicio ejercicio, long correlativo) {
        Objects.requireNonNull(ejercicio, "Una licencia se numera dentro de un ejercicio");
        if (correlativo <= 0) {
            throw new IllegalArgumentException(
                    "El correlativo de una licencia empieza en 1; llego " + correlativo);
        }

        String compuesto = plantilla.replace(MARCA_EJERCICIO, String.valueOf(ejercicio.valor()));
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
                            + " caracteres de licencia_funcionamiento.numero");
        }
        return numero;
    }
}
