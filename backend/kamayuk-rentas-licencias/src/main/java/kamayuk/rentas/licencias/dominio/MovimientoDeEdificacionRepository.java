package kamayuk.rentas.licencias.dominio;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lo que le pasa a un FUE y los tramos de vigencia que sus actos conceden (#48, V43 §7 y §8).
 *
 * <p><b>Solo agrega.</b> V43 le concede a {@code kamayuk_app} nada mas que {@code SELECT} e {@code
 * INSERT} sobre las dos tablas: corregir un movimiento seria corregir la historia de un acto
 * administrativo ya notificado, y corregir una vigencia seria mover el plazo de una obra que ya
 * esta en marcha.
 */
public interface MovimientoDeEdificacionRepository {

    /**
     * Registra el movimiento.
     *
     * @throws YaEstabaEmitida si el expediente ya tenia su emision; lo decide {@code
     *     edificacion_movimiento_emision_uq}, no un {@code SELECT}
     * @throws NumeroDeLicenciaDuplicado si ese numero ya existe
     */
    MovimientoDeEdificacion registrar(MovimientoDeEdificacion movimiento);

    /**
     * Concede un tramo de vigencia a la licencia. El orden lo calcula el repositorio, y quien llama
     * tiene que haber tomado antes el candado de la licencia ({@link FueRepository#bloquear},
     * #427): el orden y el dia en que empieza el tramo se apoyan en los tramos que ya tiene.
     */
    VigenciaDeLaLicencia conceder(long licenciaId, long movimientoId, VigenciaDeLaLicencia tramo);

    /** Los movimientos de un expediente, ordenados por fecha. */
    List<MovimientoDeEdificacion> deExpediente(long fueId);

    /** Los movimientos de varios expedientes, en una consulta. */
    Map<Long, List<MovimientoDeEdificacion>> deExpedientes(Set<Long> fueIds);

    /** Los tramos de vigencia de una licencia, ordenados. */
    List<VigenciaDeLaLicencia> vigenciasDe(long licenciaId);

    /** Los tramos de vigencia de varias licencias, en una consulta. */
    Map<Long, List<VigenciaDeLaLicencia>> vigenciasDeVarias(Set<Long> licenciaIds);

    /** La emision de un expediente, si ya la tuvo. */
    Optional<MovimientoDeEdificacion> emisionDe(long fueId);

    /** La revalidacion de un expediente de revalidacion, si ya la tuvo (#449). */
    Optional<MovimientoDeEdificacion> revalidacionDe(long fueId);

    /** El expediente ya tenia su revalidacion: lo dice el indice, para la carrera (#449). */
    final class YaEstabaRevalidada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public YaEstabaRevalidada(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /** El expediente ya tenia su emision. */
    final class YaEstabaEmitida extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public YaEstabaEmitida(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /** Ese numero de licencia ya lo lleva otro expediente. */
    final class NumeroDeLicenciaDuplicado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public NumeroDeLicenciaDuplicado(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
