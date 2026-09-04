package kamayuk.rentas.cuentacorriente.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Lo cobrado de un tributo, de un ejercicio, en un mes y en una fase, tal como lo agrega el motor
 * (#53).
 *
 * <p>Vive en {@code .dominio} y <b>no cruza</b>: quien pregunta desde otro contexto recibe {@code
 * RecaudacionDeUnTributo}, que es la proyeccion publica. La distincion es la misma que entre {@link
 * Asiento} y {@code MovimientoDelLibro}, y existe para que un cambio de esta forma interna no se
 * lleve por delante a los tres modulos que leen resumenes.
 *
 * <p><b>La suma la hace PostgreSQL, no Java.</b> Traer los asientos y sumarlos aqui significaria
 * traer todos los pagos del periodo —de un padron entero, en un ano— para escribir doce cifras.
 */
public record RecaudacionAgregada(
        String tributo, Ejercicio ejercicio, int mes, Fase fase, Dinero recaudado, long abonos) {

    public RecaudacionAgregada {
        Objects.requireNonNull(tributo, "La linea necesita su tributo");
        Objects.requireNonNull(ejercicio, "La linea necesita su ejercicio");
        Objects.requireNonNull(fase, "La linea necesita su fase");
        Objects.requireNonNull(recaudado, "La linea necesita su importe");
        if (mes < 1 || mes > 12) {
            throw new IllegalArgumentException("El mes va de 1 a 12: " + mes);
        }
    }
}
