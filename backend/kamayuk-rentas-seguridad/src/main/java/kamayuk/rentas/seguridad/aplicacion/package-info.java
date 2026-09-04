/**
 * Casos de uso de seguridad: la frontera transaccional (ARQ-04 §1).
 *
 * <p>Todo lo que cambia la configuracion de acceso deja auditoria (ADR-0008 §5). El manual no lo
 * pide; sin ello, quien administra la seguridad puede alterar su propia pista, que es el unico
 * agujero que deja una auditoria por lo demas completa.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.seguridad.aplicacion;
