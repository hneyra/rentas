package kamayuk.rentas.fiscalizacion.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * El embudo de un programa de fiscalización: cuántos se detectaron, cuántos se programaron, cuántos
 * tienen acta y cuántos sostienen una determinación (#196, {@code fisc_panel}).
 *
 * <h2>Por qué esto lo publica el backend y no se compone en el navegador</h2>
 *
 * <p>Las cuatro cifras se podrían sacar del {@code totalElementos} de cuatro operaciones distintas
 * —omisos, muestra, actas y resultados—, y eso es exactamente lo que {@code conectores.ts} prohíbe:
 * serían cuatro peticiones para cuatro números que ninguna operación afirma que signifiquen eso, y
 * tres de las cuatro habría que acotarlas a mano al programa. <b>Un embudo compuesto en el
 * navegador se lee igual que uno publicado, y sólo uno de los dos se puede cuadrar.</b>
 *
 * <h2>Las cuatro etapas cuentan UNIDADES, no papeles</h2>
 *
 * <p>El embudo es un estrechamiento: de los detectados salen los programados, de éstos los que
 * tienen acta y de éstos los que sostienen diferencia. Para que eso sea cierto, las cuatro tienen
 * que contar lo mismo —la unidad fiscalizada— y no filas. Refiscalizar un predio levanta una
 * <b>segunda</b> acta (versión 2) y reliquidar emite una <b>segunda</b> liquidación: contar filas
 * haría que «con acta» superara a «programados», que es un embudo que se ensancha.
 *
 * <h2>Lo que este tipo NO dice, medido</h2>
 *
 * <p><b>«Con acta cerrada», que es como el artboard rotula la tercera etapa, sigue sin
 * publicarse</b> — pero desde #214 ya no porque no se pueda contestar. Hasta entonces {@link
 * EstadoDeActa} declaraba cinco valores y este sistema escribía uno: toda acta nacía {@code
 * ABIERTA} y no había en {@code src/main} un solo camino que la moviera, así que un {@code
 * conActaCerrada} derivado del estado valdría <b>cero siempre</b> —el defecto que #194 midió—.
 *
 * <p>#214 cerró eso de la única manera que no inventa una segunda verdad: el estado del acta se
 * quedó en lo único que no se deriva de nada —viva o anulada—, y «cerrada» es <b>que tenga
 * liquidación</b>, que es un hecho que ya está escrito en {@code liquidacion_fiscalizacion} y que
 * {@link LiquidacionRepository#ultimaVersionDeActa} ya contesta. O sea que se puede contar. No se
 * cuenta <b>aquí</b> porque es otra etapa del embudo y otro contrato publicado, y el nombre que se
 * le ponga decide qué dibuja la pantalla: es #231.
 *
 * <p>Lo que sí se puede contar, y es lo que {@link #conActa} cuenta, es cuántas unidades del
 * programa <b>tienen acta viva</b> —levantada y no anulada—: la etapa que el javadoc de {@code
 * ActasController} llama «Inspeccionados». Se publica con ese nombre y no con el del artboard,
 * porque no son lo mismo.
 *
 * @param programaId el programa
 * @param codigo su «Nº de programa», que es lo que la pantalla teclea
 * @param ejercicio el ejercicio que el programa examina; nulo en un programa anterior a {@code V60}
 * @param aLaFecha el día al que está el cruce (regla 9). <b>No es decorativo</b>: las tres etapas
 *     de abajo están congeladas por lo que se sorteó y se visitó, y la primera se resuelve contra
 *     el padrón de hoy, así que se mueve sola mientras las otras no
 * @param detectadosPorCruce cuántos predios señala el cruce con los parámetros del programa; nulo
 *     si el programa no los declara
 * @param parametroQueFalta cuál de los parámetros del sorteo le falta al programa, cuando {@link
 *     #detectadosPorCruce} no se puede resolver. Nombrarlo es lo único honesto que se puede hacer
 *     con un programa registrado antes de {@code V60}
 * @param programados cuántas unidades sorteó la muestra
 * @param conActa cuántas de ellas tienen acta viva. <b>No es «con acta cerrada»</b>: ver arriba
 * @param conDiferencia cuántas unidades tienen, en la <b>última</b> versión de su liquidación, al
 *     menos una línea cuya condición justifica determinar de oficio ({@link
 *     CondicionFiscalizada#hayDiferencia})
 */
public record EmbudoDeFiscalizacion(
        long programaId,
        String codigo,
        @Nullable Ejercicio ejercicio,
        LocalDate aLaFecha,
        @Nullable Integer detectadosPorCruce,
        @Nullable String parametroQueFalta,
        int programados,
        int conActa,
        int conDiferencia) {

    public EmbudoDeFiscalizacion {
        Objects.requireNonNull(codigo, "El embudo necesita el codigo del programa");
        Objects.requireNonNull(aLaFecha, "Toda cifra publicada dice a que fecha esta (regla 9)");
        if ((detectadosPorCruce == null) == (parametroQueFalta == null)) {
            throw new IllegalArgumentException(
                    "O se detecta y hay cifra, o no se detecta y se dice que parametro falta: las"
                            + " dos cosas a la vez, o ninguna, dejan a la pantalla sin saber si"
                            + " esperar un numero");
        }
        if (programados < 0 || conActa < 0 || conDiferencia < 0) {
            throw new IllegalArgumentException("Ninguna etapa del embudo puede ser negativa");
        }
    }
}
