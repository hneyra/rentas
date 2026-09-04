package kamayuk.rentas.rentas.dominio.beneficios;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo que produce simular el acogimiento de una deuda a una campana (#72).
 *
 * <p><b>Simular no es acoger.</b> Aqui no hay ningun asiento: la deuda del libro no se toca, y
 * condonar de verdad es un apunte con su motivo y su observacion que escribira quien tenga la
 * ordenanza firmada —el mismo criterio con que #33 dejo {@code recibo.campania_beneficio} como
 * «SOLO constancia»—.
 *
 * <p>No lleva la deuda acogida ni cuantas obligaciones son: eso existe con campana y sin ella, asi
 * que vive donde vive la consulta. Aqui solo esta <b>lo que la campana produce</b>, que es
 * exactamente lo que no se puede publicar cuando no hay ninguna elegida.
 *
 * @param baseDelBeneficio la parte de la deuda acogida sobre la que corre el descuento
 * @param ahorro cuanto descuenta la campana, ya redondeado con la politica que ella trae
 * @param deudaConBeneficio lo que quedaria por pagar: {@code deudaAcogida - ahorro}
 */
public record AcogimientoSimulado(
        Dinero baseDelBeneficio, Dinero ahorro, Dinero deudaConBeneficio) {

    public AcogimientoSimulado {
        Objects.requireNonNull(baseDelBeneficio, "El descuento corre sobre una base");
        Objects.requireNonNull(ahorro, "La simulacion dice cuanto se ahorra");
        Objects.requireNonNull(deudaConBeneficio, "La simulacion dice cuanto quedaria");
    }
}
