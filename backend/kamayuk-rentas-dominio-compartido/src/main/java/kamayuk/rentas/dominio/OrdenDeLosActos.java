package kamayuk.rentas.dominio;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * <b>Un acto no se fecha antes del acto que resuelve ni después de hoy</b> (#402).
 *
 * <p>Es una <i>Specification</i> del dominio, y está aquí y no en cada caso de uso porque tiene su
 * propio motivo de cambio: hasta #402 la misma regla vivía en seis sitios con cinco excepciones
 * distintas —{@code AnteriorALaDeclaracion}, {@code AnteriorALaEmision}, {@code
 * AnteriorALaAutorizacion}, {@code DiligenciaAnteriorALaResolucion} y {@code
 * DiligenciaAnteriorALaEmision}—, todas cotas <b>inferiores</b>, y ninguna miraba hoy. Y faltaba,
 * entera o a medias, en once actos más, con dos vivos: la anulación de una papeleta fechada antes
 * de su infracción la dejaba {@code ANULADA} y debiendo —la baja no veía el cargo, escribía cero
 * asientos y confirmaba—, y un pase a coactiva o una prescripción fechados en el futuro llevaban
 * hoy el valor a {@code COACTIVA} o {@code PRESCRITO}, en tablas que no admiten corrección.
 *
 * <p>Un {@code CHECK} no puede expresarla —no compara con el día ni con una fila de otra tabla— y
 * los controladores sólo validan el formato. Queda el caso de uso, que es quien conoce los actos
 * previos, y esta clase, que es quien decide qué significa que estén en orden.
 *
 * <h2>Pura (regla 6)</h2>
 *
 * <p>{@code hoy} entra como argumento: {@code LocalDate.now(reloj)} en el caso de uso, que desde
 * #316 es el día de la municipalidad y no el del sistema operativo. Así la regla se prueba sin
 * reloj, y la frontera —el mismo día, que vale— se escribe con fechas fijas.
 *
 * <h2>El mismo día vale</h2>
 *
 * <p>Una anulación el mismo día de la infracción, una diligencia el mismo día de la resolución o un
 * pase el mismo día en que se registra son actos corrientes. Lo que no vale es <b>antes</b> ni
 * <b>después</b>: la comparación es estricta en los dos lados.
 */
public final class OrdenDeLosActos {

    private OrdenDeLosActos() {}

    /**
     * Exige que {@code fechaDelActo} no sea anterior a ninguno de los {@code previos} ni posterior
     * a {@code hoy}.
     *
     * @param acto qué acto se fecha, como lo lee quien opera: «la anulacion de la papeleta T-003»
     * @param fechaDelActo la fecha que tecleó el operador
     * @param hoy el día de la municipalidad, del reloj inyectado (regla 6)
     * @param previos los actos que éste resuelve, con su fecha
     * @throws ActoFueraDeOrden si es anterior a alguno de ellos, nombrando el más reciente de los
     *     que incumple, o si es posterior a hoy
     */
    public static void exigir(
            String acto, LocalDate fechaDelActo, LocalDate hoy, ActoPrevio... previos) {
        exigir(acto, fechaDelActo, hoy, Arrays.asList(previos));
    }

    /**
     * La misma regla con los previos en una lista, para el acto cuyo previo existe o no según el
     * caso —la resolución de gerencia resuelve un descargo sólo si lo hay—.
     *
     * @see #exigir(String, LocalDate, LocalDate, ActoPrevio...)
     */
    public static void exigir(
            String acto, LocalDate fechaDelActo, LocalDate hoy, List<ActoPrevio> previos) {
        Objects.requireNonNull(acto, "Hay que decir que acto se fecha");
        Objects.requireNonNull(fechaDelActo, "La fecha del acto entra como argumento (regla 6)");
        Objects.requireNonNull(hoy, "Hoy entra como argumento (regla 6)");
        Objects.requireNonNull(previos, "Los actos previos son una lista, vacia si no hay");

        // El mas reciente de los que incumple: es la cota que quien opera tiene que respetar, y
        // nombrar otro le haria corregir la fecha dos veces.
        ActoPrevio incumplido = null;
        for (ActoPrevio previo : previos) {
            Objects.requireNonNull(previo, "Un acto previo que no existe no se pasa");
            if (fechaDelActo.isBefore(previo.fecha())
                    && (incumplido == null || previo.fecha().isAfter(incumplido.fecha()))) {
                incumplido = previo;
            }
        }
        if (incumplido != null) {
            throw ActoFueraDeOrden.anteriorA(acto, fechaDelActo, incumplido);
        }
        if (fechaDelActo.isAfter(hoy)) {
            throw ActoFueraDeOrden.posteriorAHoy(acto, fechaDelActo, hoy);
        }
    }

    /**
     * Un acto que el que se fecha resuelve: qué es y cuándo fue.
     *
     * @param nombre como lo lee quien opera: «la infraccion de la papeleta T-003», «el ultimo
     *     movimiento del anuncio AN-2026-000001 (Renovacion de anuncio)»
     * @param fecha el día de ese acto
     */
    public record ActoPrevio(String nombre, LocalDate fecha) {

        public ActoPrevio {
            Objects.requireNonNull(nombre, "El acto previo se nombra: el mensaje lo dice");
            Objects.requireNonNull(fecha, "El acto previo tiene su fecha");
        }
    }
}
