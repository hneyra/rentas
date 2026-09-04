package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Por que se busca en la grilla «Certificados emitidos» (#54, RF-115).
 *
 * <p>Son los tres filtros que la pantalla {@code certificados} declara —numero, tipo y predio— mas
 * el rango de fechas con que el padron acota lo que imprime.
 *
 * <h2>Nulo y vacio no significan lo mismo en {@link #solicitantes}</h2>
 *
 * <p>{@code null} es «no se filtro por solicitante». Un conjunto <b>vacio</b> es «se filtro y no
 * hay ningun contribuyente que se parezca», y entonces el resultado tiene que ser <b>ningun
 * certificado</b>. Confundirlos es el defecto que la consulta de fichas ya cometio una vez y que
 * #44 volvio a cazar: buscar un nombre inexistente devolvia el padron entero.
 *
 * @param numero el numero del certificado, exacto
 * @param tipo la clase de certificado
 * @param codigoPredial prefijo del codigo de referencia catastral del predio
 * @param desde solo los emitidos desde ese dia, inclusive
 * @param hasta solo los emitidos hasta ese dia, inclusive
 * @param solicitantes los titulares que el nombre buscado resolvio; ver arriba
 */
public record CriterioDeCertificados(
        @Nullable String numero,
        @Nullable TipoDeCertificado tipo,
        @Nullable String codigoPredial,
        @Nullable LocalDate desde,
        @Nullable LocalDate hasta,
        @Nullable Set<Long> solicitantes) {

    public CriterioDeCertificados {
        numero = limpiar(numero);
        codigoPredial = limpiar(codigoPredial);
        if (solicitantes != null) {
            solicitantes = Set.copyOf(solicitantes);
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
    public static CriterioDeCertificados ninguno() {
        return new CriterioDeCertificados(null, null, null, null, null, null);
    }

    /** El mismo criterio, con los solicitantes ya resueltos. */
    public CriterioDeCertificados conSolicitantes(Set<Long> encontrados) {
        return new CriterioDeCertificados(numero, tipo, codigoPredial, desde, hasta, encontrados);
    }

    /** Se filtro por solicitante y no hay ninguno: no hay nada que consultar. */
    public boolean sinSolicitantePosible() {
        Set<Long> ids = solicitantes;
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
