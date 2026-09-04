package kamayuk.rentas.tesoreria;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * La constancia de que un recibo cobro un concepto del TUPA, tal como cruza la frontera del modulo
 * (#50).
 *
 * <p>Es a {@link CobrosDeTasas} lo que {@code ObligacionPublica} es a {@code
 * ConsultaDeDeudaPublica}: la proyeccion de {@code Recibo} —que vive en {@code .dominio} y no
 * cruza— reducida a lo que un consumidor externo necesita para <b>acreditar un pago</b>.
 *
 * <p>{@link #fecha} no es decorativa (regla 9, RNF-075): es el dia al que corresponde {@link
 * #importe}, y es lo que permite que el acta de liberacion diga cuando se pago la custodia en vez
 * de dar una cifra sin dia.
 *
 * @param numeroDeRecibo como esta impreso en el papel, {@code 001-0000123}
 * @param codigoDeTasa el concepto del TUPA que se cobro
 * @param cantidad cuantas unidades se cobraron; en la custodia, los dias
 * @param importe lo cobrado por ese concepto
 * @param fecha el dia del cobro (regla 9, RNF-075)
 */
public record TasaCobrada(
        String numeroDeRecibo, String codigoDeTasa, int cantidad, Dinero importe, LocalDate fecha) {

    public TasaCobrada {
        Objects.requireNonNull(numeroDeRecibo, "La constancia necesita el numero del recibo");
        Objects.requireNonNull(codigoDeTasa, "La constancia necesita el concepto cobrado");
        Objects.requireNonNull(importe, "La constancia necesita su importe");
        Objects.requireNonNull(fecha, "Toda cifra indica a que fecha corresponde (RNF-075)");
        if (cantidad <= 0) {
            throw new IllegalArgumentException(
                    "Un cobro de una tasa es de al menos una unidad; llego " + cantidad);
        }
    }
}
