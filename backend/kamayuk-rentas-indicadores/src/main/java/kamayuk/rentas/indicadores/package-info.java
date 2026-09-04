/**
 * El panel de recaudacion: los indicadores del ejercicio, a una fecha (#56, RF-130).
 *
 * <p><b>No es un contexto acotado.</b> ARQ-01 §3 fija doce y este no es el trece: no tiene modelo,
 * no tiene tablas, no determina y no asienta. Lo unico que hace es <b>agregar</b> lo que otros ya
 * publican —{@code cuentacorriente.RecaudacionDelLibro}, {@code cuentacorriente.CarteraDelLibro} y
 * {@code tesoreria.AvanceDeCaja}— y redactarlo para una pantalla.
 *
 * <p><b>Invariante:</b> ninguna cifra de aqui se calcula aqui. Si el panel sumara por su cuenta lo
 * que la caja ya suma, la pantalla de inicio y la de recaudacion podrian decir cifras distintas del
 * mismo dia, y no habria forma de saber cual esta mal.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.indicadores;
