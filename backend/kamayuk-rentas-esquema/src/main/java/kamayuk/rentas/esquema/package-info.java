/**
 * El esquema: las migraciones Flyway y el proceso que las aplica.
 *
 * <p>No es un contexto acotado y no depende de Spring. Contiene una sola clase de produccion,
 * {@link kamayuk.rentas.esquema.Migrador}, porque el resto del modulo son recursos —las migraciones
 * y el guion de roles— y su prueba de aislamiento, que es bloqueante.
 *
 * <p>{@code @NullMarked}: en este paquete todo es no nulo salvo lo marcado {@code @Nullable}, y
 * NullAway lo verifica al compilar (ARQ-04 §4).
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.esquema;
