/**
 * Casos de uso de fiscalización: la frontera transaccional (ARQ-04 §1).
 *
 * <p>Aquí empieza y termina la transacción, y por tanto aquí es donde el contexto de municipalidad
 * llega a la base con {@code SET LOCAL} y donde se asienta la auditoría.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.fiscalizacion.aplicacion;
