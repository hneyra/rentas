package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Observacion;

/**
 * Acoge deuda a un convenio de fraccionamiento y la devuelve cuando el convenio se cierra (#35,
 * RF-084, RF-086).
 *
 * <p>Es la quinta API publica de este modulo —tras {@link ConsultaDeDeudaPublica}, {@link
 * GeneradorDeCargos}, {@link MovimientoDeFase} y {@link RegistroDeAbonos}—, y vive en el paquete
 * raiz por el mismo motivo que las otras cuatro: Spring Modulith trata como interno todo lo que
 * esta en un subpaquete, asi que esto es exactamente lo que {@code tesoreria} puede ver de {@code
 * cuentacorriente}. Sus tablas, no.
 *
 * <p>Podria haber sido un metodo mas de {@link MovimientoDeFase} —mover a fase de convenio es mover
 * de fase—, y no lo es por una diferencia que importa: {@code moverAValor} recibe el monto ya
 * congelado por quien llama, y aqui <b>no puede haber ningun monto en la firma</b>. Un convenio
 * acoge «lo que se debe», y si la cifra viajara desde tesoreria la caja podria acoger la que leyo
 * hace cinco minutos —o la que le diera la gana— y el libro la asentaria sin discutir (ARQ-01 §3.8:
 * «tesoreria asienta abonos; nunca determina»).
 *
 * <h2>Que es acoger, en el libro</h2>
 *
 * <p>Un par de asientos por cuota, con {@link kamayuk.rentas.cuentacorriente.dominio.Concepto
 * #FRACCIONAMIENTO}: un abono en la fase en que la cuota estaba y un cargo por el mismo importe en
 * fase {@code CONVENIO}. Es exactamente la forma que {@code moverAValor} ya usa para el pase a
 * valor, y su propiedad es la que hace falta: <b>el total que el contribuyente debe no cambia</b>
 * —el concepto del par no es ninguna de las cuatro partes del desglose, asi que {@code
 * deudaActualizadaA} lo ignora—, solo cambia la fase en la que el libro lo cuenta.
 *
 * <p>Y cambia porque la fase de una obligacion es la de su <b>ultimo</b> asiento ({@code
 * ProyeccionDelSaldo}). Nunca un {@code UPDATE} de una columna de fase: el libro no se edita
 * (ADR-0006).
 *
 * <h2>Devolver no es reversar</h2>
 *
 * <p>Cerrar un convenio <b>no</b> deshace el acogimiento: lo mueve al reves, y por lo que se
 * devuelve es <b>lo que queda pendiente ahora</b>, releido del libro, no lo que se acogio entonces.
 * Reversar los asientos del acogimiento devolveria a la fase de origen tambien lo que entretanto se
 * hubiera pagado, y el contribuyente acabaria debiendo otra vez lo que ya pago.
 */
public interface AcogimientoAConvenio {

    /**
     * Que deuda tienen esas obligaciones por acoger desde la fecha, cuota por cuota y con su fase.
     * <b>No escribe nada.</b>
     *
     * <p>Lo que queda por extinguir <b>desde</b> {@code fechaDeCorte}, no lo que se debia a ella
     * (#471): un cobro con fecha valor posterior que ya esta en el libro ya extinguio su parte, y
     * congelarla en el cronograma seria fraccionar una deuda que no existe.
     *
     * <p>Es lo que el preconvenio congela para simular el cronograma: la fila que acaba en {@code
     * convenio_deuda}. Que la lectura y el movimiento salgan del mismo sitio es lo que impide que
     * el convenio se firme sobre una composicion de deuda y se acoja otra.
     *
     * @param contribuyenteId el titular; lo resolvio quien llama
     * @param obligaciones las marcadas en la pantalla; sin repetidas
     * @param fechaDeCorte la fecha a la que se lee la deuda (regla 9)
     * @return una fila por cuota con deuda, en orden estable; vacia si ninguna la tiene
     * @throws CuotaYaAcogida si alguna cuota con deuda ya esta en fase de convenio (#442)
     */
    List<DeudaAcogida> deudaAcogible(
            long contribuyenteId, List<SeleccionDeObligacion> obligaciones, LocalDate fechaDeCorte);

