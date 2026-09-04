/**
 * Objetos de valor del dominio compartido: el vocabulario que los doce contextos acotados dan por
 * sabido (ARQ-01 §4 regla 6).
 *
 * <p>Es un paquete {@code dominio} de verdad, y no una bolsa de utilidades: aqui aplican las siete
 * reglas de ArchUnit sin excepcion —sin Spring, sin JPA, sin reloj, sin coma flotante— y por eso
 * este paquete es el que <b>apaga</b> {@code SIN_DOMINIO_TODAVIA}. Desde que existe, las reglas
 * acotadas a {@code ..dominio..} revisan codigo real en lugar de no encontrar nada.
 *
 * <p><b>Por que cuelga de {@code kamayuk.rentas} y no de {@code kamayuk.rentas.compartido}.</b>
 * Para Spring Modulith un subpaquete es interno a su modulo, y un objeto de valor que ningun
 * contexto puede importar no sirve de vocabulario comun. Como modulo propio queda expuesto sin
 * necesidad de anotar el paquete, que es lo que permite que este modulo Gradle siga sin depender de
 * Spring —la regla 7 en su forma mas literal: aqui no hay nada que importar de un framework, ni
 * siquiera una anotacion.
 *
 * <p>Lo que <b>no</b> vive aqui: cualquier operacion que devuelva un importe determinado. Eso es
 * regla de calculo y esta bloqueado por D-02a. {@link kamayuk.rentas.dominio.Dinero} sabe sumar y
 * restar; no sabe cuanto se debe.
 *
 * <p>{@code @NullMarked}: todo es no nulo salvo lo marcado {@code @Nullable} (ARQ-04 §4).
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.dominio;
