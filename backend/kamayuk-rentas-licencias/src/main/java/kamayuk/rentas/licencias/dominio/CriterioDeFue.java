package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Por que se busca en la grilla del FUE y en el reporte general (#48, RF-113, RF-115).
 *
 * <p>Son los seis filtros que declara la pantalla {@code fue_edificacion} —expediente, numero de
 * licencia, nombre del contribuyente, manzana, lote y tipo de tramite— y los cuatro de {@code
 * edificacion_reporte} —desde, hasta, modalidad y estado—. El estado no esta aqui: <b>se deriva</b>
 * y se filtra despues de derivarlo, porque no es una columna que se pueda meter en un {@code
 * WHERE}.
 *
 * <h2>Nulo y vacio no significan lo mismo en {@link #contribuyentes}</h2>
 *
 * <p>{@code null} es «no se filtro por titular». Un conjunto <b>vacio</b> es «se filtro y no hay
 * ningun contribuyente que se parezca», y entonces el resultado tiene que ser <b>ningun
 * expediente</b>. Confundirlos es el defecto que la consulta de fichas ya cometio una vez y que la
 * de licencias de funcionamiento caza desde #44: buscar un nombre inexistente devolvia el padron
 * entero.
 *
 * @param expediente el numero de expediente, exacto
 * @param numeroLicencia el numero de la licencia otorgada, exacto
 * @param manzana prefijo de la manzana del terreno
 * @param lote prefijo del lote
 * @param tipoTramite cual de los cinco tramites
 * @param modalidad la modalidad de aprobacion
 * @param desde el primer dia de declaracion admitido
 * @param hasta el ultimo
 * @param contribuyentes los titulares que el nombre buscado resolvio; ver arriba
 */
public record CriterioDeFue(
        @Nullable String expediente,
        @Nullable String numeroLicencia,
        @Nullable String manzana,
        @Nullable String lote,
        @Nullable TipoDeTramiteDeEdificacion tipoTramite,
        @Nullable ModalidadDeAprobacion modalidad,
        @Nullable LocalDate desde,
        @Nullable LocalDate hasta,
        @Nullable Set<Long> contribuyentes) {

    public CriterioDeFue {
        expediente = limpiar(expediente);
        numeroLicencia = limpiar(numeroLicencia);
        manzana = limpiar(manzana);
        lote = limpiar(lote);
        if (contribuyentes != null) {
            contribuyentes = Set.copyOf(contribuyentes);
        }
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new IllegalArgumentException(
                    "El rango de fechas termina el "
                            + hasta
                            + ", antes de empezar el "
                            + desde
                            + ": asi no puede devolver nunca nada, y quien lo tecleo creeria que no"
                            + " hay expedientes");
        }
    }

    /** Sin ningun filtro: la grilla recien abierta. */
    public static CriterioDeFue ninguno() {
        return new CriterioDeFue(null, null, null, null, null, null, null, null, null);
    }

    /** Se filtro por titular y no hay ninguno: no hay nada que consultar. */
    public boolean sinTitularPosible() {
        Set<Long> ids = contribuyentes;
        return ids != null && ids.isEmpty();
    }

    /** El mismo criterio con los titulares ya resueltos. */
    public CriterioDeFue conTitulares(Set<Long> resueltos) {
        return new CriterioDeFue(
                expediente,
                numeroLicencia,
                manzana,
                lote,
                tipoTramite,
                modalidad,
                desde,
                hasta,
                resueltos);
    }

    private static @Nullable String limpiar(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
