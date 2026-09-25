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
     * <p>Antes del par carga, en cada cuota de la obligacion, el reajuste y el interes devengados
     * que el libro todavia no tenia (#365): el par adelanta el ultimo movimiento, y sin ese cargo
     * el libro perderia al dia siguiente el interes que la OP acaba de congelar.
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
     * Pasa a la fase {@link kamayuk.rentas.cuentacorriente.dominio.Fase#COACTIVA} lo que una
     * obligacion tiene en la fase {@link kamayuk.rentas.cuentacorriente.dominio.Fase#VALOR} (#407).
     *
     * <p>Es el espejo de {@link #moverAValor} en el par —un abono en fase valor y un cargo por el
     * mismo importe en fase coactiva, atomicamente—, y el total que debe el contribuyente no
     * cambia. Un convenio coactivo que se quiebre la devuelve a COACTIVA y no a VALOR.
     *
     * <p><b>Pero el monto no lo decide quien llama, y es a proposito.</b> Lo que entra en coactiva
     * es lo que la OP o la RD formalizaron y sigue en VALOR, y eso solo lo sabe el libro. La
     * primera version de #407 lo decidia coactiva con lo pendiente de la obligacion <b>en todas sus
     * fases</b>, y eso fallaba de dos maneras: un cargo ordinario asentado despues de la OP —que
     * ningun valor formaliza— se sacaba de VALOR y dejaba esa fase en negativo; y una obligacion
     * que dos valores traen en dos importaciones distintas se movia dos veces. Leido aqui, lo que
     * se mueve es:
     *
     * <ul>
     *   <li>el neto de la obligacion en VALOR —cargos menos abonos, todas sus cuotas—, porque eso
     *       es lo que la fase tiene; una segunda importacion ya no encuentra nada;
     *   <li>y nunca mas de lo que se debe desde {@code fechaValor}, porque un abono que el libro
     *       asento en otra fase deja en VALOR mas de lo que se debe, y COACTIVA no puede contar
     *       deuda que no existe.
     * </ul>
     *
     * <p>El par va, como el de {@link #moverAValor}, en la fila anual (periodo nulo).
     *
     * <p><b>A diferencia de {@link #moverAValor}, no cristaliza el devengo antes del par</b>
     * (#365): cuanto entra en coactiva lo decide el libro, y cristalizar antes lo cambiaria, que es
     * una decision de cobranza todavia sin tomar. Con una mora que devengue, el par adelanta el
     * ultimo movimiento y el interes devengado desde la OP se pierde en cada paso a coactiva; hoy
     * no se ve porque la unica politica de mora no devenga, y el encendido de la mora (D-02) exige
     * resolverlo antes.
     *
     * @param contribuyenteId a quien se le cobra
     * @param obligacion que obligacion entra en coactiva
     * @param referenciaExterna como entra el valor que origina el movimiento, sin clave foranea
     *     (ARQ-01 §4 regla 2)
     * @param fechaValor fecha a la que se imputan los dos asientos
     * @param documentoOrigen el expediente en el que la deuda entra a coactiva
     * @param observacion por que se mueve (regla 10)
     * @return lo que se paso a COACTIVA; cero si la obligacion no tenia nada en VALOR, y entonces
     *     no se asienta nada: un par por cero no mueve nada y deja un asiento que nadie explica
     */
    Dinero moverACoactiva(
            long contribuyenteId,
            ClaveDeObligacionPublica obligacion,
            String referenciaExterna,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion);
}
