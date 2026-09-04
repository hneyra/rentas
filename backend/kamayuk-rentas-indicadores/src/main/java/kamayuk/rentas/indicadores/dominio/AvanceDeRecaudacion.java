package kamayuk.rentas.indicadores.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * El panel entero: el avance de la recaudacion de un ejercicio, a una fecha y a una hora (#56,
 * RF-130).
 *
 * <p>Lleva las <b>dos</b> marcas de tiempo y las dos hacen falta. {@link #fechaCalculo} es el dia
 * tributario con el que se leyo —el que las cifras dicen y el que la pantalla imprime—; {@link
 * #calculadoEn} es el instante exacto en que se leyeron, con su zona. Un panel se recarga cada
 * pocos minutos y dos lecturas del mismo dia dan cifras distintas: sin la hora, dos capturas del
 * mismo panel serian indistinguibles y ninguna podria explicarse (AC 2 de #56, RNF-075).
 *
 * <h2>Lo cargado es un campo, no una frase (#549)</h2>
 *
 * <p>{@link #cargado} es lo emitido del ejercicio, y hasta #549 <b>no salia del panel</b>: la cifra
 * se calculaba aqui y se enterraba dentro del texto de la nota del KPI «Avance de cobranza» —«de S/
 * 13,783.75 cargados»—. La pantalla que la necesitaba no podia recomponerla: una cifra de dinero no
 * se compone en la interfaz (RNF-083), y sacarla de una frase con una expresion regular seria peor
 * que no tenerla. Ahora viaja como campo <b>y</b> sigue en la frase, y una prueba compara las dos
 * para que no puedan divergir.
 *
 * <p>Va acompañada de {@link #cargadoA} y no de {@link #fechaCalculo}: es la fecha con la que el
 * libro contesto, y si algun dia contestara con otra el panel diria la verdad en vez de estampar la
 * suya (regla 9, RNF-075). Es el mismo reparto que en {@link Indicador}: el dominio lleva el par de
 * componentes y la capa web los ata en un solo objeto.
 *
 * <p>Sin cargos asentados en el ejercicio la cifra es <b>S/ 0.00 y no un hueco</b>, y ahi esta la
 * diferencia con el avance: que el libro no tenga ningun cargo es un hecho que se puede afirmar
 * —cero cargado—, mientras que el porcentaje de algo sobre cero no existe, y por eso {@link
 * AvanceDeCobranza} devuelve vacio.
 *
 * <p>No hay aqui ninguna cifra que no venga de un contexto que la publica. Ver el paquete raiz.
 *
 * @param ejercicio el ejercicio del que se habla
 * @param fechaCalculo el dia al que corresponden las cifras
 * @param calculadoEn el instante en que se leyeron
 * @param cargado lo emitido del ejercicio, al centimo
 * @param cargadoA la fecha con la que el libro sumo lo cargado
 * @param indicadores las cifras grandes
 * @param carteras los bloques de filas
 */
public record AvanceDeRecaudacion(
        Ejercicio ejercicio,
        LocalDate fechaCalculo,
        Instant calculadoEn,
        Dinero cargado,
        LocalDate cargadoA,
        List<Indicador> indicadores,
        List<Cartera> carteras) {

    public AvanceDeRecaudacion {
        Objects.requireNonNull(ejercicio, "El panel siempre es de un ejercicio");
        Objects.requireNonNull(fechaCalculo, "Un panel sin fecha de corte miente al dia siguiente");
        Objects.requireNonNull(
                calculadoEn, "El panel dice tambien a que hora se leyo (AC 2 de #56)");
        Objects.requireNonNull(
                cargado, "Lo cargado es cero cuando no hay cargos, nunca ausente (#549)");
        Objects.requireNonNull(
                cargadoA, "Toda cifra indica a que fecha esta actualizada (RNF-075, regla 9)");
        Objects.requireNonNull(indicadores, "La lista es vacia, no nula");
        Objects.requireNonNull(carteras, "La lista es vacia, no nula");
        indicadores = List.copyOf(indicadores);
        carteras = List.copyOf(carteras);
    }
}
