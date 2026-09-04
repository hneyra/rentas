package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo que un conjunto de documentos abono en el libro, con su fecha (#36, RF-087).
 *
 * <p>Es la respuesta de {@link ConciliacionDeCaja#abonadoPor}, y viene del libro, no de quien
 * pregunta. {@link #aLaFecha} es la fecha con la que se ley: viaja con el importe siempre (regla 9,
 * RNF-075), porque un cierre de caja archivado tiene que poder decir dentro de dos anios contra que
 * dia cuadro.
 *
 * <p>Un documento que no asento nada vale {@link Dinero#CERO}, y eso <b>no es un error</b>: un
 * recibo de caja de tasas o de una cuota inicial de convenio no toca el libro nunca. Por eso {@link
 * #de} devuelve cero en vez de fallar: quien cuadra ya sabe cuales de sus recibos abonan y cuales
 * no, y este tipo no tiene por que adivinarlo.
 *
 * @param porDocumento el importe abonado por cada documento preguntado
 * @param aLaFecha la fecha a la que se ley el libro
 */
public record AbonadoEnElLibro(Map<String, Dinero> porDocumento, LocalDate aLaFecha) {

    public AbonadoEnElLibro {
        Objects.requireNonNull(porDocumento, "El mapa es vacio, no nulo");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        porDocumento = Map.copyOf(porDocumento);
    }

    /** Lo abonado por ese documento; cero si no asento nada. */
    public Dinero de(String documento) {
        return porDocumento.getOrDefault(documento, Dinero.CERO);
    }

    /** La suma de todo lo abonado por los documentos preguntados. */
    public Dinero total() {
        Dinero total = Dinero.CERO;
        for (Dinero abonado : porDocumento.values()) {
            total = total.mas(abonado);
        }
        return total;
    }
}
