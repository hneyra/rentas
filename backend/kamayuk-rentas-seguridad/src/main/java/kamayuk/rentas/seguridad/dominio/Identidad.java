package kamayuk.rentas.seguridad.dominio;

import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Quien es la sesion en curso: la persona que hay detras del token, ya resuelta a la fila de {@code
 * usuario} de <b>esta</b> municipalidad (#559).
 *
 * <h2>Por que hace falta un record y no basta con {@link Usuario}</h2>
 *
 * <p>Porque son dos hechos de dos tablas. {@code usuario} dice quien es —{@link #usuarioId()},
 * {@link #cuenta()} y {@link #nombre()}—, y {@code sesion} dice sobre que ejercicio esta trabajando
 * ahora mismo. Publicarlos por separado obligaria a la interfaz a hacer dos lecturas para dibujar
 * una sola cabecera, y a componer entre ellas la unica cosa que no debe componer: la relacion entre
 * la persona y su sesion.
 *
 * <h2>El {@code usuarioId} no es un dato mas</h2>
 *
 * <p>Es <b>el</b> dato. {@code PUT /seguridad/usuarios/{id}/clave} solo admite la clave propia
 * —{@code AdministrarSesion} lo comprueba comparando la cuenta del token con la del usuario que el
 * {@code id} nombra—, y hasta este issue la interfaz no tenia forma de saber cual era el suyo: las
 * unicas lecturas que publicaban un {@code usuario.id} eran el <b>padron entero</b> de usuarios y
 * la matriz de otro, las dos detras de un permiso de administracion mucho mayor que «cambiar mi
 * propia contrasena».
 *
 * <p>Y sale del token, nunca de un parametro. Esa es la diferencia entre una lectura de la sesion
 * propia y un directorio de personas: no hay donde poner el identificador de otra.
 *
 * @param ejercicioDeTrabajo el ejercicio que la sesion tiene <b>registrado</b> como acto (RF-125),
 *     o {@code null} si nadie lo ha fijado todavia. Nulo no quiere decir «el corriente»: quiere
 *     decir que no hay ningun acto que lo diga, y las dos cosas se distinguen a proposito (#557).
 *     Poner aqui el año del reloj seria afirmar que alguien lo eligio
 */
public record Identidad(
        long usuarioId, String cuenta, String nombre, @Nullable Ejercicio ejercicioDeTrabajo) {}
