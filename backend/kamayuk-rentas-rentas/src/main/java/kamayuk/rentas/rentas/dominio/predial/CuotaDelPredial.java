package kamayuk.rentas.rentas.dominio.predial;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Una cuota del impuesto predial con el dia en que vence (#395; TUO Ley de Tributacion Municipal,
 * D.S. 156-2004-EF, art. 15).
 *
 * <p><b>El dia no se calcula, se lee.</b> El articulo 15 dice «el ultimo dia habil» de cuatro
 * meses, y que dia es eso en un ejercicio concreto depende del calendario de feriados que publica
 * cada municipalidad: se toma del conjunto sellado ({@code VENCIMIENTO_PREDIAL:‹modalidad›-‹n›}) y
 * queda amarrado al mismo conjunto que el importe.
 *
 * <p><b>Sin reajuste.</b> El articulo 15 reajusta las cuotas segun la variacion del IPM, y ese
 * indice no esta publicado en ningun conjunto todavia: la cuota que sale de aqui es la porcion del
 * impuesto, sin reajustar, y se dice —no se aplica un reajuste inventado ni se calla que falta—.
 *
 * @param numero la posicion en el cronograma, empezando en 1
 * @param vencimiento el dia en que vence
 * @param importe cuanto se paga en ella
 */
public record CuotaDelPredial(int numero, LocalDate vencimiento, Dinero importe) {

    public CuotaDelPredial {
        if (numero <= 0) {
            throw new IllegalArgumentException("Las cuotas se numeran desde 1: " + numero);
        }
        Objects.requireNonNull(vencimiento, "Toda cuota dice que dia vence");
        Objects.requireNonNull(importe, "Toda cuota dice cuanto se paga en ella");
        if (importe.esNegativo()) {
            throw new IllegalArgumentException("Una cuota no puede ser negativa: " + importe);
        }
    }
}
