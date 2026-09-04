package kamayuk.rentas.tesoreria;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo que la caja recaudo por un concepto del TUPA en un rango de dias, tal como cruza la frontera
 * del modulo (#54, RF-115).
 *
 * <p>Es a {@link CobrosDeTasas#recaudado} lo que {@link TasaCobrada} es a {@link
 * CobrosDeTasas#acreditar}: la una acredita <b>un</b> pago concreto y esta suma <b>todos</b> los
 * del concepto. Sale del detalle congelado de los recibos —lo que la ventanilla cobro—, que es la
 * pregunta que un resumen de recaudacion hace.
 *
 * <h2>Lo anulado se resta, no se excluye</h2>
 *
 * <p>Un recibo anulado sigue estando: sus lineas cuentan en {@link #cobrado} y otra vez en {@link
 * #anulado}, y {@link #neto()} es la resta. Excluirlas daria el mismo neto y perderia la
 * explicacion de por que el resumen de un año cambio despues de una anulacion. Es el mismo criterio
 * con el que #36 publica el avance de recaudacion.
 *
 * <h2>El rango es el del TURNO</h2>
 *
 * <p>{@link #desde} y {@link #hasta} son fechas de turno de caja, no instantes de recibo, por lo
 * mismo que explica {@code CriterioDeRecaudacion}: la frontera de la medianoche de un instante
 * depende de la zona con que se consulte, y el arqueo del turno usa la fecha del turno. Las dos
 * cifras no pueden discrepar.
 *
 * <p>Las dos fechas son ademas lo que hace defendible el importe (regla 9, RNF-075): «se recaudo
 * tanto» sin decir entre que dias es una cifra que manana significa otra cosa.
 *
 * @param codigoDeTasa el concepto del TUPA sumado
 * @param cobrado lo que las lineas de ese concepto sumaron, anuladas incluidas
 * @param anulado lo que de eso pertenecia a recibos que se anularon
 * @param desde primer dia del rango, inclusive
 * @param hasta ultimo dia del rango, inclusive
 */
public record RecaudacionDeTasa(
        String codigoDeTasa, Dinero cobrado, Dinero anulado, LocalDate desde, LocalDate hasta) {

    public RecaudacionDeTasa {
        Objects.requireNonNull(codigoDeTasa, "La recaudacion es de un concepto del TUPA");
        Objects.requireNonNull(cobrado, "La recaudacion trae lo cobrado");
        Objects.requireNonNull(anulado, "La recaudacion trae lo anulado");
        Objects.requireNonNull(desde, "Toda cifra dice desde que dia se sumo (regla 9, RNF-075)");
        Objects.requireNonNull(hasta, "Toda cifra dice hasta que dia se sumo (regla 9, RNF-075)");
        if (cobrado.esNegativo() || anulado.esNegativo()) {
            throw new IllegalArgumentException("La recaudacion no se cuenta en negativo");
        }
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException(
                    "El rango termina antes de empezar: " + desde + " .. " + hasta);
        }
    }

    /** Nada recaudado en ese rango: el concepto existe y no lo cobro nadie. */
    public static RecaudacionDeTasa nada(String codigoDeTasa, LocalDate desde, LocalDate hasta) {
        return new RecaudacionDeTasa(codigoDeTasa, Dinero.CERO, Dinero.CERO, desde, hasta);
    }

    /** Lo que de verdad quedo recaudado: lo cobrado menos lo anulado. */
    public Dinero neto() {
        return cobrado.menos(anulado);
    }
}
