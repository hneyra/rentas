package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Donde empieza el tramo de vigencia que concede una revalidacion (#451).
 *
 * <p>Es una regla de fechas pura (reglas 6 y 7): la fecha del acto entra como argumento, y no hay
 * ni base, ni reloj, ni Spring. Vive junto a {@link VigenciaDeLaLicencia} y no en linea dentro del
 * caso de uso porque es el unico sitio donde se sostiene el invariante <b>«ningun tramo empieza
 * antes del acto que lo concede»</b>.
 *
 * <h2>Por que ese invariante no se puede sostener en otro sitio</h2>
 *
 * <p>{@link EstadoDelFue#derivarDe} descarta los movimientos posteriores a la fecha preguntada,
 * pero no los tramos: responde {@link VigenciaDeLaLicencia#cubre}, y el tramo no guarda la fecha
 * del acto que lo concedio. Si un tramo empieza antes de su acto, un reporte con fecha de corte en
 * ese hueco dice VIGENTE al reimprimirse, y {@code edificacion_vigencia} solo admite {@code INSERT}
 * (regla 4): el tramo equivocado ya no se corrige.
 *
 * <h2>Por que el tramo nuevo empieza donde empieza</h2>
 *
 * <p>En {@code max(ultimoHasta + 1, fechaDelActo)}:
 *
 * <ul>
 *   <li>el dia siguiente al ultimo tramo, si la licencia todavia esta vigente el dia del acto: si
 *       empezara el dia del acto, dos tramos se solaparian y la licencia diria estar vigente dos
 *       veces el mismo dia;
 *   <li>el dia del acto, si la licencia ya habia vencido —que es el caso normal: «una vencida se
 *       revalida, una anulada no»—. Hasta #451 empezaba igual el dia siguiente al vencimiento, y la
 *       revalidacion de septiembre dejaba la licencia vigente desde abril: una regularizacion de la
 *       obra hecha en el hueco sin su tramite ni su derecho.
 * </ul>
 *
 * <p>Si la norma dijera que la revalidacion corre desde el vencimiento, lo que tendria que cambiar
 * —con su fuente— es el contrato de {@link EstadoDelFue#derivarDe}, que hoy promete lo contrario.
 */
public final class TramosDeVigencia {

    private TramosDeVigencia() {}

    /**
     * El tramo que concede un acto fechado {@code fechaDelActo} sobre los tramos que la licencia ya
     * tiene.
     *
     * @param anteriores los tramos de la licencia, en cualquier orden
     * @param fechaDelActo el dia de la resolucion que concede el tramo (regla 6)
     * @param hasta el ultimo dia del tramo, tal como lo fija el acto
     * @return el tramo, todavia sin licencia ni acto: los pone quien lo registra
     * @throws ProrrogaQueNoLlegaAlActo si {@code hasta} es anterior al primer dia del tramo
     */
    public static TramoSiguiente siguienteTramo(
            List<VigenciaDeLaLicencia> anteriores, LocalDate fechaDelActo, LocalDate hasta) {

        Objects.requireNonNull(anteriores, "La lista de tramos es vacia, no nula");
        Objects.requireNonNull(fechaDelActo, "La fecha del acto entra como argumento (regla 6)");
        Objects.requireNonNull(hasta, "El acto dice hasta cuando rige el tramo");

        // max(ultimoHasta + 1, fechaDelActo). Sin tramos anteriores manda el acto: no hay ninguno
        // con el que solaparse.
        LocalDate desde =
                anteriores.stream()
                        .map(tramo -> tramo.hasta().plusDays(1))
                        .filter(siguienteAlTramo -> siguienteAlTramo.isAfter(fechaDelActo))
                        .max(LocalDate::compareTo)
                        .orElse(fechaDelActo);

        if (hasta.isBefore(desde)) {
            throw new ProrrogaQueNoLlegaAlActo(fechaDelActo, desde, hasta);
        }
        return new TramoSiguiente(anteriores.size() + 1, desde, hasta);
    }

    /**
     * El tramo nuevo, con su lugar y sus dos fechas, antes de saber que acto lo concede.
     *
     * @param orden el que le toca: uno mas que los que ya habia
     * @param desde el primer dia
     * @param hasta el ultimo
     */
    public record TramoSiguiente(int orden, LocalDate desde, LocalDate hasta) {

        public TramoSiguiente {
            Objects.requireNonNull(desde, "desde");
            Objects.requireNonNull(hasta, "hasta");
        }

        /**
         * El tramo como fila de {@code edificacion_vigencia}, concedido a esa licencia por ese
         * acto.
         */
        public VigenciaDeLaLicencia concedidoPor(long licenciaId, long movimientoId) {
            return new VigenciaDeLaLicencia(null, licenciaId, movimientoId, orden, desde, hasta);
        }
    }

    /**
     * El acto fija un fin de tramo anterior al dia en que el tramo empezaria.
     *
     * <p>Con la licencia vencida, el tramo empieza el dia del acto: un {@code hasta} anterior es
     * una revalidacion que solo autoriza el pasado, y el administrado habria pagado el derecho por
     * una licencia que el mismo dia de la resolucion ya esta vencida. Es la excepcion con nombre en
     * lugar del {@link IllegalArgumentException} generico de {@link VigenciaDeLaLicencia}.
     */
    public static final class ProrrogaQueNoLlegaAlActo extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ProrrogaQueNoLlegaAlActo(LocalDate fechaDelActo, LocalDate desde, LocalDate hasta) {
            super(
                    "El acto del "
                            + fechaDelActo
                            + " concede un tramo que empezaria el "
                            + desde
                            + " y lo hace terminar el "
                            + hasta
                            + ": una revalidacion que no llega al dia en que se concede solo"
                            + " autoriza el pasado, y la licencia estaria vencida el mismo dia de"
                            + " su resolucion");
        }
    }
}
