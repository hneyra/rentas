/**
 * La auditoria de ADR-0008: quien, desde que maquina, desde que IP, cuando, sobre que, y <b>por
 * que</b>.
 *
 * <p>El manual del sistema original lo describe sin rodeos, y es una decision de diseno deliberada
 * que aqui se conserva integra: se registra «una observacion que debe escribir el usuario, de lo
 * contrario no le permite guardar la modificacion». El <i>que cambio</i> lo reconstruye cualquier
 * sistema; el <i>por que</i> solo lo sabe quien lo cambio, en el momento de cambiarlo.
 *
 * <p><b>Se implementa antes que la primera escritura de negocio</b>, y ese orden es el issue
 * entero. Agregada despues, habria que volver sobre cada caso de uso ya escrito, y alguno se
 * quedaria sin auditar —siempre se queda alguno, y es el que interesa dos anios despues—.
 *
 * <p>Modulo propio y compartido, colgando de {@code kamayuk.rentas}, por lo mismo que {@code
 * dominio} y {@code persistencia}: los doce contextos lo llaman, y para Spring Modulith un
 * subpaquete es interno a su modulo.
 *
 * <h2>Las tres barreras, de la mas externa a la mas interna</h2>
 *
 * <ol>
 *   <li>El tipo {@link kamayuk.rentas.dominio.Observacion}, que no se puede construir vacio, y la
 *       regla de ArchUnit que exige que todo caso de uso de escritura lo reciba. Falla al
 *       <b>compilar</b>.
 *   <li>Este servicio, que escribe la fila en la misma transaccion que la operacion auditada.
 *   <li>La restriccion {@code auditoria_observacion_ck} de la base. Falla al <b>ejecutar</b>, y
 *       arrastra la operacion completa. Es la ultima y la que no se puede rodear: aunque alguien
 *       llegue a la tabla por otro camino, sin observacion no entra.
 * </ol>
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.auditoria;
