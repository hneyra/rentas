package kamayuk.rentas.rentas.dominio.predial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;

/**
 * Reparte el impuesto anual entre las cuotas del cronograma (TUO Ley de Tributacion Municipal, D.S.
 * 156-2004-EF, art. 15; #395).
 *
 * <p>Funcion pura (regla 6): sin base, sin reloj. Ni el numero de cuotas ni sus fechas se deciden
 * aqui —llegan del conjunto sellado—, y ni una cifra se compila.
 *
 * <p><b>Las cuotas suman el impuesto, exactamente.</b> El articulo 15 dice que la primera cuota es
 * «equivalente a un cuarto del impuesto total», y un cuarto de un importe impar no cabe en
 * centimos: todas valen la fraccion y la <b>ultima</b> se lleva lo que sobra, que es el mismo
 * reparto que {@code Cronograma} usa en el fraccionamiento desde #35. Dejar el resto fuera produce
 * un cronograma cuya suma no es la deuda, y esa diferencia aparece en ventanilla el dia que alguien
 * paga las cuatro.
 *
 * <p><b>Sin reajuste.</b> El articulo 15 reajusta las cuotas 2 a 4 segun la variacion del IPM, y
 * ese indice no esta publicado en ningun conjunto todavia. Las cuotas de aqui son la porcion del
 * impuesto, sin reajustar: aplicar un reajuste inventado subiria lo que se cobra, y cada cuota dice
 * de que conjunto salio para que se vea que no lo lleva.
 */
public final class CronogramaDelPredial {

    /**
     * Precision de los intermedios, como en {@code Cronograma} (#35): el cociente exacto de dividir
     * entre tres no existe, y {@code MathContext} acota la precision sin escribir ningun modo de
     * redondeo en el codigo —el unico redondeo con efecto es el de {@link PuntoDeRedondeo#CUOTA},
     * que sale del conjunto sellado (D-03b, ADR-0018)—.
     */
    private static final java.math.MathContext INTERMEDIO = java.math.MathContext.DECIMAL64;

    private CronogramaDelPredial() {}

    /**
     * Las cuotas del impuesto, una por vencimiento, en el orden del cronograma.
     *
     * @param impuestoAnual el impuesto ya determinado
     * @param vencimientos los dias en que vencen, del conjunto sellado; nunca vacio
     * @param redondeo las politicas del conjunto; la cuota se redondea en {@link
     *     PuntoDeRedondeo#CUOTA}
     */
    public static List<CuotaDelPredial> repartir(
            Dinero impuestoAnual, List<LocalDate> vencimientos, PoliticasDeRedondeo redondeo) {
        Objects.requireNonNull(impuestoAnual, "El cronograma necesita el impuesto que reparte");
        Objects.requireNonNull(vencimientos, "El cronograma necesita sus vencimientos");
        Objects.requireNonNull(redondeo, "Las politicas de redondeo se reciben, no se fijan");
        if (vencimientos.isEmpty()) {
            throw new IllegalArgumentException(
                    "Un cronograma sin ninguna fecha de vencimiento no es un cronograma");
        }
        if (impuestoAnual.esNegativo()) {
            throw new IllegalArgumentException(
                    "El impuesto que se reparte no puede ser negativo: " + impuestoAnual);
        }

        int cuantas = vencimientos.size();
        Dinero fraccion =
                impuestoAnual
                        .por(BigDecimal.ONE.divide(BigDecimal.valueOf(cuantas), INTERMEDIO))
                        .redondeadoEn(PuntoDeRedondeo.CUOTA, redondeo);

        List<CuotaDelPredial> cuotas = new ArrayList<>();
        Dinero repartido = Dinero.CERO;
        for (int i = 0; i < cuantas; i++) {
            boolean ultima = i == cuantas - 1;
            Dinero importe = ultima ? impuestoAnual.menos(repartido) : fraccion;
            cuotas.add(new CuotaDelPredial(i + 1, vencimientos.get(i), importe));
            repartido = repartido.mas(importe);
        }
        return List.copyOf(cuotas);
    }
}
