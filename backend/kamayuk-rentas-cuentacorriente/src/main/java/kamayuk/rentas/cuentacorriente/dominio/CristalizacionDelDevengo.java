package kamayuk.rentas.cuentacorriente.dominio;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;

/**
 * Lo que hay que <b>cargar</b> en una cuota antes de escribir cualquier otra cosa en ella: el
 * reajuste y el interes devengados que todavia no estan en el libro (#365).
 *
 * <h2>Por que es una invariante del libro, y no de cada llamador</h2>
 *
 * <p>{@link CalculoDeDeuda#deudaActualizadaA} acumula la mora desde el <b>ultimo movimiento</b> de
 * la cuota, sea del concepto que sea —un abono, un par {@code AJUSTE} de pase a valor, un par
 * {@code FRACCIONAMIENTO} de convenio, una reversion—. Cualquier asiento nuevo adelanta ese ancla.
 * Si antes no se asento el cargo de lo devengado, lo devengado hasta ese dia <b>deja de existir</b>
 * —una condonacion que ningun acto dice—, y si el asiento nuevo abona interes que el libro no cargo
 * nunca, {@code netear(INTERES)} queda en negativo para siempre.
 *
 * <p>Hasta #365 la regla estaba escrita dos veces —la cobranza (#33) y el convenio (#35)— y
 * olvidada en la extincion, la baja manual, el pase a valor y la reversion. Hoy no se nota porque
 * la unica {@link PoliticaDeMora} de {@code src/main} no devenga nada; el dia que D-02 fije la TIM,
 * esos cuatro caminos habrian dado saldos negativos, condonaciones sin acto y una OP que no
 * coincide con el libro, sin ningun error de por medio. Por eso vive aqui, una vez, y todos la
 * llaman.
 *
 * <h2>La cuenta</h2>
 *
 * <p>Parte por parte, lo que se puede extinguir desde {@code fecha} —{@link
 * CalculoDeDeuda#extinguibleDesde}, que sin nada posterior en el libro es exactamente {@code
 * deudaActualizadaA(fecha)}— menos lo que el libro ya tiene asentado a esa fecha ({@link
 * CalculoDeDeuda#asentadoA}). Donde la diferencia es positiva hay un cargo. Es el bucle que la
 * cobranza y el convenio tenian cada uno por su lado, sin cambiar una cifra: con {@code
 * extinguibleDesde} y no con {@code deudaActualizadaA}, porque es lo que los dos median desde #471
 * y porque garantiza que un abono que no pase de lo extinguible nunca deja una parte en negativo.
 *
 * <p>En la practica solo el reajuste y el interes pueden salir: el insoluto y el gasto no se
 * devengan, se asientan. Se recorren las cuatro partes igual, para que esa afirmacion la sostenga
 * {@link CalculoDeDeuda} y no una suposicion de esta clase.
 *
 * <p><b>Funcion pura</b> (reglas 6 y 7): recibe los asientos, la fecha y el redondeo, y devuelve lo
 * que habria que cargar. No escribe nada: el cargo lo asienta quien llama, con <b>su</b> fase y
 * <b>su</b> documento de origen, porque el devengo se cristaliza como parte de su acto y es su
 * papel el que explica la fila. Y quien planifica antes de escribir (#39) puede preguntar sin
 * escribir.
 */
public final class CristalizacionDelDevengo {

    /** Las cuatro partes del desglose, en el orden en que se cristalizan. */
    private static final List<Concepto> PARTES =
            List.of(Concepto.INSOLUTO, Concepto.REAJUSTE, Concepto.INTERES, Concepto.GASTO);

    private final CalculoDeDeuda calculo;

    public CristalizacionDelDevengo(CalculoDeDeuda calculo) {
        this.calculo = Objects.requireNonNull(calculo, "El devengo lo mide el calculo de la deuda");
    }

    /**
     * Los cargos que faltan en <b>una</b> cuota para que el libro tenga, a {@code fecha}, lo que se
     * debe a esa fecha.
     *
     * @param asientos los de <b>una</b> cuota —un asiento de otra mezclaria su devengo con el de
     *     esta—, con los posteriores a {@code fecha} dentro
     * @param fecha la fecha valor del acto que va a escribir
     * @param redondeo la politica con la que {@link PoliticaDeMora} redondea lo que acumula (D-03)
     * @return un cargo por parte con devengo sin asentar, en el orden del desglose; vacia si no
     *     falta ninguno
     */
    public List<Devengo> sinAsentar(
            List<Asiento> asientos, LocalDate fecha, PoliticaDeRedondeo redondeo) {
        Objects.requireNonNull(asientos, "La lista de asientos es vacia, no nula");
        Objects.requireNonNull(fecha, "La fecha del acto entra como argumento (regla 6, RNF-075)");

        DeudaActualizada pendiente = calculo.extinguibleDesde(asientos, fecha, redondeo);
        DeudaActualizada asentado = calculo.asentadoA(asientos, fecha);

        List<Devengo> cargos = new ArrayList<>();
        for (Concepto parte : PARTES) {
            Dinero falta = parteDe(pendiente, parte).menos(parteDe(asentado, parte));
            if (falta.esPositivo()) {
                cargos.add(new Devengo(parte, falta));
            }
        }
        return List.copyOf(cargos);
    }

    private static Dinero parteDe(DeudaActualizada deuda, Concepto concepto) {
        return switch (concepto) {
            case INSOLUTO -> deuda.insoluto();
            case REAJUSTE -> deuda.reajuste();
            case INTERES -> deuda.interes();
            case GASTO -> deuda.gasto();
            default ->
                    throw new IllegalArgumentException(
                            "El desglose de la deuda tiene cuatro partes, y "
                                    + concepto
                                    + " no es una de ellas");
        };
    }

    /**
     * Un cargo que falta: de que parte y cuanto. Siempre positivo —un devengo de cero no es un
     * cargo, y uno negativo no existe—.
     *
     * @param parte una de las cuatro partes del desglose
     * @param monto lo devengado y no asentado
     */
    public record Devengo(Concepto parte, Dinero monto) {

        public Devengo {
            Objects.requireNonNull(parte, "Un devengo es de una parte del desglose");
            Objects.requireNonNull(monto, "Un devengo tiene importe");
            if (!monto.esPositivo()) {
                throw new IllegalArgumentException(
                        "Un devengo que cristalizar es positivo, y este es " + monto);
            }
        }
    }
}
