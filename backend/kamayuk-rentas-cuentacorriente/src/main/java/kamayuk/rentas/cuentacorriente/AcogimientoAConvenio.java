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
     * Que deuda tienen esas obligaciones a la fecha, cuota por cuota y con su fase. <b>No escribe
     * nada.</b>
     *
     * <p>Es lo que el preconvenio congela para simular el cronograma: la fila que acaba en {@code
     * convenio_deuda}. Que la lectura y el movimiento salgan del mismo sitio es lo que impide que
     * el convenio se firme sobre una composicion de deuda y se acoja otra.
     *
     * @param contribuyenteId el titular; lo resolvio quien llama
     * @param obligaciones las marcadas en la pantalla; sin repetidas
     * @param fechaDeCorte la fecha a la que se lee la deuda (regla 9)
     * @return una fila por cuota con deuda, en orden estable; vacia si ninguna la tiene
     */
    List<DeudaAcogida> deudaAcogible(
            long contribuyenteId, List<SeleccionDeObligacion> obligaciones, LocalDate fechaDeCorte);

    /**
     * Mueve a fase de convenio lo que esas cuotas deban a la fecha, y devuelve lo que movio.
     *
     * <p>Lo que entra es la lista congelada por el preconvenio —de ahi salen las cuotas y sus fases
     * de origen— y lo que se mueve es <b>lo pendiente a {@code fecha}</b>, releido del libro: entre
     * la simulacion y la firma pudo pagarse una cuota, y acoger la cifra vieja dejaria al libro
     * contando una deuda que ya no existe.
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
}
