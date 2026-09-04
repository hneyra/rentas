package kamayuk.rentas.cuentacorriente.dominio;

import java.util.ArrayList;
import java.util.List;

/**
 * Las cuotas que un alta o una baja de deuda abarca: de la primera a la ultima, las dos incluidas
 * (#538, RF-043, RF-044).
 *
 * <p>La pantalla del manual da de alta un <b>rango</b> —«cuotas 1 a 4»—, y un rango de cuotas
 * <b>no</b> es una obligacion: son {@code n}. {@link ClaveDeSaldo} identifica <b>una</b>, con su
 * {@code periodo} dentro, asi que lo que abarca el acto tiene que viajar aparte y expandirse a las
 * {@code n} claves que de verdad se mueven ({@link MovimientoDeDeuda#enCadaCuota}).
 *
 * <h2>El 0 no se acompaña</h2>
 *
 * <p>{@code periodo = 0} significa <b>anual</b> —la obligacion que no se divide en cuotas—, y eso
 * lo documenta {@link ClaveDeSaldo}. No es «la cuota cero» ni el principio de nada: un rango que
 * empezara en 0 estaria diciendo «la obligacion anual y ademas las cuotas 1 a 4», que son cosas
 * distintas del mismo tributo y del mismo ejercicio. Por eso el unico rango que admite el 0 es
 * {@link #ANUAL}, el que empieza y acaba en el.
 *
 * <p>Esa asimetria es justamente lo que hacia invisible el defecto de #538: mandar {@code
 * cuotaDesde}/{@code cuotaHasta} a una peticion que no los declaraba dejaba los asientos en {@code
 * periodo = 0}, y <b>0 es un valor legitimo</b>. La fila mala no se distinguia de una buena; solo
 * se descubre cuando alguien paga y el abono no cancela lo que creia.
 *
 * @param desde la primera cuota que el acto abarca
 * @param hasta la ultima, incluida
 */
public record RangoDeCuotas(int desde, int hasta) {

    /**
     * La obligacion anual: la que no se divide en cuotas. Es el {@code periodo = 0} de la clave.
     */
    public static final RangoDeCuotas ANUAL = new RangoDeCuotas(0, 0);

    public RangoDeCuotas {
        exigirQueSeaUnPeriodo(desde, "La primera cuota");
        exigirQueSeaUnPeriodo(hasta, "La ultima cuota");
        if (desde > hasta) {
            throw new IllegalArgumentException(
                    "El rango de cuotas va de la primera a la ultima: "
                            + desde
                            + " no puede ser mayor que "
                            + hasta);
        }
        if (desde == 0 && hasta != 0) {
            throw new IllegalArgumentException(
                    "0 es la obligacion anual, no la cuota cero: no puede ser el principio de un"
                            + " rango");
        }
    }

    /** El rango de una sola cuota; con {@code 0}, la obligacion anual. */
    public static RangoDeCuotas deUnaSola(int cuota) {
        return new RangoDeCuotas(cuota, cuota);
    }

    /** Cuantas obligaciones abarca el acto. */
    public int cuantas() {
        return hasta - desde + 1;
    }

    /** Los {@code periodo} que el acto mueve, en orden. */
    public List<Integer> periodos() {
        List<Integer> periodos = new ArrayList<>(cuantas());
        for (int periodo = desde; periodo <= hasta; periodo++) {
            periodos.add(periodo);
        }
        return List.copyOf(periodos);
    }

    /**
     * Como se escribe en el papel: {@code Anual}, {@code 3} o {@code 1 a 4}.
     *
     * <p>Vive aqui y no en el formateador porque la nota de abono y la de cargo tienen que decir
     * <b>que</b> cubren: una que dijera solo «Cuota: 1» sobre cuatro obligaciones seria un papel
     * que no explica lo que sustenta.
     */
    public String etiqueta() {
        if (equals(ANUAL)) {
            return "Anual";
        }
        return desde == hasta ? Integer.toString(desde) : desde + " a " + hasta;
    }

    private static void exigirQueSeaUnPeriodo(int cuota, String cual) {
        if (cuota < 0 || cuota > ClaveDeSaldo.PERIODO_MAXIMO) {
            throw new IllegalArgumentException(
                    cual
                            + " esta fuera de rango: "
                            + cuota
                            + ". Se admite de 0 (anual) a "
                            + ClaveDeSaldo.PERIODO_MAXIMO);
        }
    }
}
