package kamayuk.rentas.coactiva.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Las notificaciones de los actos coactivos (#41, RF-103).
 *
 * <p>Escribe en {@code notificacion} con {@code objeto = 'ACTO_COACTIVO'}: la misma tabla que #39
 * usa para los valores, en su rebanada. Ningun metodo recibe la municipalidad (regla 2).
 *
 * <p><b>No hay ningun metodo que actualice ni que borre.</b> Una diligencia no se corrige: se
 * vuelve a diligenciar con el intento siguiente, y el privilegio de {@code UPDATE} sobre {@code
 * notificacion} se revoco en V28 para que no exista la tentacion.
 */
public interface NotificacionCoactivaRepository {

    /**
     * Guarda una diligencia.
     *
     * @param notificacion {@link NotificacionCoactiva#esNueva()} tiene que ser verdadero
     * @return la misma, con su {@code id} y su usuario asignados
     */
    NotificacionCoactiva insertar(NotificacionCoactiva notificacion);

    /** Todas las diligencias de un acto, de la primera a la ultima. */
    List<NotificacionCoactiva> deActo(long actoId);

    /**
     * La diligencia que abrio el plazo del acto, si alguna lo hizo.
     *
     * <p>Es la <b>primera</b> que {@linkplain NotificacionCoactiva#surtioEfecto() surtio efecto},
     * no la ultima: si despues de notificar se volviera a diligenciar por cualquier motivo, el
     * plazo ya habria empezado a correr con la primera.
     */
    Optional<NotificacionCoactiva> queSurtioEfecto(long actoId);

    /** Cuantas diligencias lleva el acto; el siguiente intento es esto mas uno. */
    int intentosDe(long actoId);
}