    /**
     * Mueve a fase de convenio lo que esas cuotas deban a la fecha, y devuelve lo que movio.
     *
     * <p>Lo que entra es la lista congelada por el preconvenio —de ahi salen las cuotas y sus fases
     * de origen— y lo que se mueve es <b>lo pendiente desde {@code fecha}</b>, releido del libro:
     * entre la simulacion y la firma pudo pagarse una cuota, y acoger la cifra vieja dejaria al
     * libro contando una deuda que ya no existe. Y ese pago puede tener fecha valor
     * <b>posterior</b> a la de la formalizacion; lo que ya extinguio tampoco se acoge (#471).
     *
     * @param contribuyenteId el titular; un convenio es de uno solo
     * @param acogidas las cuotas del preconvenio, con su fase de origen
     * @param fecha la fecha valor de los asientos y la de relectura de la deuda
     * @param documentoOrigen el numero del convenio que origina el movimiento
     * @param observacion por que se acoge (regla 10); queda como {@code motivo} de cada asiento
     * @return lo que de verdad se movio, con su fecha; vacio si ninguna cuota tenia ya deuda
     */
    MovimientoAsentado acoger(
            long contribuyenteId,
            List<DeudaAcogida> acogidas,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion);

    /**
     * Devuelve a su fase de origen lo que quede pendiente en fase de convenio (RF-086).
     *
     * <p>El movimiento contrario al de {@link #acoger}, con el mismo mecanismo y por lo pendiente
     * <b>ahora</b>. Una cuota que ya no debe nada no produce ningun asiento: no hay nada que
     * devolver.
     *
     * @param contribuyenteId el titular; el mismo del acogimiento
     * @param acogidas las cuotas del convenio que se cierra, con la fase a la que vuelven
     * @param fecha la fecha valor de los asientos nuevos
     * @param documentoOrigen el documento que sustenta la devolucion; <b>distinto</b> del que uso
     *     el acogimiento, para que los dos movimientos se puedan distinguir en el libro
     * @param observacion por que se devuelve (regla 10)
     * @return lo que de verdad se devolvio, con su fecha
     */
    MovimientoAsentado devolver(
            long contribuyenteId,
            List<DeudaAcogida> acogidas,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion);

    /**
     * Esa cuota ya esta en fase de convenio: acogerla otra vez la dejaria en dos cronogramas
     * (#442).
     *
     * <h2>Por que la regla vive aqui y no en tesoreria</h2>
     *
     * <p>{@link DeudaAcogida#faseOrigen} es opaca a proposito: tesoreria la guarda y la devuelve
     * sin interpretarla. «Esta cuota ya esta en un convenio» es interpretar una fase, y las fases
     * son de este contexto. Por eso lo dice {@link #deudaAcogible}, que es quien la lee.
     *
     * <h2>Por que se lanza en vez de saltar la cuota</h2>
     *
     * <p>Saltarla en silencio daria una de dos respuestas falsas: «no tiene deuda que fraccionar»,
     * si era la unica marcada —y la deuda existe—, o un cronograma sobre <b>una parte</b> de lo
     * marcado, que quien atiende firmaria creyendo que es el todo.
     *
     * <h2>Lo que habia antes</h2>
     *
     * <p>Hasta #442 la fila salia con {@code faseOrigen = "CONVENIO"}: la simulacion imprimia un
     * plan que no se podia firmar y el registro reventaba contra {@code
     * convenio_deuda_fase_origen_check} con un 500. Ese {@code CHECK} impedia el doble acogimiento
     * <b>por casualidad</b> —se escribio para decir a que fase se devuelve la deuda—, y el dia que
     * alguien le anadiera CONVENIO el doble acogimiento pasaria en silencio.
     *
     * <p>Quien la recibe sabe lo que este contexto no: en que convenio esta la cuota. Con eso
     * contesta 409 y remite a reformular ese convenio.
     */
    final class CuotaYaAcogida extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final ClaveDeObligacionPublica obligacion;
        private final int periodo;

        /**
         * @param obligacion el tributo, el ejercicio y la unidad de la cuota
         * @param periodo la cuota o el mes; 0 es «anual», igual que en {@link DeudaAcogida}
         */
        public CuotaYaAcogida(ClaveDeObligacionPublica obligacion, int periodo) {
            super(
                    "La cuota "
                            + descripcion(obligacion, periodo)
                            + " ya esta acogida a un convenio: fraccionarla otra vez la dejaria en"
                            + " dos cronogramas");
            this.obligacion = obligacion;
            this.periodo = periodo;
        }

        /** El tributo, el ejercicio y la unidad de la cuota ya acogida. */
        public ClaveDeObligacionPublica obligacion() {
            return obligacion;
        }

        /** La cuota o el mes; 0 es «anual». */
        public int periodo() {
            return periodo;
        }

        private static String descripcion(ClaveDeObligacionPublica obligacion, int periodo) {
            return obligacion.tributo()
                    + " "
                    + obligacion.ejercicio().valor()
                    + (periodo == 0 ? " (anual)" : " periodo " + periodo)
                    + (obligacion.predioId() == null ? "" : " del predio " + obligacion.predioId())
                    + (obligacion.vehiculoId() == null
                            ? ""
                            : " del vehiculo " + obligacion.vehiculoId());
        }
    }
}
