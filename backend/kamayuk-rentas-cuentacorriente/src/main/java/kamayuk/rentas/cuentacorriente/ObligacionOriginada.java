package kamayuk.rentas.cuentacorriente;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Una deuda del libro que originaron unos documentos concretos, via {@link
 * ConsultaDeLoOriginado#deLoOriginadoPor} (#342).
 *
 * <p>Es una <b>clave de saldo</b> —con su periodo—, no una obligacion agregada: la diferencia que
 * una fiscalizacion determina de oficio se asienta sin periodo y es una deuda distinta de las
 * cuotas ordinarias del mismo tributo, ejercicio y unidad. Por eso lleva el {@link #periodo} que
 * {@link ObligacionPublica} no tiene, y por eso un consumidor que agrupe por unidad y ejercicio
 * puede recibir varias —la del tributo y la de la multa que asento la misma resolucion—.
 *
 * @param obligacion la deuda de esa clave a la fecha, con su desglose y su fase; cuenta
 *     <b>todos</b> sus asientos, tambien los abonos con el documento del recibo
 * @param periodo la cuota, o 0 si es anual —el cargo de oficio—
 * @param documentosDeOrigen cuales de los documentos preguntados tienen en ella un cargo de origen,
 *     normalizados a mayusculas
 */
public record ObligacionOriginada(
        ObligacionPublica obligacion, int periodo, Set<String> documentosDeOrigen) {

    public ObligacionOriginada {
        Objects.requireNonNull(obligacion, "La deuda originada es una obligacion del libro");
        Objects.requireNonNull(documentosDeOrigen, "Dice que documento la origino");
        documentosDeOrigen = Set.copyOf(documentosDeOrigen);
        if (documentosDeOrigen.isEmpty()) {
            throw new IllegalArgumentException(
                    "Una deuda originada lo fue por algun documento: sin el no se distingue de la"
                            + " ordinaria (#342)");
        }
    }

    /** Si este documento —comparado sin distinguir mayusculas— la origino. */
    public boolean originadaPor(String documento) {
        Objects.requireNonNull(documento, "Hay que decir por que documento se pregunta");
        return documentosDeOrigen.contains(documento.strip().toUpperCase(Locale.ROOT));
    }
}
