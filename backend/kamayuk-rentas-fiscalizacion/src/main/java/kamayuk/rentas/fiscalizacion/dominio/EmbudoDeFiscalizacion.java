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
 * <h2>La tercera etapa se llama «Inspeccionados», y el rótulo del artboard se corrigió (#241)</h2>
 *
 * <p>Durante tres issues esta etapa fue un hueco en la pantalla. {@link #conActa} cuenta cuántas
 * unidades del programa <b>tienen acta viva</b> —levantada y no anulada—, que es la etapa que el
 * javadoc de {@code ActasController} llama «Inspeccionados»; el artboard la rotulaba «Con acta
 * cerrada», y pintar una bajo el otro habría dicho otra cosa, así que la celda decía su motivo.
 *
 * <p><b>Lo que #241 midió es que el rótulo era el equivocado</b>, y no con una opinión sino con dos
 * frases del propio artboard:
 *
 * <ol>
 *   <li>la nota de {@code fis-panel} —«Lo detectado, lo <b>inspeccionado</b> y lo que sostiene una
 *       determinación»— nombra <b>tres</b> cosas para cuatro cifras, y la tercera es la inspección;
 *   <li>la nota de {@code fis-actas} situaba el cierre <b>antes</b> de liquidar —«sin acta cerrada
 *       no se puede liquidar»—, o sea lo contrario de lo que #214 llamó «cerrada»: que el acta
 *       <b>tenga</b> liquidación. Con aquella definición la frase del artboard se leía «sin
 *       liquidación no se puede liquidar», de modo que las dos «cerrada» no podían ser la misma
 *       palabra.
 * </ol>
 *
 * <p>Así que la tercera etapa de este embudo siempre fue la inspección, el rótulo pasó a «Con acta
 * levantada» —en el artboard y en la definición de la pantalla a la vez— y esta cifra la llena.
 *
 * <h2>Lo que este tipo NO dice, y por qué NO es que no se pueda contar (#231)</h2>
 *
 * <p>«Con liquidación» <b>se puede contar</b>, y eso no está en duda: {@code LiquidarFiscalizacion}
 * escribe la liquidación y su apertura, {@code CambiarEstadoDeLaLiquidacion} escribe la anulación,
 * y {@link LiquidacionRepository#ultimaVersionDeActa} ya lo consulta para rechazar la segunda
 * liquidación. No valdría cero siempre —que es el defecto que #194 midió con el estado del acta,
 * cuando {@link EstadoDeActa} declaraba cinco valores y este sistema escribía uno—.
 *
 * <p><b>No se publica porque es otra etapa, no porque falte el dato.</b> El embudo del manual tiene
 * cuatro —«Programados», «Inspeccionados», «Con liquidación» y «Notificadas», como lo dicen {@code
 * ActasController} y {@code ActaPredialController}— y en él «Con liquidación» va <b>después</b> de
 * «Inspeccionados»; el de {@code fis-panel} tiene otras cuatro, y su cuarta celda es «Con
 * diferencia». Publicar aquí una quinta cifra dejaría un campo que ninguna pantalla dibuja, que es
 * lo que #431, #432 y #544 tuvieron que retirar después. Y publicarla bajo el rótulo viejo habría
 * dado dos significados a «cerrada» —el del artboard, antes de liquidar; el de #214, después—, que
 * es la segunda verdad que #214 se negó a escribir.
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
 * @param conActa cuántas de ellas tienen acta viva: la etapa «Inspeccionados», que la pantalla
 *     rotula «Con acta levantada» desde #241
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
