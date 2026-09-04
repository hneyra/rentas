/**
 * Casos de uso del libro de asientos: la frontera transaccional (ARQ-04 §1).
 *
 * <p>Aqui empieza y termina la transaccion, y por tanto aqui es donde el contexto de municipalidad
 * llega a la base con {@code SET LOCAL} y donde se asienta la auditoria.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.cuentacorriente.aplicacion;
