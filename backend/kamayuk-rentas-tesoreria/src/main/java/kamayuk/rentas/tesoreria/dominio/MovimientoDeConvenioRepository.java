package kamayuk.rentas.tesoreria.dominio;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Los movimientos de un convenio (V31, #35). Ningun metodo recibe la municipalidad (regla 2): la
 * filtra la politica RLS.
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}</b>, y no es un olvido: V31 le concede a {@code
 * sgtm_app} solo {@code SELECT} e {@code INSERT}. Lo que le pasa a un convenio se agrega.
 */
public interface MovimientoDeConvenioRepository {

    /**
     * Registra el movimiento.
     *
     * @param claveDeIdempotencia la cabecera {@code Idempotency-Key} del intento, si vino. Solo la
     *     traen los tres cierres: la formalizacion entra por la caja y la protege {@code
     *     recibo_idempotencia_uq} (V29)
     * @throws ClaveRepetida si esa clave ya registro otro movimiento. Lo decide {@code
     *     convenio_movimiento_idempotencia_uq} (V70)
     * @throws ConvenioYaFormalizado si el convenio ya tenia su formalizacion
     * @throws ConvenioYaCerrado si el convenio ya estaba anulado, quebrado o reformulado. Los dos
     *     los decide la base —{@code convenio_movimiento_formalizacion_uq} y {@code
     *     convenio_movimiento_cierre_uq}—, no un {@code SELECT} previo: dos peticiones simultaneas
     *     pasarian las dos por cualquier comprobacion escrita en Java, y cerrar dos veces
     *     devolveria la deuda dos veces a su fase de origen
     */
    MovimientoDeConvenio registrar(
            MovimientoDeConvenio movimiento, @Nullable String claveDeIdempotencia);

    /**
     * El movimiento que se registro con esa clave de idempotencia, si ya existe.
     *
     * <p>Es lo que convierte el reenvio de una anulacion en un 201 con el convenio ya cerrado, en
     * vez del 409 que contestaba {@code convenio_movimiento_cierre_uq} —que se lee como un fallo
     * nuevo y no como «ya estaba hecho»— (#606). La garantia sigue siendo el indice.
     */
    Optional<MovimientoDeConvenio> porClaveDeIdempotencia(String clave);

    /** Todos los movimientos del convenio, del primero al ultimo. Es de donde sale su estado. */
    List<MovimientoDeConvenio> deConvenio(long convenioId);

    /** La formalizacion de ese convenio, si la hubo. */
    Optional<MovimientoDeConvenio> formalizacionDe(long convenioId);

    /** El cierre de ese convenio, si lo hubo: su anulacion, su quiebre o su reformulacion. */
    Optional<MovimientoDeConvenio> cierreDe(long convenioId);

    /**
     * Ese convenio ya estaba formalizado.
     *
     * <p>No es un reenvio inocente que deba devolver lo de la primera vez: una segunda
     * formalizacion acogeria la deuda otra vez y la dejaria contada dos veces en fase de convenio.
     * Quien llama responde 409.
     */
    final class ConvenioYaFormalizado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ConvenioYaFormalizado(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /**
     * Ese convenio ya estaba cerrado.
     *
     * <p>Anular lo que ya se quebro —o al reves— devolveria la deuda dos veces a su fase de origen,
     * y el contribuyente acabaria debiendo el doble. Quien llama responde 409.
     */
    final class ConvenioYaCerrado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ConvenioYaCerrado(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /**
     * Esa clave de idempotencia ya registro un movimiento.
     *
     * <p>No es el reenvio que el caso de uso atiende leyendo primero, sino la <b>carrera</b>: dos
     * envios del mismo intento que pasan los dos por el {@code SELECT}. La segunda no puede
     * devolver el acta de la primera porque todavia no esta confirmada. Quien llama responde 409.
     */
    final class ClaveRepetida extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ClaveRepetida(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
