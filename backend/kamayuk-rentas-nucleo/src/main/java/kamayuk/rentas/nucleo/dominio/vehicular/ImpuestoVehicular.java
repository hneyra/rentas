package kamayuk.rentas.nucleo.dominio.vehicular;

import java.math.BigDecimal;
import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.nucleo.dominio.predial.MinimoImponible;

/**
 * El impuesto al patrimonio vehicular de un vehículo afecto: {@code valor referencial × alícuota},
 * con el mínimo imponible del ejercicio (TUO Ley de Tributación Municipal, D.S. 156-2004-EF, arts.
 * 30 a 37; #32).
 *
 * <p><b>Ninguna cifra vive aquí</b> (regla 5). El valor referencial y la alícuota llegan ya
 * resueltos por quien invoca —el primero de la tabla del conjunto sellado (#26), la segunda del
 * mismo conjunto (#32)— y el mínimo imponible como argumento, igual que {@code
 * RegistrarDeterminacionPredial} recibe el suyo: del origen del mínimo del vehicular no está
 * decidido el formato (D-02a), y fijarlo aquí lo congelaría antes de tiempo.
 *
 * <p><b>No redondea.</b> Como {@code RT001ValorDeTerreno}, esta clase deja el producto sin tocar
 * ({@link Dinero#por} nunca redondea): D-03c no ha identificado todavía un punto de redondeo para
 * el vehicular —la campaña de {@code docs/10-negocio/observaciones-srtm-mef/} solo cubre el
 * predial— y añadir uno sin haberlo observado sería inventar la respuesta que esa campaña existe
 * para dar.
 */
public final class ImpuestoVehicular {

    private ImpuestoVehicular() {}

    /**
     * El impuesto del vehículo: el mayor entre {@code valorReferencial × alícuota} y el mínimo
     * imponible.
     *
     * @param valorReferencial la base imponible: el valor del vehículo en la tabla del ejercicio
     * @param alicuota la alícuota vigente del ejercicio, leída del conjunto sellado
     * @param minimoImponible el mínimo del ejercicio; nunca reduce el resultado, solo lo eleva
     */
    public static Dinero calcular(
            Dinero valorReferencial, Alicuota alicuota, Dinero minimoImponible) {
        Objects.requireNonNull(valorReferencial, "El calculo necesita el valor referencial");
        Objects.requireNonNull(alicuota, "El calculo necesita la alicuota vigente");
        Objects.requireNonNull(minimoImponible, "El calculo necesita el minimo imponible");
        Dinero bruto = valorReferencial.por(comoFraccion(alicuota));
        return MinimoImponible.aplicar(bruto, minimoImponible);
    }

    /**
     * {@link Alicuota} viaja en tanto por ciento (0 a 100); {@link Dinero#por} exige la fraccion.
     */
    private static BigDecimal comoFraccion(Alicuota alicuota) {
        return alicuota.valor().divide(BigDecimal.valueOf(100));
    }
}
