package kamayuk.rentas.cuentacorriente;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Una linea del resumen de recaudacion: lo cobrado de un tributo, de un ejercicio, en un mes y en
 * una fase (#53).
 *
 * <p>Es a {@link RecaudacionDelLibro} lo que {@link MovimientoDelLibro} es a {@link
 * MovimientosDelLibro}: la proyeccion que cruza la frontera del modulo. No lleva {@code
 * contribuyenteId} ni {@code documentoOrigen} —un resumen de area no los pinta— y no lleva su
 * fecha: la lleva {@link RecaudadoEnElLibro}, que es la respuesta entera (regla 9).
 *
 * @param tributo el tributo de la obligacion cobrada
 * @param ejercicio el ejercicio de la obligacion, no el de la fecha de pago
 * @param mes el mes de la {@code fecha_valor} del abono, de 1 a 12
 * @param fase en que fase de la cobranza estaba la obligacion cuando se cobro
 * @param recaudado la suma de los abonos vivos de ese grupo
 * @param abonos cuantos asientos la componen; sin el, «300,00» no dice si son tres pagos o uno
 */
public record RecaudacionDeUnTributo(
        String tributo, Ejercicio ejercicio, int mes, String fase, Dinero recaudado, long abonos) {

    public RecaudacionDeUnTributo {
        Objects.requireNonNull(tributo, "La linea necesita su tributo");
        Objects.requireNonNull(ejercicio, "La linea necesita su ejercicio");
        Objects.requireNonNull(fase, "La linea necesita su fase");
        Objects.requireNonNull(recaudado, "La linea necesita su importe");
        if (mes < 1 || mes > 12) {
            throw new IllegalArgumentException("El mes va de 1 a 12: " + mes);
        }
        if (abonos < 0) {
            throw new IllegalArgumentException("El numero de abonos no puede ser negativo");
        }
    }
}
