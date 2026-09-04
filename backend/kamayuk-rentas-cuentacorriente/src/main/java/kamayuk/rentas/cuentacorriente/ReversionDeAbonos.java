package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo que {@link RegistroDeAbonos#reversarAbonos} deshizo, con su fecha (#34, RF-083).
 *
 * <p>Es la respuesta a «cuanto volvio a deber», y viene del libro, no de quien pidio anular. La
 * anulacion la copia tal cual en su acta: el importe que se congela ahi es el que se reverso, no el
 * que alguien recuerde haber cobrado.
 *
 * <p>{@link #abonado} cuenta <b>solo los abonos</b> reversados, no los cargos. La reversion escribe
 * las dos clases —al cobrar se cristalizo el devengo con un cargo, y deshacer la cobranza deshace
 * las dos mitades—, pero lo que el contribuyente pago es la suma de los abonos. Sumar tambien los
 * cargos daria una cifra que no coincide con ningun recibo y que nadie sabria interpretar.
 *
 * @param asientos cuantas filas se escribieron en el libro; nunca las que se borraron, porque no se
 *     borra ninguna
 * @param abonado lo que se devuelve a deber: la suma de los abonos reversados
 * @param fecha la fecha valor de la reversion (regla 9, RNF-075)
 */
public record ReversionDeAbonos(int asientos, Dinero abonado, LocalDate fecha) {

    public ReversionDeAbonos {
        Objects.requireNonNull(abonado, "Toda cifra indica su fecha, y viene con ella");
        Objects.requireNonNull(fecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        if (asientos < 0) {
            throw new IllegalArgumentException(
                    "Una reversion escribe asientos, no los quita: " + asientos);
        }
    }
}
