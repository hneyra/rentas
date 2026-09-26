package kamayuk.rentas.fiscalizacion.dominio;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;

/**
 * Lo hallado que la transferencia inscribe en el padron: el area y el uso de la LIQUIDACION que se
 * transfiere, de la linea de su ultimo ejercicio (#370).
 *
 * <p>Hasta #370 el area salia del ACTA y el uso de la liquidacion. Una reliquidacion existe
 * justamente para corregir un area mal medida, y esa correccion vive en las lineas de la version
 * nueva —el acta no cambia (regla 4)—: el padron habria quedado con la medida que la propia
 * administracion declaro erronea, y la resolucion habria impreso una cifra distinta de la de la
 * liquidacion que resuelve. Y con varias lineas el uso salia de la PRIMERA, o sea del ejercicio mas
 * antiguo, cuando la version del padron rige desde hoy.
 *
 * <p>Por eso los dos datos salen de la misma linea: la del ejercicio mas reciente, que es la que
 * describe el predio tal como se inscribe ahora. Es una funcion pura.
 *
 * @param area el area hallada de esa linea; nula si no la consigno
 * @param uso el uso hallado de esa linea; nulo si no lo consigno, y entonces la version nueva
 *     conserva el que tenia
 */
public record LoHalladoQueSeInscribe(@Nullable AreaM2 area, @Nullable String uso) {

    /** Lo de la linea del ultimo ejercicio; nada si la liquidacion no tiene lineas. */
    public static LoHalladoQueSeInscribe de(List<LineaDeLiquidacion> lineas) {
        Objects.requireNonNull(lineas, "Hacen falta las lineas de la liquidacion");
        return lineas.stream()
                .max(Comparator.comparing(linea -> linea.ejercicio().valor()))
                .map(
                        ultima ->
                                new LoHalladoQueSeInscribe(
                                        ultima.areaHallada(), ultima.usoHallado()))
                .orElse(new LoHalladoQueSeInscribe(null, null));
    }
}
