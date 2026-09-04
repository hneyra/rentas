package kamayuk.rentas.tesoreria.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Un recibo del turno, visto desde el arqueo (#36, RF-087).
 *
 * <p>No es un {@link Recibo}: no trae su desglose, ni su contribuyente, ni su observacion. El
 * arqueo de un turno con trescientos recibos no puede pagar trescientas lecturas de detalle para
 * sumar trescientos totales, y ademas no los necesita —el total del recibo ya esta congelado en su
 * cabecera—.
 *
 * <p>{@link #anulado} es el importe que la anulacion de ese recibo congelo, <b>no</b> el total
 * releido: {@code recibo_movimiento.importe} guarda lo que dejo de estar cobrado, y es esa cifra la
 * que sale del cajon (V30 §5). Cero cuando el recibo sigue vigente.
 *
 * @param numero el numero impreso del recibo
 * @param tipoDePago que clase de cobranza fue; decide si abona en el libro
 * @param formaDePago con que se pago; decide en que cajon del arqueo cae
 * @param total lo que el recibo cobro, congelado
 * @param anulado lo que su anulacion devolvio; {@link Dinero#CERO} si no esta anulado
 */
public record ReciboDelTurno(
        NumeroDeRecibo numero,
        TipoDePago tipoDePago,
        FormaDePago formaDePago,
        Dinero total,
        Dinero anulado) {

    public ReciboDelTurno {
        Objects.requireNonNull(numero, "Un recibo del turno se identifica por su numero");
        Objects.requireNonNull(tipoDePago, "El recibo dice que clase de cobranza fue");
        Objects.requireNonNull(formaDePago, "El recibo dice con que se pago");
        Objects.requireNonNull(total, "El recibo trae su total congelado");
        Objects.requireNonNull(anulado, "Lo anulado es cero, no nulo");
        if (total.esNegativo() || anulado.esNegativo()) {
            throw new IllegalArgumentException("Un recibo no cobra ni devuelve en negativo");
        }
        if (anulado.esMayorQue(total)) {
            throw new IllegalArgumentException(
                    "El recibo "
                            + numero.impreso()
                            + " cobro "
                            + total
                            + " y su anulacion devolvio "
                            + anulado
                            + ": el acta congela el total del recibo, no otra cifra (V30 §5)");
        }
    }

    public boolean estaAnulado() {
        return !anulado.esCero();
    }

    /** Lo que este recibo deja en el cajon: su total menos lo que su anulacion saco. */
    public Dinero neto() {
        return total.menos(anulado);
    }

    /** Si dejo asientos en el libro. Ver {@link TipoDePago#abonaEnElLibro}. */
    public boolean abonaEnElLibro() {
        return tipoDePago.abonaEnElLibro();
    }
}
