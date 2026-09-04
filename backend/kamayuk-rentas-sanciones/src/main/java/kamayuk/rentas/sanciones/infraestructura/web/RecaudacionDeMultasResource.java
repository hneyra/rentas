package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.cuentacorriente.RecaudacionDeUnTributo;
import kamayuk.rentas.cuentacorriente.RecaudadoEnElLibro;
import kamayuk.rentas.dominio.Dinero;

/**
 * El resumen de recaudación por multas, por HTTP (#53, RF-073, RF-074, #398).
 *
 * <p>Cada cifra viene del <b>libro</b>: es la suma de los abonos vivos —{@code ABONO} de concepto
 * {@code PAGO} que nadie ha reversado—, agregada por PostgreSQL. Ni una se recompone en la
 * interfaz. Sumar los importes de las papeletas en estado {@code PAGADA} daría una cifra parecida y
 * distinta: no contaría los intereses cobrados, contaría entero un pago parcial y seguiría contando
 * un recibo anulado (AC 3 de #53).
 *
 * <p>{@code abonos} no es decorativo: sin él, «300,00» no dice si son tres pagos o uno, y quien
 * cuadre la caja no tiene con qué contrastar.
 *
 * <h2>{@link #porMes} existe porque «Total S/» no tenía de dónde salir (#398)</h2>
 *
 * <p>{@link #lineas} es lo que el libro devuelve: una por {@code (tributo, ejercicio, mes, fase)}.
 * La pantalla {@code transito_resumen_recaudacion} dibuja <b>una fila por mes</b> con las fases en
 * columnas y una columna «Total S/». Pivotar las fases no suma nada —solo reordena—, pero el total
 * del mes <b>no se puede obtener sin sumar</b>, y sumar en el cliente es exactamente lo que RNF-083
 * prohíbe: la cifra del papel exportado y la de la pantalla dejarían de tener un solo origen.
 *
 * <p><b>Dónde se compone, y por qué aquí.</b> No en {@code cuentacorriente}: el libro «no conoce a
 * nadie» (ARQ-01 §4), y un pivote por mes con las fases en columnas es la forma que pide <b>una</b>
 * pantalla de <b>un</b> módulo; meterlo en {@link RecaudadoEnElLibro} sería meter el reporte de
 * tránsito dentro del libro. Tampoco en los controladores: son dos —tránsito y administrativas— y
 * serían dos copias de la misma cuenta, que es como divergen. Se compone en la proyección HTTP del
 * módulo, que es una sola y ya es de {@code sanciones}, y sobre las líneas que el libro <b>ya</b>
 * publicó: aquí no se consulta nada ni se inventa ningún reparto.
 */
public record RecaudacionDeMultasResource(
        LocalDate desde,
        LocalDate hasta,
        Dinero total,
        long abonos,
        LocalDate actualizadoA,
        List<Linea> lineas,
        List<LineaDeUnMes> porMes) {

    public static RecaudacionDeMultasResource de(RecaudadoEnElLibro recaudado) {
        return new RecaudacionDeMultasResource(
                recaudado.desde(),
                recaudado.hasta(),
                recaudado.total(),
                recaudado.abonos(),
                recaudado.aLaFecha(),
                recaudado.lineas().stream()
                        .map(linea -> Linea.de(linea, recaudado.aLaFecha()))
                        .toList(),
                porMesDe(recaudado));
    }

    /**
     * Una línea del resumen.
     *
     * @param mes el de la fecha valor del abono, de 1 a 12; no es el del ejercicio de la obligación
     * @param fase en qué fase de la cobranza estaba la obligación cuando se cobró: lo que la
     *     pantalla llama «tipo de cobranza»
     */
    public record Linea(
            String tributo,
            int ejercicio,
            int mes,
            String fase,
            long abonos,
            Dinero recaudado,
            LocalDate actualizadoA) {

        static Linea de(RecaudacionDeUnTributo linea, LocalDate aLaFecha) {
            return new Linea(
                    linea.tributo(),
                    linea.ejercicio().valor(),
                    linea.mes(),
                    linea.fase(),
                    linea.abonos(),
                    linea.recaudado(),
                    aLaFecha);
        }
    }

    /**
     * Lo recaudado en un mes, con las fases desglosadas y <b>su total</b> (#398).
     *
     * <p>{@code total} es la suma de <b>todas</b> las fases del mes, no la de las que la pantalla
     * dibuja. El manual dibuja tres —ordinaria, coactiva y convenio— y el libro tiene cuatro: la
     * cuarta, {@code VALOR}, es lo cobrado de una obligación con su resolución de multa ya emitida.
     * Repartir esa cifra entre las tres columnas del manual sería inventar un reparto; dejarla
     * fuera del total sería publicar una recaudación menor que la real. Va dentro del total y
     * viaja, nombrada, en {@link #porFase}.
     *
     * @param mes de 1 a 12
     * @param porFase una entrada por fase con movimiento en el mes, en el orden en que el libro las
     *     devolvió
     * @param total lo recaudado en el mes, de todas las fases
     * @param abonos cuántos asientos lo componen. <b>No es «papeletas pagadas»</b>: una papeleta se
     *     puede pagar en varios abonos y un recibo puede abonar varias papeletas
     */
    public record LineaDeUnMes(
            int mes, List<PorFase> porFase, Dinero total, long abonos, LocalDate actualizadoA) {}

    /** Lo recaudado de una fase dentro de un mes. */
    public record PorFase(String fase, Dinero recaudado, long abonos, LocalDate actualizadoA) {}

    // ------------------------------------------------------------------

    /**
     * Agrupa por mes las líneas que el libro devolvió, sumando las fases.
     *
     * <p>{@code LinkedHashMap} y no un orden inventado: el mes va como lo devuelve el libro, que
     * ordena por tributo, ejercicio, mes y fase. Un orden distinto en la respuesta y en el papel
     * sería una hoja que no se puede cotejar con la pantalla de la que salió.
     */
    private static List<LineaDeUnMes> porMesDe(RecaudadoEnElLibro recaudado) {
        Map<Integer, Map<String, PorFase>> fasesPorMes = new LinkedHashMap<>();
        for (RecaudacionDeUnTributo linea : recaudado.lineas()) {
            Map<String, PorFase> fases =
                    fasesPorMes.computeIfAbsent(linea.mes(), mes -> new LinkedHashMap<>());
            PorFase acumulada = fases.get(linea.fase());
            Dinero importe =
                    acumulada == null
                            ? linea.recaudado()
                            : acumulada.recaudado().mas(linea.recaudado());
            long abonos = (acumulada == null ? 0L : acumulada.abonos()) + linea.abonos();
            fases.put(
                    linea.fase(), new PorFase(linea.fase(), importe, abonos, recaudado.aLaFecha()));
        }

        List<LineaDeUnMes> meses = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, PorFase>> mes : fasesPorMes.entrySet()) {
            Dinero total = Dinero.CERO;
            long abonos = 0;
            for (PorFase fase : mes.getValue().values()) {
                total = total.mas(fase.recaudado());
                abonos += fase.abonos();
            }
            meses.add(
                    new LineaDeUnMes(
                            mes.getKey(),
                            List.copyOf(mes.getValue().values()),
                            total,
                            abonos,
                            recaudado.aLaFecha()));
        }
        return List.copyOf(meses);
    }
}
