package kamayuk.rentas.valores.dominio;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Cuanto de un valor cubre lo que ya se declaro prescrito (#337): la Specification de la que se
 * deriva si un valor pasa a {@link EstadoDeValor#PRESCRITO}.
 *
 * <h2>Por que existe</h2>
 *
 * <p>Un valor formaliza varias obligaciones a proposito —{@code POST /valores} recibe N selectores
 * sin exigir que compartan tributo ni ejercicio, y la emision masiva mete todo el rango en uno—,
 * pero la prescripcion es de cada obligacion: el computo sale ejercicio por ejercicio y la
 * solicitud es de un tributo. Hasta #337 bastaba con que <b>alguna</b> linea coincidiera con un
 * ejercicio prescrito para marcar el valor entero, de modo que el PREDIAL 2022 que no prescribio y
 * los ARBITRIOS 2021 que nadie pidio quedaban dentro de un titulo {@code PRESCRITO}: un estado del
 * que no sale nada —ni un acto que lo revierta, ni coactiva, que lo rechaza como no cobrable—. Es
 * exactamente lo que el javadoc de {@code DeclararPrescripcion} advertia: redondear hacia el
 * contribuyente «extinguiria deuda viva».
 *
 * <h2>Que decide</h2>
 *
 * <ul>
 *   <li>{@link #TOTAL}: todas las lineas estan en el conjunto prescrito. Solo este marca.
 *   <li>{@link #PARCIAL}: alguna si y alguna no. El valor <b>no se toca</b>: {@code PRESCRITO} es
 *       irreversible y {@code EMITIDO} o {@code NOTIFICADO} no, y #674 ya decidio que la
 *       prescripcion la opone quien la gano sobre el rango que gano. Se informa, para que la
 *       administracion lo vea.
 *   <li>{@link #NINGUNA}: ninguna linea esta, o el valor no tiene lineas —un valor sin nada que
 *       formalizar no tiene nada que haya prescrito—.
 * </ul>
 *
 * <p>El conjunto es el <b>acumulado</b> del contribuyente, no el de una sola resolucion: el PREDIAL
 * 2021 que prescribio en marzo y el 2022 que prescribe al ano siguiente cubren entre los dos un
 * valor que ninguna de las dos cubria sola.
 *
 * <p>Funcion pura (regla 6): sin base, sin reloj. La unica definicion de «cubierto» que hay: la
 * consulta de candidatos trae los valores que tocan lo que se acaba de declarar, y es esto lo que
 * decide cuales se marcan.
 */
public enum CoberturaDeLaPrescripcion {
    TOTAL,
    PARCIAL,
    NINGUNA;

    /**
     * La cobertura de un valor.
     *
     * @param lineas las obligaciones que el valor formaliza
     * @param prescritas los pares prescritos para su contribuyente, contando todas sus resoluciones
     */
    public static CoberturaDeLaPrescripcion de(
            List<ValorDetalle> lineas, Set<ObligacionPrescrita> prescritas) {
        Objects.requireNonNull(lineas, "Hacen falta las lineas del valor");
        Objects.requireNonNull(prescritas, "Hace falta el conjunto prescrito, aunque este vacio");
        long cubiertas =
                lineas.stream()
                        .map(ObligacionPrescrita::deLaLinea)
                        .filter(prescritas::contains)
                        .count();
        if (cubiertas == 0) {
            return NINGUNA;
        }
        return cubiertas == lineas.size() ? TOTAL : PARCIAL;
    }
}
