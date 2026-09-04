/**
 * Los casos de uso del convenio de fraccionamiento: registrar el preconvenio, formalizarlo con el
 * recibo de su cuota inicial y cerrarlo —anulacion, quiebre o reformulacion— (RF-084, RF-085).
 *
 * <p>Cada uno abre <b>una</b> transaccion y todo lo suyo cae dentro: el convenio, su cronograma, el
 * acogimiento en el libro y la auditoria, o nada.
 *
 * <p><b>Desde P5D aqui no queda ningun caso de uso de caja</b>: cobrar, anular, duplicar, abrir el
 * turno y cerrarlo se fueron con las tablas (`V7`, ADR-0026). Lo que se quedo es lo que ADR-0026 §5
 * decide que se queda, y la unica pieza del convenio que la caja sigue tocando —la formalizacion—
 * recibe hoy el {@code reciboId} como argumento: lo traera el evento {@code PagoRegistrado}.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.tesoreria.aplicacion;
