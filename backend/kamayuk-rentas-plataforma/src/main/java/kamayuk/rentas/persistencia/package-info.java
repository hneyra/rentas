/**
 * El patron de acceso a datos que repiten los doce contextos: {@code JdbcClient}, mapeo, paginacion
 * con orden seguro y la transaccion que lleva el contexto de tenant (ARQ-04 §1, ADR-0001).
 *
 * <p>Se escribe una vez y se copia. Si se dejara para el primer contexto que lo necesite, cada
 * contexto inventaria el suyo: once formas de paginar, once de mapear un {@code numeric} y —lo
 * caro— once oportunidades de que a uno se le escape el {@code ORDER BY} concatenado.
 *
 * <p><b>Por que cuelga de {@code kamayuk.rentas} y no de {@code kamayuk.rentas.plataforma}</b>,
 * aunque viva en el mismo modulo Gradle: por el mismo motivo que {@code kamayuk.rentas.dominio}.
 * Para Spring Modulith un subpaquete es interno a su modulo, y un patron que los doce contextos
 * tienen que extender no puede ser interno a nadie. Como {@code compartido} y {@code dominio}, se
 * declara modulo compartido en {@code SgtmAplicacion}: no es un contexto acotado, y que cualquier
 * contexto lo use no es una violacion de los limites sino su proposito.
 *
 * <p>No hay JPA (ADR-0001). El dominio no conoce el mecanismo de persistencia (regla 7), y con
 * {@code JdbcClient} el SQL que se ejecuta es el SQL que esta escrito: en un sistema donde el
 * aislamiento depende de una politica RLS sobre cada consulta, no conviene que un ORM decida por su
 * cuenta cuando emitir un {@code JOIN} o una carga perezosa.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.persistencia;
