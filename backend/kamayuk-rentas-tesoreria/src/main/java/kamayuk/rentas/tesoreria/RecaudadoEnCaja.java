package kamayuk.rentas.tesoreria;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo que la caja cobro y anulo en un dia, con su fecha (#56, #36, RF-088).
 *
 * <p>Es la respuesta de {@link AvanceDeCaja#delDia} y sale del <b>detalle congelado de los
 * recibos</b>, no del libro: es lo que la ventanilla cobro. La distincion importa cuando esta cifra
 * se pone al lado de la del libro en un panel —{@code cuentacorriente.RecaudacionDelLibro}—, porque
 * las dos son ciertas y no tienen por que coincidir: por caja entra tambien lo que no es deuda
 * tributaria —las tasas del TUPA—, y al libro llega tambien lo que no entro por ventanilla.
 *
 * <p>{@link #anulado} se publica en vez de restarse en silencio, por lo mismo que en {@code
 * RecaudacionDeTributo}: un avance que solo mostrara el neto no podria explicar por que a media
 * tarde dice menos que a mediodia.
 *
 * @param cobrado lo que las lineas del dia sumaron, anuladas incluidas
 * @param anulado lo que de eso pertenecia a recibos que se anularon
 * @param dia el dia de los turnos que se sumaron
 * @param aLaFecha la fecha a la que se leyo (regla 9, RNF-075)
 */
public record RecaudadoEnCaja(Dinero cobrado, Dinero anulado, LocalDate dia, LocalDate aLaFecha) {

    public RecaudadoEnCaja {
        Objects.requireNonNull(cobrado, "El avance del dia trae lo cobrado");
        Objects.requireNonNull(anulado, "El avance del dia trae lo anulado");
        Objects.requireNonNull(dia, "El avance es de un dia concreto (regla 6)");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
    }

    /** Lo que de verdad quedo recaudado ese dia: lo cobrado menos lo anulado. */
    public Dinero neto() {
        return cobrado.menos(anulado);
    }
}
