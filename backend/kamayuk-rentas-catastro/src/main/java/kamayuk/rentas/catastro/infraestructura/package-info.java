/**
 * El transporte hacia {@code catastro}, y nada mas.
 *
 * <p>Desde P5C este modulo NO tiene dominio ni tablas: el sistema del predio vive en el repositorio
 * {@code catastro} (ADR-0029). Lo que queda aqui es el paquete raiz —los nueve puertos, que YA ERAN
 * el contrato— y este paquete, con el unico camino que los implementa.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.catastro.infraestructura;
