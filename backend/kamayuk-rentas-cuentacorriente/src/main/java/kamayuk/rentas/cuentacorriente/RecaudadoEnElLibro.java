package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo recaudado de unos tributos en un rango, con su fecha (#53, RF-073, RF-074).
 *
 * <p>Es la respuesta de {@link RecaudacionDelLibro#recaudadoPor}, y viene del libro, no de quien
 * pregunta. {@link #aLaFecha} es la fecha con la que se leyo: viaja con el importe siempre (regla
 * 9, RNF-075), y con ella {@link #desde} y {@link #hasta}, porque un resumen de recaudacion
 * archivado tiene que poder decir dentro de dos anios que periodo cubria.
 *
 * <p>Una lista vacia significa que en ese periodo no se cobro nada de esos tributos, y <b>no es un
 * error</b>: un area que no recaudo un solo sol en marzo tiene un resumen de marzo en cero.
 *
 * @param lineas una por (tributo, ejercicio, mes, fase) con movimiento
 * @param desde primer dia del periodo, inclusive
 * @param hasta ultimo dia del periodo, inclusive
 * @param aLaFecha la fecha a la que se leyo el libro
 */
public record RecaudadoEnElLibro(
        List<RecaudacionDeUnTributo> lineas, LocalDate desde, LocalDate hasta, LocalDate aLaFecha) {

    public RecaudadoEnElLibro {
        Objects.requireNonNull(lineas, "La lista es vacia, no nula");
        Objects.requireNonNull(desde, "El resumen dice desde cuando cuenta");
        Objects.requireNonNull(hasta, "El resumen dice hasta cuando cuenta");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        lineas = List.copyOf(lineas);
    }

    /** La suma de todas las lineas: lo recaudado del periodo, al centimo. */
    public Dinero total() {
        Dinero total = Dinero.CERO;
        for (RecaudacionDeUnTributo linea : lineas) {
            total = total.mas(linea.recaudado());
        }
        return total;
    }

    /** Lo recaudado de un tributo concreto. */
    public Dinero de(String tributo) {
        Dinero total = Dinero.CERO;
        for (RecaudacionDeUnTributo linea : lineas) {
            if (linea.tributo().equals(tributo)) {
                total = total.mas(linea.recaudado());
            }
        }
        return total;
    }

    /** Cuantos abonos componen el total. */
    public long abonos() {
        long cuantos = 0;
        for (RecaudacionDeUnTributo linea : lineas) {
            cuantos += linea.abonos();
        }
        return cuantos;
    }
}
