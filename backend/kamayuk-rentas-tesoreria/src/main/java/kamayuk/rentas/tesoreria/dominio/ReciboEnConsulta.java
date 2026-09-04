package kamayuk.rentas.tesoreria.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Una fila del listado de recibos (#548, RF-082): lo que la grilla «Recibos localizados» pinta.
 *
 * <p><b>Sin el desglose.</b> Una pagina de veinte filas no puede costar veinte lecturas de {@code
 * recibo_detalle}; quien quiere el detalle abre el recibo por su numero, que ya tiene ruta. Es el
 * mismo reparto que {@code ConvenioEnConsulta} hace con el cronograma.
 *
 * <p><b>El importe viaja con su fecha</b> (regla 9, RNF-075). {@link #actualizadoA} es la fecha a
 * la que estaban actualizados los importes que el recibo cobro —la de pago en caja tributaria, la
 * de vigencia de la tarifa en caja de tasas—, congelada al emitir. No es «hoy», y por eso no se
 * calcula al leer: un recibo de marzo tiene que seguir explicando en 2037 por que su interes no era
 * el de hoy.
 *
 * <p>{@link #estado} y {@link #duplicados} se <b>derivan</b> de {@code recibo_movimiento} (V30): el
 * recibo no guarda ni lo uno ni lo otro, porque el recibo no se actualiza (V29).
 *
 * @param id el identificador interno, para que la fila se pueda seguir
 * @param numero la serie y el correlativo; se imprime {@code 001-0000123}
 * @param contribuyenteId a quien se le cobro; el nombre lo resuelve el padron, no esta tabla
 * @param emitidoEn el instante de emision: de ahi salen la fecha y la hora de la grilla
 * @param cajero quien cobro
 * @param formaDePago con que se pago
 * @param total lo cobrado
 * @param actualizadoA a que fecha estaba actualizado {@link #total}
 * @param estado derivado del movimiento de anulacion
 * @param duplicados cuantas veces se ha reimpreso ya
 */
public record ReciboEnConsulta(
        long id,
        NumeroDeRecibo numero,
        long contribuyenteId,
        Instant emitidoEn,
        String cajero,
        FormaDePago formaDePago,
        Dinero total,
        LocalDate actualizadoA,
        EstadoDeRecibo estado,
        long duplicados) {

    public ReciboEnConsulta {
        Objects.requireNonNull(numero, "Un recibo sin numero no es un recibo");
        Objects.requireNonNull(emitidoEn, "La fila dice cuando se emitio");
        Objects.requireNonNull(cajero, "La fila dice quien cobro");
        Objects.requireNonNull(formaDePago, "La fila dice con que se pago");
        Objects.requireNonNull(total, "La fila lleva lo cobrado");
        Objects.requireNonNull(
                actualizadoA, "Toda cifra indica a que fecha esta actualizada (RNF-075, regla 9)");
        Objects.requireNonNull(estado, "La fila dice si el recibo sigue en pie");
        if (duplicados < 0) {
            throw new IllegalArgumentException(
                    "Un recibo no se puede haber reimpreso menos de cero veces: " + duplicados);
        }
    }
}
