package kamayuk.rentas.cuentacorriente;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Una linea de lo cargado: cuanto se puso a cobrar de un tributo en un ejercicio (#56).
 *
 * <p>Es a {@link CarteraDelLibro} lo que {@link RecaudacionDeUnTributo} es a {@link
 * RecaudacionDelLibro}: la proyeccion que cruza la frontera del modulo. No lleva su fecha —la lleva
 * {@link CargadoEnElLibro}, que es la respuesta entera (regla 9)—.
 *
 * @param tributo el tributo de las obligaciones cargadas
 * @param cargado la suma de los cargos de insoluto vivos de ese tributo
 * @param cargos cuantos asientos la componen
 */
public record CargoDeUnTributo(String tributo, Dinero cargado, long cargos) {

    public CargoDeUnTributo {
        Objects.requireNonNull(tributo, "La linea necesita su tributo");
        Objects.requireNonNull(cargado, "La linea necesita su importe");
        if (cargos < 0) {
            throw new IllegalArgumentException("El numero de cargos no puede ser negativo");
        }
    }
}
