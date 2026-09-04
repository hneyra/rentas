package kamayuk.rentas.valores.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Las notificaciones de un valor.
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2). No hay ningun metodo que actualice ni que
 * borre: una diligencia no se corrige, se vuelve a diligenciar con el intento siguiente, y el
 * privilegio de {@code UPDATE} sobre {@code notificacion} se revoca en V28 para que no exista la
 * tentacion.
 */
public interface NotificacionRepository {

    /**
     * Guarda una diligencia.
     *
     * @param notificacion {@link Notificacion#esNueva()} tiene que ser verdadero
     * @return la misma, con su {@code id} y su usuario asignados
     */
    Notificacion insertar(Notificacion notificacion);

    /** Todas las diligencias de un valor, de la primera a la ultima. */
    List<Notificacion> deValor(long valorId);

    /**
     * La diligencia que hizo exigible la deuda del valor, si alguna lo hizo.
     *
     * <p>Es la primera que {@linkplain Notificacion#surtioEfecto() surtio efecto}, no la ultima: si
     * despues de notificar se volviera a diligenciar por cualquier motivo, el plazo ya habria
     * empezado a correr con la primera.
     */
    Optional<Notificacion> queSurtioEfecto(long valorId);

    /** Cuantas diligencias lleva el valor; el siguiente intento es esto mas uno. */
    int intentosDe(long valorId);
}
