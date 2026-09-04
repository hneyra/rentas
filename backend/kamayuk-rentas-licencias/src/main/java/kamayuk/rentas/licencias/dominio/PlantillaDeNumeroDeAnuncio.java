package kamayuk.rentas.licencias.dominio;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Como se compone el numero impreso de una autorizacion de anuncio (#51).
 *
 * <p>Es un <b>parametro</b> y no un formato escrito dentro del codigo porque <b>D-09 sigue
 * abierta</b>. Mismo precedente y mismo motivo que {@code PlantillaDeNumeroDeLicencia} (#44),
 * {@code PlantillaDeNumeroDeExpediente} (#40) y {@code ComposicionCatastral} (D-10): con la
 * composicion afuera, cerrar la decision es cambiar una plantilla; con la composicion adentro seria
 * cambiar la validacion, las consultas y las pruebas de todo lo que la use, y migrar la columna que
 * ya tiene numeros con el formato viejo.
 *
 * <p>{@code anuncio_correlativo} (V45) guarda el correlativo <b>desnudo</b>, de modo que el dia que
 * la plantilla cambie el correlativo siga siendo el mismo.
 *
 * <h2>El numero no es la clave de idempotencia, y conviene saberlo</h2>
 *
 * <p>El numero lo pone el sistema desde su correlativo —la pantalla lo pinta como campo de solo
 * lectura—, asi que <b>un reintento produciria un numero distinto</b> y no se podria reconocer como
 * repetido. Lo que hace idempotente el registro es la cabecera {@code idempotency-key} con {@code
 * anuncio_idempotencia_uq}, y lo que impide un segundo cargo es {@code
 * anuncio_movimiento_cargo_uq}. Confundir las dos cosas seria creer que un correlativo protege de
 * algo.
 *
 * @param plantilla el texto con sus marcas, {@code {ejercicio}} y {@code {correlativo[:N]}}
 */
public record PlantillaDeNumeroDeAnuncio(String plantilla) {

    /** {@code anuncio.numero varchar(20)} (V4). */
    public static final int NUMERO_MAXIMO = 20;

    private static final String MARCA_EJERCICIO = "{ejercicio}";

    private static final Pattern MARCA_CORRELATIVO = Pattern.compile("\\{correlativo(?::(\\d+))?}");

    /**
     * La plantilla por omision mientras D-09 no se cierre: {@code AN-2026-000001}.
     *
     * <p>TODO D-09: contrastar con las autorizaciones reales de la municipalidad piloto. Se elige
     * la forma que ya usan el valor ({@code OP-2026-000001}), el convenio, el expediente y la
     * licencia ({@code LF-2026-000001}): un formato distinto en el mismo sistema seria una
     * decision, y aqui no hay ninguna que tomar todavia.
     */
    public static final PlantillaDeNumeroDeAnuncio POR_OMISION =
            new PlantillaDeNumeroDeAnuncio("AN-{ejercicio}-{correlativo:6}");

    public PlantillaDeNumeroDeAnuncio {
        Objects.requireNonNull(plantilla, "La plantilla del numero es obligatoria (D-09)");
        if (!plantilla.contains(MARCA_EJERCICIO)) {
            throw new IllegalArgumentException(
                    "La plantilla tiene que llevar {ejercicio}: el correlativo se reinicia con el"
                            + " ejercicio, y sin el año dos autorizaciones de años distintos"
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
        Objects.requireNonNull(ejercicio, "Una autorizacion se numera dentro de un ejercicio");
        if (correlativo <= 0) {
            throw new IllegalArgumentException(
                    "El correlativo de una autorizacion empieza en 1; llego " + correlativo);
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
                            + " caracteres de anuncio.numero");
        }
        return numero;
    }
}
