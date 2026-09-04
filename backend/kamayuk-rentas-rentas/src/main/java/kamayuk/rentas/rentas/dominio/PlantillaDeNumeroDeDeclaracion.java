package kamayuk.rentas.rentas.dominio;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Como se compone el numero de una declaracion jurada (#365).
 *
 * <p><b>El numero lo pone el sistema.</b> Es como opera la administracion tributaria municipal
 * peruana —el sistema genera el numero de referencia de la declaracion, y quien la presenta se
 * lleva ese numero en el papel— y es la doctrina de la casa: los actos se numeran con correlativo
 * propio y plantilla parametrizada. El numero de mesa de partes, si el tramite lo tiene, es otra
 * cosa: una referencia del expediente, nunca la identidad de la DJ.
 *
 * <p>Es un <b>parametro</b> y no un formato escrito dentro del codigo porque <b>D-09 sigue
 * abierta</b>. Mismo precedente y mismo motivo que {@code PlantillaDeNumeroDeCertificado} (#54),
 * {@code PlantillaDeNumeroDeAnuncio} (#51) y {@code PlantillaDeNumeroDeLicencia} (#44): con la
 * composicion afuera, cerrar la decision es cambiar una plantilla; con la composicion adentro seria
 * cambiar la validacion, las consultas y las pruebas de todo lo que la use.
 *
 * <p>{@code dj_correlativo} (V54) guarda el correlativo <b>desnudo</b>, de modo que el dia que la
 * plantilla cambie el correlativo siga siendo el mismo.
 *
 * <h2>{@code {ejercicio}} es obligatorio, y aqui el motivo es literal</h2>
 *
 * <p>El correlativo <b>se reinicia con el ejercicio</b> —la serie es del año— y desde V54 el numero
 * es unico en toda la municipalidad, no dentro de su año. Sin {@code {ejercicio}}, la declaracion
 * numero 1 de 2026 compondria el mismo numero que la numero 1 de 2025 y {@code dj_numero_uq} la
 * rechazaria: el sistema dejaria de poder registrar declaraciones el 1 de enero, y el sintoma no se
 * pareceria a su causa.
 *
 * <h2>{@code {tipo}} es opcional, al reves que en el certificado</h2>
 *
 * <p>Porque la serie es por ejercicio y no por tipo: los cuatro formularios de un año comparten una
 * sola numeracion, como muestra el manual —{@code 000392} ANUAL MECANIZADA, {@code 000401}
 * INSCRIPCION y {@code 000418} RECTIFICATORIA, los tres del mismo ejercicio—. Con la serie
 * compartida, llevar {@code {tipo}} o no llevarlo no puede producir dos numeros iguales; en el
 * certificado la serie <b>es</b> por tipo y omitir la marca si los producia, que es lo que su
 * constructor existe para impedir.
 *
 * @param plantilla el texto con sus marcas: {@code {ejercicio}}, {@code {correlativo[:N]}} y, si se
 *     quiere, {@code {tipo}}
 */
public record PlantillaDeNumeroDeDeclaracion(String plantilla) {

    /** {@code declaracion_jurada.numero varchar(20)} (V2). */
    public static final int NUMERO_MAXIMO = 20;

    private static final String MARCA_EJERCICIO = "{ejercicio}";

    private static final String MARCA_TIPO = "{tipo}";

    private static final Pattern MARCA_CORRELATIVO = Pattern.compile("\\{correlativo(?::(\\d+))?}");

    /**
     * La plantilla por omision mientras D-09 no se cierre: {@code DJ-2026-000418}.
     *
     * <p>TODO D-09: contrastar con las declaraciones reales de la municipalidad piloto. El
     * prototipo dibuja el numero desnudo —{@code 000418}, con el año en una columna aparte—, y esa
     * forma <b>no se puede usar</b> desde V54: el numero es unico en la municipalidad, asi que el
     * año tiene que estar dentro. Se elige entonces la forma que ya usan el valor ({@code
     * OP-2026-000001}), el convenio, el expediente, la licencia y el certificado: un formato
     * distinto en el mismo sistema seria una decision, y aqui no hay ninguna que tomar todavia.
     */
    public static final PlantillaDeNumeroDeDeclaracion POR_OMISION =
            new PlantillaDeNumeroDeDeclaracion("DJ-{ejercicio}-{correlativo:6}");

    public PlantillaDeNumeroDeDeclaracion {
        Objects.requireNonNull(plantilla, "La plantilla del numero es obligatoria (D-09)");
        if (!plantilla.contains(MARCA_EJERCICIO)) {
            throw new IllegalArgumentException(
                    "La plantilla tiene que llevar {ejercicio}: el correlativo se reinicia con el"
                            + " ejercicio y el numero es unico en la municipalidad, asi que sin el"
                            + " año la declaracion 1 de un año chocaria con la 1 del siguiente");
        }
        if (!MARCA_CORRELATIVO.matcher(plantilla).find()) {
            throw new IllegalArgumentException(
                    "La plantilla tiene que llevar {correlativo} o {correlativo:N}");
        }
    }

    /**
     * El numero de ese correlativo, para ese tipo y ese ejercicio.
     *
     * @throws IllegalArgumentException si el correlativo no es positivo o si el numero compuesto no
     *     cabe en la columna
     */
    public String componer(TipoDeDeclaracion tipo, Ejercicio ejercicio, long correlativo) {
        Objects.requireNonNull(tipo, "Una declaracion jurada se numera sabiendo que formulario es");
        Objects.requireNonNull(
                ejercicio, "Una declaracion jurada se numera dentro de un ejercicio");
        if (correlativo <= 0) {
            throw new IllegalArgumentException(
                    "El correlativo de una declaracion jurada empieza en 1; llego " + correlativo);
        }

        String compuesto =
                plantilla
                        .replace(MARCA_TIPO, tipo.name())
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
                            + " caracteres de declaracion_jurada.numero");
        }
        return numero;
    }
}
