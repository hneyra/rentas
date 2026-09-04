package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una linea del libro tal como cruza la frontera del modulo: un pago, un alta o una baja (#25).
 *
 * <p>Es a {@link MovimientosDelLibro} lo que {@link ObligacionPublica} es a {@link
 * ConsultaDeDeudaPublica}: la proyeccion de {@code Asiento} —que vive en {@code .dominio} y no
 * cruza— reducida a lo que un consumidor externo pinta en una grilla. Sin {@code contribuyenteId}
 * ni {@code usuarioId}: el primero ya lo sabe quien pregunta, y el segundo es del expediente de
 * auditoria, no de una consulta.
 *
 * <p><b>{@link #fechaValor} no es decorativa</b> (regla 9, RNF-075): es el dia al que corresponde
 * {@link #monto}, y es lo que permite que la ficha del contribuyente no presente un pago de marzo
 * como si fuera de hoy. El importe de un asiento no se actualiza —lo que se asento, se asento—, asi
 * que su fecha es la de su valor y no la de la consulta.
 *
 * @param id el identificador del asiento, para poder pedir su detalle
 * @param ejercicio el ejercicio de la obligacion, no el de la fecha de pago
 * @param tributo el tributo de la obligacion
 * @param concepto insoluto, reajuste, interes, gasto, pago, condonacion…
 * @param tipo {@code CARGO} o {@code ABONO}
 * @param fase en que fase de la cobranza estaba la obligacion cuando se asento
 * @param periodo la cuota o el mes; nulo es anual
 * @param predioId la unidad, si la obligacion es predial o de arbitrios
 * @param vehiculoId la unidad, si la obligacion es vehicular
 * @param monto lo asentado
 * @param fechaValor el dia al que corresponde {@code monto} (regla 9, RNF-075)
 * @param documentoOrigen el papel que lo sustenta: el recibo, la resolucion, la determinacion
 * @param motivo por que se registro; obligatorio en anulacion, condonacion y ajuste (RNF-052)
 */
public record MovimientoDelLibro(
        long id,
        Ejercicio ejercicio,
        String tributo,
        String concepto,
        String tipo,
        String fase,
        @Nullable Integer periodo,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        Dinero monto,
        LocalDate fechaValor,
        String documentoOrigen,
        @Nullable String motivo) {

    public MovimientoDelLibro {
        Objects.requireNonNull(ejercicio, "El movimiento necesita su ejercicio");
        Objects.requireNonNull(tributo, "El movimiento necesita su tributo");
        Objects.requireNonNull(concepto, "El movimiento necesita su concepto");
        Objects.requireNonNull(tipo, "El movimiento dice si carga o abona");
        Objects.requireNonNull(fase, "El movimiento necesita su fase");
        Objects.requireNonNull(monto, "El movimiento necesita su importe");
        Objects.requireNonNull(
                fechaValor, "Toda cifra indica a que fecha corresponde (RNF-075, regla 9)");
        Objects.requireNonNull(documentoOrigen, "Todo asiento dice que papel lo sustenta");
    }
}
