package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Por que se busca en la grilla de anuncios y en su padron (#51, RF-114).
 *
 * <p>Cubre los filtros de la pantalla {@code anuncios} —numero de autorizacion, expediente,
 * direccion y contribuyente— y los dos que la pantalla {@code anuncios_reportes} agrega: el
 * intervalo de fechas de autorizacion. El contribuyente, el R.U.C. y el D.N.I. de la pantalla no
 * viven aqui: los resuelve {@code contribuyentes} y llegan ya traducidos a identificadores.
 *
 * <h2>Nulo y vacio no significan lo mismo en {@link #contribuyentes}</h2>
 *
 * <p>{@code null} es «no se filtro por titular». Un conjunto <b>vacio</b> es «se filtro y no hay
 * ningun contribuyente que se parezca», y entonces el resultado tiene que ser <b>ningun
 * anuncio</b>. Confundirlos es el defecto que la consulta de fichas ya cometio una vez y que #44
 * volvio a cazar: buscar un nombre inexistente devolvia el padron entero.
 *
 * @param numero el numero de la autorizacion, exacto
 * @param expediente prefijo del numero de expediente del tramite
 * @param direccion prefijo de la direccion donde esta instalado
 * @param clase la clase del elemento, exacta
 * @param desde solo autorizaciones desde ese dia, inclusive
 * @param hasta solo autorizaciones hasta ese dia, inclusive
 * @param contribuyentes los titulares que el nombre buscado resolvio; ver arriba
 */
public record CriterioDeAnuncios(
        @Nullable String numero,
        @Nullable String expediente,
        @Nullable String direccion,
        @Nullable ClaseDeAnuncio clase,
        @Nullable LocalDate desde,
        @Nullable LocalDate hasta,
        @Nullable Set<Long> contribuyentes) {

    public CriterioDeAnuncios {
        numero = limpiar(numero);
        expediente = limpiar(expediente);
        direccion = limpiar(direccion);
        if (contribuyentes != null) {
            contribuyentes = Set.copyOf(contribuyentes);
        }
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new IllegalArgumentException(
                    "El intervalo del padron termina antes de empezar: " + desde + " .. " + hasta);
        }
    }

    /** Sin ningun filtro: la grilla recien abierta. */
    public static CriterioDeAnuncios ninguno() {
        return new CriterioDeAnuncios(null, null, null, null, null, null, null);
    }

    /** El mismo criterio con los titulares ya resueltos. */
    public CriterioDeAnuncios conTitulares(@Nullable Set<Long> titulares) {
        return new CriterioDeAnuncios(
                numero, expediente, direccion, clase, desde, hasta, titulares);
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
