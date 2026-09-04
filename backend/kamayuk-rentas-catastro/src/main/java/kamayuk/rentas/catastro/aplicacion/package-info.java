/**
 * Casos de uso del catastro: la frontera transaccional (ARQ-04 §1).
 *
 * <p>Aqui empieza y termina la transaccion, y por tanto aqui es donde el contexto de municipalidad
 * llega a la base con {@code SET LOCAL} y donde se asienta la auditoria. Que las dos cosas ocurran
 * en el mismo sitio no es casualidad: es lo que garantiza que una operacion deshecha no deje
 * constancia de haber ocurrido, y que una sin observacion no ocurra.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.catastro.aplicacion;
