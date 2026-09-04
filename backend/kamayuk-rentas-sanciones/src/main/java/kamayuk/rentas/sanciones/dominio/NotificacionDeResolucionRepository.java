package kamayuk.rentas.sanciones.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Las notificaciones de resoluciones de gerencia contra PostgreSQL: la rebanada {@code objeto =
 * 'RESOLUCION'} de {@code notificacion} (V3 + V28). Ningún método recibe la municipalidad (regla
 * 2).
 *
 * <p>No hay {@code actualizar} ni {@code borrar}, y tampoco existe el privilegio: V28 le revoca el
 * {@code UPDATE} a {@code sgtm_app}. Un intento no hallado se reintenta con otra fila, y la
 * anterior se queda donde estaba.
 */
public interface NotificacionDeResolucionRepository {

    NotificacionDeResolucion insertar(NotificacionDeResolucion notificacion);

    /** Las diligencias de una resolución, por intento. */
    List<NotificacionDeResolucion> deResolucion(long resolucionId);

    /**
     * La <b>primera</b> diligencia que surtió efecto sobre esa resolución.
     *
     * <p>La primera y no la última: si después se volviera a diligenciar por cualquier motivo, el
     * plazo ya habría empezado a correr con aquella.
     */
    Optional<NotificacionDeResolucion> queSurtioEfecto(long resolucionId);

    /** Cuántos intentos se llevan sobre esa resolución. */
    int intentosDe(long resolucionId);
}
