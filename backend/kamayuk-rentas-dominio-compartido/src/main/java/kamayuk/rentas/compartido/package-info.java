/**
 * Contexto de tenant de la peticion: utilidad tecnica comun a todos los contextos acotados, y a
 * ninguno en particular (ARQ-03 §2). No es un contexto acotado y no depende de ninguno.
 *
 * <p>Los objetos de valor del dominio compartido —importes, periodos, codigos— viven en {@link
 * kamayuk.rentas.dominio}, no aqui: {@code TenantContext} es infraestructura con nombre en ingles,
 * y mezclarlo con el vocabulario tributario haria que las reglas de ArchUnit acotadas al dominio
 * revisaran un {@code ThreadLocal}.
 *
 * <p>{@code @NullMarked}: en este paquete todo es no nulo salvo lo marcado {@code @Nullable}, y
 * NullAway lo verifica al compilar (ARQ-04 §4).
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.compartido;
