/**
 * Lo comun a toda carga masiva desde archivo: leer las filas, informar cuales no entraron y —para
 * las que siembran datos ficticios— negarse si la instalacion no es de demostracion.
 *
 * <p>Es transversal por el mismo motivo que {@code auditoria} y {@code documentos}: un catalogo
 * vial, un padron de sectores y un padron de contribuyentes son archivos distintos con el mismo
 * problema. Vivia dentro de {@code catastro} mientras solo lo usaba {@code catastro}; en cuanto
 * {@code contribuyentes} tuvo su propia carga, la alternativa era un segundo analizador de comas
 * con sus propios defectos.
 *
 * <p><b>Lo que no hace:</b> abrir transacciones alrededor del archivo. Cada fila abre la suya al
 * llamar al caso de uso que la registra, y esa propiedad —la fila que falla no se lleva a la
 * siguiente— es de quien importa, no de quien lee.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.carga;
