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
 * <p>{@code coactiva} es el segundo (#407): importar un valor a un expediente es el punto en que su
 * deuda entra en cobranza coactiva, y el libro tiene que dejar de contarla en VALOR. Hasta #407
 * nadie la movia —la unica escritura de la fase COACTIVA eran las costas—, y el fraccionamiento
 * coactivo, que solo acoge deuda de esa fase, rechazaba justo la deuda que el expediente cobra.
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

    /**
     * Mueve exactamente {@code monto} de la fase {@link
     * kamayuk.rentas.cuentacorriente.dominio.Fase#VALOR} a la fase {@link
     * kamayuk.rentas.cuentacorriente.dominio.Fase#COACTIVA} de una obligacion (#407).
     *
     * <p>Es el espejo de {@link #moverAValor}: un abono en fase valor y un cargo por el mismo
     * importe en fase coactiva, atomicamente. El total que debe el contribuyente no cambia, solo la
     * fase en la que el libro lo cuenta, y por eso un convenio coactivo que se quiebre la devuelve
     * a COACTIVA y no a VALOR.
     *
     * <p>El monto lo decide quien llama, igual que en {@link #moverAValor}, y no es el congelado en
     * el valor sino <b>lo pendiente a la fecha del movimiento</b>: si el obligado pago una parte
     * entre la emision y la importacion, mover lo congelado dejaria la fase VALOR en negativo.
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
     * @param documentoOrigen el expediente en el que la deuda entra a coactiva
     * @param observacion por que se mueve (regla 10)
     */
    void moverACoactiva(
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
