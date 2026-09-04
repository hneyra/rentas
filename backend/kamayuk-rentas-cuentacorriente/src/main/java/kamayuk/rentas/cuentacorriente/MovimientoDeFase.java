package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Mueve una obligacion de una fase de cobranza a otra, sin alterar cuanto se debe (V2; ARQ-06 de
 * {@code ../srtm}).
 *
 * <p>Es la API publica que {@link GeneradorDeCargos} anuncia y no cubre: "reversar, abonar o mover
 * de fase son actos posteriores de otros contextos... que ya tienen su propio caso de uso". {@code
 * valores} es el primero de esos actos posteriores (#37): un valor no crea deuda, formaliza una que
 * ya esta asentada en fase ordinaria, y a partir de ahi el libro tiene que dejar de contarla ahi.
 *
 * <p>Vive en el paquete raiz, no en {@code .aplicacion} ni en {@code .dominio}, mismo patron que
 * {@link GeneradorDeCargos} y {@link ConsultaDeDeudaPublica}.
 */
public interface MovimientoDeFase {

    /**
     * Mueve exactamente {@code monto} de la fase ordinaria a la fase {@link
     * kamayuk.rentas.cuentacorriente.dominio.Fase#VALOR} de una obligacion.
     *
     * <p>Asienta un abono en fase ordinaria y un cargo por el mismo importe en fase valor,
     * atomicamente: el total que debe el contribuyente no cambia, solo la fase en la que el libro
     * lo cuenta. El monto es el que quien llama ya congelo —no se relee la deuda aqui—, porque este
     * contexto no sabe congelar nada, solo asentar lo que le piden (regla 2).
     *
     * @param ejercicio el ejercicio de la obligacion que se mueve
     * @param contribuyenteId a quien se le cobra
     * @param tributo el tributo de la obligacion, tal como lo nombra quien pide el movimiento
     * @param periodo la cuota o el mes, si el tributo se divide; {@code null} si no aplica
     * @param predioId la unidad, si la obligacion es predial o de arbitrios
     * @param vehiculoId la unidad, si la obligacion es vehicular
     * @param referenciaExterna como entra el valor que origina el movimiento, sin clave foranea
     *     (ARQ-01 §4 regla 2)
     * @param monto siempre positivo; el mismo en el abono y en el cargo
     * @param fechaValor fecha a la que se imputan los dos asientos
     * @param documentoOrigen el numero del valor que origina el movimiento
     * @param observacion por que se mueve (regla 10)
     */
    void moverAValor(
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion);
}
