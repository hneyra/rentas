package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo que {@link AcogimientoAConvenio} movio de verdad, con su fecha (#35).
 *
 * <p>Viene del libro, no de quien pidio moverlo. El acta del convenio lo copia tal cual: el importe
 * que se congela ahi es el que se asento, no el que alguien recuerde haber acogido.
 *
 * <p>{@link #asientos} cuenta <b>filas del libro</b> y {@link #importe} cuenta <b>dinero</b>: son
 * dos unidades distintas y por eso van en dos campos y no en uno. Las filas no son siempre dos por
 * cuota —antes de mover se cristaliza el devengo que todavia no estaba asentado, y eso agrega las
 * suyas—, asi que deducirlas de la lista seria deducir mal.
 *
 * @param movidas una fila por cuota que tenia deuda, en el orden en que se movieron
 * @param asientos cuantas filas se escribieron en el libro; nunca las que se borraron, porque no se
 *     borra ninguna
 * @param fecha la fecha valor con la que se asentaron (regla 9, RNF-075)
 */
public record MovimientoAsentado(List<DeudaAcogida> movidas, int asientos, LocalDate fecha) {

    public MovimientoAsentado {
        Objects.requireNonNull(movidas, "La lista es vacia, no nula");
        movidas = List.copyOf(movidas);
        Objects.requireNonNull(fecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        if (asientos < 0) {
            throw new IllegalArgumentException(
                    "Un movimiento escribe asientos, no los quita: " + asientos);
        }
    }

    /** Vacio: ninguna cuota tenia deuda que mover a esa fecha. */
    public static MovimientoAsentado nada(LocalDate fecha) {
        return new MovimientoAsentado(List.of(), 0, fecha);
    }

    /** Lo que se movio, sumando las cuotas. Nunca una cifra calculada aparte. */
    public Dinero importe() {
        Dinero total = Dinero.CERO;
        for (DeudaAcogida movida : movidas) {
            total = total.mas(movida.total());
        }
        return total;
    }

    public boolean estaVacio() {
        return movidas.isEmpty();
    }
}
