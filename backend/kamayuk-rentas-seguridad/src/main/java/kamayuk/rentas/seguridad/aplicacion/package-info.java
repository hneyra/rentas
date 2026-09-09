/**
 * Casos de uso de seguridad: la frontera transaccional (ARQ-04 §1).
 *
 * <p>La configuracion de acceso ya no se cambia aqui: se cambia en {@code identidad} y llega por su
 * buzon (ADR-0039, etapa 4). Lo que escribe este paquete en esas tablas es la siembra del arranque
 * en frio y el aplicador de los eventos, y los dos estan declarados como escritores de la
 * autorizacion con su motivo.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.seguridad.aplicacion;
