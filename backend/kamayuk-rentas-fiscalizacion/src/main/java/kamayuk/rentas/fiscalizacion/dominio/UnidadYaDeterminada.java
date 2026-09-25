package kamayuk.rentas.fiscalizacion.dominio;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * La unidad ya tiene una determinacion de oficio viva en alguno de esos ejercicios (#462).
 *
 * <p>Es una <i>Specification</i>: una regla con nombre que se evalua sobre una resolucion de
 * determinacion ya emitida y se cumple o no. Si se cumple para alguna, emitir otra sobre la misma
 * unidad y un ejercicio comun es un <b>segundo acto de determinacion</b> sobre la misma obligacion
 * —con su propio plazo de reclamacion, su propio papel y, el dia que las lineas traigan cifras, sus
 * propios cargos en el libro—, y {@code TransferirARentas} lo rechaza nombrando la resolucion que
 * ya la determina.
 *
 * <h2>Por que hacia falta: la clave estaba en el documento, no en lo determinado</h2>
 *
 * <p>Hasta #462 la unica barrera era {@code resolucion_determinacion_liquidacion_uq}, que es <b>por
 * liquidacion</b>. Pero a la misma unidad y los mismos ejercicios se llega con otra liquidacion por
 * dos caminos: reliquidar la ya transferida —la v2 es otra fila— y liquidar la segunda visita al
 * mismo vehiculo —otra acta, otra liquidacion—. Las dos se transferian, y quedaban dos RDF vigentes
 * sobre lo mismo. La clave buena es la que {@code EstadoDeCuentaDeFiscalizacion} ya usa para no
 * contar dos veces una obligacion: el ejercicio y la unidad.
 *
 * <h2>Por unidad Y ejercicio, no por unidad</h2>
 *
 * <p>Rechazar cualquier segunda RDF de la unidad seria mas simple y estaria mal: la visita de 2026
 * a un vehiculo fiscalizado por 2024 determina otro ejercicio, y es otra obligacion. Los periodos
 * de una liquidacion son un intervalo cerrado de ejercicios —una linea por cada uno, sin huecos—,
 * asi que «algun ejercicio en comun» es que los dos intervalos se solapen.
 *
 * <h2>Pura, y solo vale despues del candado</h2>
 *
 * <p>No lee nada (regla 6): recibe las resoluciones de la unidad que {@link
 * ResolucionDeDeterminacionRepository#vigentesSobreLaUnidad} devuelve y decide. Evaluada a secas,
 * la regla la pasan las dos peticiones simultaneas —ninguna ve la resolucion de la otra hasta que
 * confirma—, asi que quien la evalua toma antes {@link
 * ResolucionDeDeterminacionRepository#bloquearLaUnidad} en la misma transaccion. Es el mismo
 * reparto que {@code ObligacionYaFormalizada} (#366) en {@code valores}.
 *
 * @param predioId la unidad, si es predial
 * @param vehiculoId la unidad, si es vehicular
 * @param desde primer ejercicio que se quiere determinar
 * @param hasta ultimo ejercicio que se quiere determinar
 */
public record UnidadYaDeterminada(
        @Nullable Long predioId, @Nullable Long vehiculoId, Ejercicio desde, Ejercicio hasta) {

    public UnidadYaDeterminada {
        if ((predioId == null) == (vehiculoId == null)) {
            throw new IllegalArgumentException(
                    "Se determina un predio o un vehiculo, nunca los dos ni ninguno");
        }
        Objects.requireNonNull(desde, "La regla necesita el primer ejercicio");
        Objects.requireNonNull(hasta, "La regla necesita el ultimo ejercicio");
        if (desde.compareTo(hasta) > 0) {
            throw new IllegalArgumentException(
                    "El periodo empieza en " + desde + " y termina antes, en " + hasta);
        }
    }

    /** La regla para lo que una liquidacion determina: la unidad de su acta y su periodo. */
    public static UnidadYaDeterminada para(ActaFiscalizacion acta, Liquidacion liquidacion) {
        return new UnidadYaDeterminada(
                acta.predioId(),
                acta.vehiculoId(),
                liquidacion.ejercicioDesde(),
                liquidacion.ejercicioHasta());
    }

    /**
     * Si esa resolucion ya determina esta unidad en algun ejercicio de este periodo.
     *
     * @param resolucion una resolucion emitida, con el periodo de la liquidacion que la sostiene
     */
    public boolean esSatisfechaPor(ResolucionEnLaRelacion resolucion) {
        return mismaUnidad(resolucion) && solapa(resolucion);
    }

    /**
     * La primera resolucion que ya determina lo mismo, si la hay. Si la hay, la regla se cumple y
     * emitir otra seria determinar dos veces la misma obligacion.
     *
     * @param deLaUnidad las resoluciones vivas de la unidad, de la primera a la ultima
     */
    public Optional<ResolucionEnLaRelacion> laQueYaDetermina(
            List<ResolucionEnLaRelacion> deLaUnidad) {
        return deLaUnidad.stream().filter(this::esSatisfechaPor).findFirst();
    }

    private boolean mismaUnidad(ResolucionEnLaRelacion resolucion) {
        return Objects.equals(predioId, resolucion.predioId())
                && Objects.equals(vehiculoId, resolucion.vehiculoId());
    }

    /** Dos intervalos cerrados se solapan si ninguno termina antes de que empiece el otro. */
    private boolean solapa(ResolucionEnLaRelacion resolucion) {
        return resolucion.periodoDesde().compareTo(hasta) <= 0
                && desde.compareTo(resolucion.periodoHasta()) <= 0;
    }
}
