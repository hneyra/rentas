package kamayuk.rentas.fiscalizacion.dominio;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Cómo se compone el «Nº Liquidación» impreso.
 *
 * <p>Es un <b>parámetro</b> y no un formato escrito dentro del código porque <b>D-09 sigue
 * abierta</b>: con qué formato y con qué reinicio se numeran valores y expedientes. Mismo
 * precedente que {@code PlantillaDeNumeroDeExpediente} (#40) y {@code ComposicionCatastral} para
 * D-10.
 *
 * <p>El esquema tomó la misma precaución: {@code liquidacion_fiscalizacion.correlativo} guarda el
 * entero desnudo <b>además</b> del número impreso, de modo que el día que la plantilla cambie el
 * correlativo siga siendo el mismo.
 *
 * <p>Solo <b>compone</b>, al revés que la del expediente, que además analiza. La diferencia no es
 * pereza: el número de expediente llega por la <i>ruta</i> HTTP y hay que traducirlo a ejercicio y
 * correlativo para buscarlo; el de liquidación llega como <i>filtro</i> y se busca por igualdad
 * exacta sobre la columna, que es lo que {@code liquidacion_numero_uq} indexa. Escribir un
 * analizador que nadie usa sería escribir un segundo formato que un día difiere del primero.
 *
 * @param plantilla el texto con sus marcas {@code {ejercicio}} y {@code {correlativo:N}}
 */
public record PlantillaDeNumeroDeLiquidacion(String plantilla) {

    /** {@code liquidacion_fiscalizacion.numero varchar(40)} (V39). */
    public static final int NUMERO_MAXIMO = 40;

    private static final String MARCA_EJERCICIO = "{ejercicio}";

    private static final String MARCA_CORRELATIVO = "{correlativo:6}";

    /**
     * La plantilla por omisión mientras D-09 no se cierre: {@code LIQ-2026-000001}.
     *
     * <p>TODO D-09: contrastar con las liquidaciones reales de la municipalidad piloto. Se elige
     * ésta por simetría con el valor ({@code OP-2026-000001}), el convenio ({@code F-2026-000123})
     * y el expediente ({@code EXP-2026-000001}); <b>ninguna está verificada contra el piloto</b>, y
     * por eso las cuatro son cambiables sin migrar código.
     */
    public static final PlantillaDeNumeroDeLiquidacion POR_OMISION =
            new PlantillaDeNumeroDeLiquidacion("LIQ-" + MARCA_EJERCICIO + "-" + MARCA_CORRELATIVO);

    public PlantillaDeNumeroDeLiquidacion {
        Objects.requireNonNull(plantilla, "La plantilla del numero es obligatoria (D-09)");
        if (!plantilla.contains(MARCA_EJERCICIO)) {
            throw new IllegalArgumentException(
                    "La plantilla tiene que llevar {ejercicio}: el correlativo se reinicia con el"
                            + " ejercicio, y sin el año dos liquidaciones de años distintos"
                            + " compartirian numero");
        }
        if (!plantilla.contains(MARCA_CORRELATIVO)) {
            throw new IllegalArgumentException("La plantilla tiene que llevar {correlativo:6}");
        }
    }

    /** El número impreso de ese correlativo en ese ejercicio. */
    public String componer(Ejercicio ejercicio, long correlativo) {
        Objects.requireNonNull(ejercicio, "Una liquidacion se numera dentro de un ejercicio");
        if (correlativo <= 0) {
            throw new IllegalArgumentException(
                    "El correlativo de una liquidacion empieza en 1; llego " + correlativo);
        }
        String numero =
                plantilla
                        .replace(MARCA_EJERCICIO, String.valueOf(ejercicio.valor()))
                        .replace(
                                MARCA_CORRELATIVO, String.format(Locale.ROOT, "%06d", correlativo));
        if (numero.length() > NUMERO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero '"
                            + numero
                            + "' excede los "
                            + NUMERO_MAXIMO
                            + " caracteres de liquidacion_fiscalizacion.numero");
        }
        return numero;
    }
}
