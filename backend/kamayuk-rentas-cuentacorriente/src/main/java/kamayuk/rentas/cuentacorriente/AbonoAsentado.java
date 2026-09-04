package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo que {@link RegistroDeAbonos} <b>abono de verdad</b> en una obligacion, con su desglose y su
 * fecha.
 *
 * <p>Es la respuesta a «cuanto se cobro», y viene del libro, no de quien pidio cobrar. La caja la
 * usa tal cual para el detalle del recibo: el desglose que se imprime es el que se asento, no uno
 * que la ventanilla recomponga sumando o restando por su cuenta.
 *
 * <p>{@link #fecha} es la fecha a la que se releyo la deuda —la fecha de pago—, y viaja con el
 * importe siempre (regla 9, RNF-075). Sin ella, el duplicado de un recibo de marzo no podria
 * explicar por que su interes no es el de hoy.
 *
 * @param obligacion cual de las marcadas es
 * @param fecha la fecha a la que se releyo la deuda y se imputaron los asientos
 * @param insoluto el tributo abonado, sin reajuste ni interes
 * @param reajuste el reajuste abonado
 * @param interes el interes moratorio abonado
 * @param gasto los gastos abonados
 */
public record AbonoAsentado(
        SeleccionDeObligacion obligacion,
        LocalDate fecha,
        Dinero insoluto,
        Dinero reajuste,
        Dinero interes,
        Dinero gasto) {

    public AbonoAsentado {
        Objects.requireNonNull(obligacion, "El abono dice de que obligacion es");
        Objects.requireNonNull(fecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        Objects.requireNonNull(insoluto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(reajuste, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(interes, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(gasto, "El desglose siempre trae sus cuatro partes");
    }

    /** La suma de las cuatro partes, nunca una quinta cifra calculada aparte. */
    public Dinero total() {
        return insoluto.mas(reajuste).mas(interes).mas(gasto);
    }
}
