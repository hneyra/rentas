package kamayuk.rentas.nucleo.dominio.alcabala;

import java.math.BigDecimal;
import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;

/**
 * El impuesto de alcabala: la alícuota sobre el exceso de la base imponible por encima del tramo
 * inafecto (TUO Ley de Tributación Municipal, D.S. 156-2004-EF, art. 25; #32).
 *
 * <p><b>Ninguna cifra vive aquí</b> (regla 5): la base ya viene elegida por {@link
 * BaseImponibleDeAlcabala}, el tramo inafecto y la alícuota llegan resueltos por quien invoca —el
 * tramo desde la UIT del ejercicio, la alícuota del conjunto sellado—.
 *
 * <p><b>Redondea al cierre, en {@link #PUNTO_DE_REDONDEO}</b> (#378), por el mismo motivo que
 * {@code kamayuk.rentas.nucleo.dominio.vehicular.ImpuestoVehicular}: ADR-0018 de {@code normativa}
 * cerro D-03 —«al cierre de cada regla, a centimo (escala 2), {@code HALF_UP}»— y hasta #378 esta
 * regla devolvia el producto crudo, que la columna {@code dinero} redondeaba por su cuenta al
 * guardar mientras la respuesta y la auditoria decian otra cifra. La politica llega del conjunto
 * sellado, no se escribe aqui.
 */
public final class ImpuestoDeAlcabala {

    /**
     * Donde cierra esta regla. Quien invoca pide su politica al conjunto sellado con este punto.
     */
    public static final PuntoDeRedondeo PUNTO_DE_REDONDEO = PuntoDeRedondeo.IMPUESTO_ALCABALA;

    private ImpuestoDeAlcabala() {}

    /**
     * El impuesto: {@code max(base - tramoInafecto, 0) × alícuota}, redondeado con la politica de
     * {@link #PUNTO_DE_REDONDEO}. Nunca negativo: una base que no supera el tramo inafecto no
     * genera impuesto.
     */
    public static Dinero calcular(
            Dinero base, Dinero tramoInafecto, Alicuota alicuota, PoliticaDeRedondeo redondeo) {
        Objects.requireNonNull(base, "El calculo necesita la base imponible ya elegida");
        Objects.requireNonNull(
                tramoInafecto, "El calculo necesita el tramo inafecto del ejercicio");
        Objects.requireNonNull(alicuota, "El calculo necesita la alicuota vigente");
        Objects.requireNonNull(redondeo, "La politica de redondeo se recibe, no se fija (#378)");

        Dinero excedente = base.menos(tramoInafecto);
        Dinero excedenteAfecto = excedente.esNegativo() ? Dinero.CERO : excedente;
        return excedenteAfecto.por(comoFraccion(alicuota)).redondeadoCon(redondeo);
    }

    private static BigDecimal comoFraccion(Alicuota alicuota) {
        return alicuota.valor().divide(BigDecimal.valueOf(100));
    }
}
