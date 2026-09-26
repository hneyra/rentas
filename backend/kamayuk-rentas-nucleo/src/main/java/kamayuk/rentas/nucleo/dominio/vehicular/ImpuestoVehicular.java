package kamayuk.rentas.nucleo.dominio.vehicular;

import java.math.BigDecimal;
import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.nucleo.dominio.predial.MinimoImponible;

/**
 * El impuesto al patrimonio vehicular de un vehículo afecto: {@code base imponible × alícuota}, con
 * el mínimo imponible del ejercicio (TUO Ley de Tributación Municipal, D.S. 156-2004-EF, arts. 30 a
 * 37; #32).
 *
 * <p><b>Ninguna cifra vive aquí</b> (regla 5). La base y la alícuota llegan ya resueltas por quien
 * invoca —la primera por {@link BaseImponibleVehicular#segunArticulo32}, que compara el valor de
 * adquisición con la tabla del conjunto sellado (#330); la segunda del mismo conjunto (#32)—. Hasta
 * #330 recibía «el valor referencial», y ese nombre era el defecto: la base del art. 32 no es la
 * tabla, es el mayor de los dos. y el mínimo imponible como argumento, igual que {@code
 * RegistrarDeterminacionPredial} recibe el suyo: del origen del mínimo del vehicular no está
 * decidido el formato (D-02a), y fijarlo aquí lo congelaría antes de tiempo.
 *
 * <p><b>Redondea al cierre, en {@link #PUNTO_DE_REDONDEO}</b> (#378). Hasta #378 no redondeaba: su
 * javadoc decia que D-03c no habia identificado un punto para el vehicular, y era cierto al
 * escribirse —llego de {@code sgtm} dos dias antes de ADR-0018—, pero ADR-0018 de {@code normativa}
 * cerro D-03a, D-03b y D-03c: «se redondea al cierre de cada regla, a centimo (escala 2), {@code
 * HALF_UP}». Mientras tanto la columna {@code dinero numeric(15,2)} redondeaba por su cuenta al
 * guardar, y la respuesta y la auditoria decian otra cifra que la fila. La escala y el modo no
 * viven aqui: llegan en la {@link PoliticaDeRedondeo} que quien invoca lee del conjunto sellado
 * para ese punto, y si el conjunto no la publica el calculo falla en vez de no redondear.
 */
public final class ImpuestoVehicular {

    /**
     * Donde cierra esta regla: el impuesto ya comparado con el minimo, como {@code IMPUESTO_ANUAL}
     * en el predial. Quien invoca pide su politica al conjunto sellado con este punto.
     */
    public static final PuntoDeRedondeo PUNTO_DE_REDONDEO = PuntoDeRedondeo.IMPUESTO_VEHICULAR;

    private ImpuestoVehicular() {}

    /**
     * El impuesto del vehículo: el mayor entre {@code base × alícuota} y el mínimo imponible.
     *
     * @param base la base imponible del art. 32, con su origen
     * @param alicuota la alícuota vigente del ejercicio, leída del conjunto sellado
     * @param minimoImponible el mínimo del ejercicio; nunca reduce el resultado, solo lo eleva
     * @param redondeo la politica del conjunto sellado para {@link #PUNTO_DE_REDONDEO} (#378)
     */
    public static Dinero calcular(
            BaseImponibleVehicular base,
            Alicuota alicuota,
            Dinero minimoImponible,
            PoliticaDeRedondeo redondeo) {
        Objects.requireNonNull(base, "El calculo necesita la base imponible del art. 32");
        Objects.requireNonNull(alicuota, "El calculo necesita la alicuota vigente");
        Objects.requireNonNull(minimoImponible, "El calculo necesita el minimo imponible");
        Objects.requireNonNull(redondeo, "La politica de redondeo se recibe, no se fija (#378)");
        Dinero bruto = base.valor().por(comoFraccion(alicuota));
        return MinimoImponible.aplicar(bruto, minimoImponible).redondeadoCon(redondeo);
    }

    /**
     * {@link Alicuota} viaja en tanto por ciento (0 a 100); {@link Dinero#por} exige la fraccion.
     */
    private static BigDecimal comoFraccion(Alicuota alicuota) {
        return alicuota.valor().divide(BigDecimal.valueOf(100));
    }
}
