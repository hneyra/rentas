/**
 * Determinación de arbitrios —limpieza pública, parques y jardines, serenazgo— por predio, uso y
 * sector (#31, RF-022).
 *
 * <p>Sin Spring y sin JPA (regla 7). A diferencia del predial, aquí no hay un grafo de reglas: el
 * monto de cada cuota es la tasa parametrizada por servicio, sector y uso —sin área, sin
 * valuación—, tomada tal cual del conjunto sellado del ejercicio.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.nucleo.dominio.arbitrios;
