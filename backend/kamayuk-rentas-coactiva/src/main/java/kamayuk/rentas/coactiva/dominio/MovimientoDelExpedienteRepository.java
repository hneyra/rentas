package kamayuk.rentas.coactiva.dominio;

import java.util.List;
import java.util.Optional;

/**
 * El historial de un expediente coactivo (V33, #40).
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2). <b>No hay {@code actualizar} ni {@code
 * borrar}</b>: V33 le concede a {@code kamayuk_app} solo {@code SELECT} e {@code INSERT}. Un cambio
 * de estado equivocado se corrige registrando otro, y los dos quedan.
 */
public interface MovimientoDelExpedienteRepository {

    /**
     * Registra el movimiento.
     *
     * @throws AperturaDuplicada si el expediente ya tenia su apertura. Lo decide la base —{@code
     *     expediente_movimiento_apertura_uq}, V33—, no un {@code SELECT} previo
     */
    MovimientoDelExpediente registrar(MovimientoDelExpediente movimiento);

    /** Todo el historial del expediente, del primero al ultimo. Es de donde sale su estado. */
    List<MovimientoDelExpediente> deExpediente(long expedienteId);

    /**
     * El ultimo movimiento que cambio la direccion referencial, si lo hubo.
     *
     * <p>Existe para la pantalla {@code cambiar_direccion_ref}, que muestra «Dirección referencial
     * actual (expediente)» antes de dejar escribir la nueva.
     */
    Optional<MovimientoDelExpediente> ultimoCambioDeDireccion(long expedienteId);

    /** Ese expediente ya estaba abierto. */
    final class AperturaDuplicada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public AperturaDuplicada(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
