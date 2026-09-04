package kamayuk.rentas.autorizacion;

import java.time.LocalDate;

/**
 * Responde si un usuario puede hacer algo. El puerto; la implementacion vive en {@code seguridad}.
 *
 * <p>La fecha entra como argumento y no se lee del reloj: la autorizacion tiene vigencia (RF-123) y
 * una prueba tiene que poder situarse antes o despues de ella sin cambiar la hora de la maquina.
 */
public interface ComprobadorDeAcceso {

    /**
     * @param usuario la cuenta del usuario, tal como llega del token
     * @param acceso id de la opcion en el catalogo (NEG-03)
     * @param privilegio cual de los siete se exige
     * @param fecha dia para el que se comprueba la vigencia
     */
    boolean autoriza(String usuario, String acceso, Privilegio privilegio, LocalDate fecha);
}
