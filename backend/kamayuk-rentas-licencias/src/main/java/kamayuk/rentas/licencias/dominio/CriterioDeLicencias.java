package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Por que se busca en la grilla de licencias y en su padron (#44, RF-110; #54, RF-115).
 *
 * <p>Son los filtros que declaran las pantallas {@code licencia_funcionamiento} y {@code
 * licencia_padron}. La mayoria son del propio contexto —numero, expediente, denominacion comercial,
 * direccion, tipo, CIIU, rango de fechas—; el <b>nombre del titular</b> no vive aqui: lo resuelve
 * {@code contribuyentes} y llega ya traducido a identificadores.
 *
 * <h2>Nulo y vacio no significan lo mismo en {@link #contribuyentes}</h2>
 *
 * <p>{@code null} es «no se filtro por titular». Un conjunto <b>vacio</b> es «se filtro y no hay
 * ningun contribuyente que se parezca», y entonces el resultado tiene que ser <b>ninguna
 * licencia</b>. Confundirlos es el defecto que la consulta de fichas ya cometio una vez y que su
 * prueba caza: buscar un nombre inexistente devolvia el padron entero.
 *
 * <h2>El estado NO esta aqui, y es deliberado</h2>
 *
 * <p>El estado de una licencia se <b>deriva</b> de sus movimientos y de la fecha a la que se
 * pregunte (V37 §1, {@link EstadoDeLicencia}), asi que no es un filtro sobre una columna sino una
 * condicion que depende de un segundo argumento: la fecha de corte. Meterlo en el criterio
 * significaria llevar la fecha dentro tambien, y entonces el mismo criterio con dos fechas seria
 * dos objetos distintos. Viaja aparte, junto a la fecha, igual que en {@code ConsultaDeFue} (#48).
 *
 * @param numero el numero de la licencia, exacto
 * @param expediente el numero de expediente del tramite, exacto
 * @param nombreComercial prefijo de la denominacion comercial
 * @param direccion prefijo de la direccion del establecimiento
 * @param tipo definitiva, temporal o cesionaria
 * @param ciiu el codigo CIIU de alguno de sus giros, exacto
 * @param desde solo las emitidas desde ese dia, inclusive
 * @param hasta solo las emitidas hasta ese dia, inclusive
 * @param contribuyentes los titulares que el nombre buscado resolvio; ver arriba
 */
public record CriterioDeLicencias(
        @Nullable String numero,
        @Nullable String expediente,
        @Nullable String nombreComercial,
        @Nullable String direccion,
        @Nullable TipoDeLicencia tipo,
        @Nullable String ciiu,
        @Nullable LocalDate desde,
        @Nullable LocalDate hasta,
        @Nullable Set<Long> contribuyentes) {

    public CriterioDeLicencias {
        numero = limpiar(numero);
        expediente = limpiar(expediente);
        nombreComercial = limpiar(nombreComercial);
        direccion = limpiar(direccion);
        ciiu = limpiar(ciiu);
        if (contribuyentes != null) {
            contribuyentes = Set.copyOf(contribuyentes);
        }
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new IllegalArgumentException(
                    "El intervalo va de "
                            + desde
                            + " a "
                            + hasta
                            + ", que termina antes de empezar: no encontraria nunca nada y nadie"
                            + " sabria por que");
        }
    }

    /** Sin ningun filtro: la grilla recien abierta. */
    public static CriterioDeLicencias ninguno() {
        return new CriterioDeLicencias(null, null, null, null, null, null, null, null, null);
    }

    /** El mismo criterio, con los titulares ya resueltos. */
    public CriterioDeLicencias conTitulares(Set<Long> encontrados) {
        return new CriterioDeLicencias(
                numero,
                expediente,
                nombreComercial,
                direccion,
                tipo,
                ciiu,
                desde,
                hasta,
                encontrados);
    }

    /** Se filtro por titular y no hay ninguno: no hay nada que consultar. */
    public boolean sinTitularPosible() {
        Set<Long> ids = contribuyentes;
        return ids != null && ids.isEmpty();
    }

    private static @Nullable String limpiar(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
