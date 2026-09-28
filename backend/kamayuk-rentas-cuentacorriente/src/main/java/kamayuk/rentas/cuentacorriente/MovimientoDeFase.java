package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;

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
     * Pasa a la fase {@link kamayuk.rentas.cuentacorriente.dominio.Fase#VALOR} lo que una
     * obligacion debe en fase ordinaria a {@code fechaValor}, <b>cuota por cuota</b> (#448).
     *
     * <p>Por cada cuota que a esa fecha esta en ORDINARIA y debe algo, un abono en ordinaria y un
     * cargo en valor por <b>lo que esa cuota tiene en ORDINARIA</b> y con <b>su</b> periodo,
     * atomicamente: el total que debe el contribuyente no cambia, solo la fase en la que el libro
     * lo cuenta. Una cuota que a esa fecha no vencio, que ya se pago o que esta en otra fase no se
     * toca.
     *
     * <p><b>Lo que tiene en ORDINARIA, y no lo que debe (#510).</b> Es el espejo de {@link
     * #moverACoactiva}, y por lo mismo: hasta #510 cada cuota pasaba lo que debia en todas sus
     * fases, y con una parte ya formalizada eso sacaba de ORDINARIA deuda que no estaba ahi. Una
     * rectificacion asentada despues de la OP deja el ultimo asiento de la cuota en ORDINARIA, y la
     * RD que la formaliza movia los 600 —los 500 de la OP y los 100 de la rectificacion— y dejaba
     * ORDINARIA en -500 y VALOR en 1 100; con la deuda ya importada, lo que estaba en COACTIVA
     * volvia a contarse. Leido aqui, lo que se mueve es:
     *
     * <ul>
     *   <li>el neto de la cuota en ORDINARIA a {@code fechaValor} —cargos menos abonos, todos los
     *       conceptos—, porque eso es lo que la fase tiene; un segundo valor sobre la misma deuda
     *       ya no encuentra nada, salvo lo que un cargo posterior al primero dejo alli;
     *   <li>y nunca mas de lo que la cuota debe a esa fecha, porque un abono que el libro asento en
     *       otra fase deja en ORDINARIA mas de lo que se debe, y el pase no formaliza deuda que no
     *       existe.
     * </ul>
     *
     * <p>Hasta #448 quien llamaba pasaba el periodo y el monto, y no los podia saber: {@code
     * valores} tiene la obligacion agregada, asi que pasaba periodo nulo y el total, y el par caia
     * en una fila anual nueva —VALOR por 0,00— mientras las cuotas, que son las que la cobranza, la
     * extincion y el acogimiento leen, seguian en ORDINARIA. Cuales son las cuotas lo sabe este
     * contexto, y por eso el puerto ya no las pregunta.
     *
     * <p>Antes de los pares carga, en cada cuota de la obligacion, el reajuste y el interes
     * devengados que el libro todavia no tenia (#365): el par adelanta el ultimo movimiento, y sin
     * ese cargo el libro perderia al dia siguiente el interes que la OP acaba de congelar.
     *
     * @param contribuyenteId a quien se le cobra
     * @param obligacion que obligacion pasa a valor
     * @param referenciaExterna como entra el valor que origina el movimiento, sin clave foranea
     *     (ARQ-01 §4 regla 2)
     * @param fechaValor fecha a la que se imputan los asientos, y a la que se mide lo que se debe
     * @param documentoOrigen el numero del valor que origina el movimiento
     * @param observacion por que se mueve (regla 10)
     * @return lo que se paso a VALOR, la suma de las cuotas; cero si ninguna tenia nada en
     *     ORDINARIA, y entonces no se asienta ningun par. Quien formaliza la obligacion por primera
     *     vez lo compara con lo que congelo, y si no coincide el libro no es el que leyo
     */
    Dinero moverAValor(
            long contribuyenteId,
            ClaveDeObligacionPublica obligacion,
            String referenciaExterna,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion);

    /**
     * Pasa a la fase {@link kamayuk.rentas.cuentacorriente.dominio.Fase#COACTIVA} lo que una
     * obligacion tiene en la fase {@link kamayuk.rentas.cuentacorriente.dominio.Fase#VALOR} (#407).
     *
     * <p>Es el espejo de {@link #moverAValor} en el par —un abono en fase valor y un cargo por el
     * mismo importe en fase coactiva, atomicamente— y en el monto —lo que la fase de salida tiene,
     * acotado por lo que se debe (#510)—, y el total que debe el contribuyente no cambia. Un
     * convenio coactivo que se quiebre la devuelve a COACTIVA y no a VALOR.
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
     * <p>El par va en la fila anual (periodo nulo). El de {@link #moverAValor} ya no, desde #448;
     * este sigue asi hasta que #308 decida que pasa con la fase COACTIVA.
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
