package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Lo cargado en el libro en un ejercicio, con su fecha (#56, RF-130).
 *
 * <p>Es la respuesta de {@link CarteraDelLibro#cargadoPorTributo}. {@link #aLaFecha} es la fecha
 * con la que se leyo el libro: viaja con el importe siempre (regla 9, RNF-075), y con ella el
 * {@link #ejercicio}, porque «lo cargado» de 2025 y de 2026 son cifras distintas y un panel
 * archivado tiene que poder decir cual estaba mirando.
 *
 * <p>Una lista vacia significa que en ese ejercicio no se asento ningun cargo, y <b>no es un
 * error</b>: es lo que ve una municipalidad recien implantada, o el ejercicio siguiente antes de la
 * emision anual.
 *
 * @param lineas una por tributo con cargos en el ejercicio
 * @param ejercicio el ejercicio que se sumo
 * @param aLaFecha la fecha a la que se leyo el libro
 */
public record CargadoEnElLibro(
        List<CargoDeUnTributo> lineas, Ejercicio ejercicio, LocalDate aLaFecha) {

    public CargadoEnElLibro {
        Objects.requireNonNull(lineas, "La lista es vacia, no nula");
        Objects.requireNonNull(ejercicio, "Lo cargado siempre es de un ejercicio");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        lineas = List.copyOf(lineas);
    }

    /** La suma de todas las lineas: lo cargado del ejercicio, al centimo. */
    public Dinero total() {
        Dinero total = Dinero.CERO;
        for (CargoDeUnTributo linea : lineas) {
            total = total.mas(linea.cargado());
        }
        return total;
    }

    /** Lo cargado de un tributo concreto; cero si ese tributo no tiene cargos. */
    public Dinero de(String tributo) {
        Dinero total = Dinero.CERO;
        for (CargoDeUnTributo linea : lineas) {
            if (linea.tributo().equals(tributo)) {
                total = total.mas(linea.cargado());
            }
        }
        return total;
    }
}
