package kamayuk.rentas.nucleo.dominio.alcabala;

import java.math.BigDecimal;
import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;

/**
 * El impuesto de alcabala: la alícuota sobre el exceso de la base imponible por encima del tramo
 * inafecto (TUO Ley de Tributación Municipal, D.S. 156-2004-EF, art. 25; #32).
 *
 * <p><b>Ninguna cifra vive aquí</b> (regla 5): la base ya viene elegida por {@link
 * BaseImponibleDeAlcabala}, el tramo inafecto y la alícuota llegan resueltos por quien invoca —el
 * tramo desde la UIT del ejercicio, la alícuota del conjunto sellado—.
 *
 * <p><b>No redondea</b>, por el mismo motivo que {@code
 * kamayuk.rentas.nucleo.dominio.vehicular.ImpuestoVehicular}: D-03c no ha identificado todavía un
 * punto de redondeo para la alcabala.
 */
public final class ImpuestoDeAlcabala {

    private ImpuestoDeAlcabala() {}

    /**
     * El impuesto: {@code max(base - tramoInafecto, 0) × alícuota}. Nunca negativo: una base que no
     * supera el tramo inafecto no genera impuesto.
     */
    public static Dinero calcular(Dinero base, Dinero tramoInafecto, Alicuota alicuota) {
        Objects.requireNonNull(base, "El calculo necesita la base imponible ya elegida");
        Objects.requireNonNull(
                tramoInafecto, "El calculo necesita el tramo inafecto del ejercicio");
        Objects.requireNonNull(alicuota, "El calculo necesita la alicuota vigente");

        Dinero excedente = base.menos(tramoInafecto);
        Dinero excedenteAfecto = excedente.esNegativo() ? Dinero.CERO : excedente;
        return excedenteAfecto.por(comoFraccion(alicuota));
    }

    private static BigDecimal comoFraccion(Alicuota alicuota) {
        return alicuota.valor().divide(BigDecimal.valueOf(100));
    }
}
