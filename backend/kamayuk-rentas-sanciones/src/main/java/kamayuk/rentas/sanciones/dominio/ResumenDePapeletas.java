package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Un resumen de papeletas entero, con su fecha (#53, RF-073).
 *
 * <p>{@link #aLaFecha} no es decorativa (regla 9, RNF-075): un resumen dice cuántas papeletas
 * constaban pagadas <b>ese día</b>, y mañana son otras. Un resumen archivado sin su fecha no se
 * puede volver a comparar con nada.
 *
 * <p>Una lista vacía no es un error: un mes sin una sola papeleta tiene su resumen en cero.
 *
 * @param lineas una por grupo con papeletas
 * @param agrupacion por qué se agruparon
 * @param desde primer día de infracción que entró, si se acotó
 * @param hasta último, si se acotó
 * @param aLaFecha el día al que se leyeron los estados
 */
public record ResumenDePapeletas(
        List<LineaDelResumen> lineas,
        AgrupacionDelResumen agrupacion,
        LocalDate desde,
        LocalDate hasta,
        LocalDate aLaFecha) {

    public ResumenDePapeletas {
        Objects.requireNonNull(lineas, "La lista es vacia, no nula");
        Objects.requireNonNull(agrupacion, "El resumen dice por que agrupa");
        Objects.requireNonNull(desde, "El resumen dice desde cuando cuenta");
        Objects.requireNonNull(hasta, "El resumen dice hasta cuando cuenta");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException("«hasta» no puede ser anterior a «desde»");
        }
        lineas = List.copyOf(lineas);
    }

    /** Cuántas papeletas cubre el resumen entero. */
    public long total() {
        long total = 0;
        for (LineaDelResumen linea : lineas) {
            total += linea.cantidad();
        }
        return total;
    }

    /** La suma de los importes de acta de todas las líneas. */
    public Dinero importeTotal() {
        Dinero total = Dinero.CERO;
        for (LineaDelResumen linea : lineas) {
            total = total.mas(linea.importe());
        }
        return total;
    }
}
