package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Observacion;

/**
 * Asienta el abono de un pago en el libro de cuenta corriente (#33, RF-080).
 *
 * <p>Es la cuarta API publica de este modulo —despues de {@link ConsultaDeDeudaPublica}, {@link
 * GeneradorDeCargos} y {@link MovimientoDeFase}—, y la que {@code GeneradorDeCargos} anunciaba sin
 * cubrir: «reversar, abonar o mover de fase son actos posteriores de otros contextos (tesoreria,
 * coactiva) que ya tienen su propio caso de uso». Este es el de tesoreria.
 *
 * <p>Vive en el paquete raiz, no en {@code .aplicacion} ni en {@code .dominio}, mismo patron que
 * las otras tres: Spring Modulith trata como interno todo lo que esta en un subpaquete, asi que
 * esto es exactamente lo que {@code tesoreria} puede ver de {@code cuentacorriente}. Sus tablas,
 * no.
 *
 * <h2>Por que no recibe importes</h2>
 *
 * <p>ARQ-01 §3.8: «tesoreria asienta abonos; nunca determina. Si la caja calcula deuda, el sistema
 * tiene dos verdades». Por eso la firma pide obligaciones y una fecha, y <b>no</b> un importe: el
 * cuanto lo resuelve este contexto releyendo {@code deudaActualizadaA(fechaDePago)} sobre su propio
 * libro, dentro de la misma transaccion en la que asienta. Entre la lectura y la escritura no cabe
 * nada, y ni el cajero ni un cliente HTTP pueden meter una cifra por el medio.
 *
 * <p>Es tambien lo que hace imposible cobrar dos veces la misma deuda: la segunda cobranza no
 * trabaja sobre una cifra que traiga en la mano, sino sobre el libro que ya tiene dentro el abono
 * de la primera.
 */
public interface RegistroDeAbonos {

    /**
     * Cobra <b>integramente</b> las obligaciones marcadas y asienta sus abonos.
     *
     * <p>Lo que hace, en este orden y en una sola transaccion:
     *
     * <ol>
     *   <li>bloquea en la base las filas de saldo de cada obligacion marcada, en orden estable
     *       —para que dos cobranzas concurrentes con selecciones que se solapan se serialicen en
     *       vez de bloquearse mutuamente—;
     *   <li>relee la deuda de cada cuota con {@code deudaActualizadaA(fechaDePago)}, ya con el
     *       libro que la cobranza anterior dejo;
     *   <li>asienta el cargo del reajuste y del interes <b>devengados y no asentados</b> —al
     *       cobrarlos dejan de ser una proyeccion y pasan a ser un hecho— y, contra ellos, el abono
     *       de las cuatro partes.
     * </ol>
     *
     * <p><b>No aplica descuentos.</b> El efecto de una campana de beneficio sobre el importe esta
     * bloqueado por D-02b (#33): lo que se cobra es lo que se debe. Una condonacion es un asiento
     * de {@code CONDONACION} con su motivo, y la escribira quien tenga los valores de la ordenanza
     * firmados.
     *
     * @param contribuyenteId a quien se le cobra; lo resolvio quien llama
     * @param obligaciones las marcadas en ventanilla; sin repetidas
     * @param fechaDePago la fecha a la que se relee la deuda y se imputan los asientos (regla 9)
     * @param documentoOrigen el numero del recibo que origina el abono
     * @param observacion por que se abona (regla 10)
     * @return un {@link AbonoAsentado} por obligacion que tenia deuda, en el orden recibido
     * @throws SinDeudaQueAbonar si ninguna de las obligaciones marcadas tenia deuda a esa fecha
     */
    List<AbonoAsentado> abonarPagoIntegro(
            long contribuyenteId,
            List<SeleccionDeObligacion> obligaciones,
            LocalDate fechaDePago,
            String documentoOrigen,
            Observacion observacion);

    /**
     * Deshace los abonos que un documento origino, <b>asentando su reversion</b> (#34, RF-083).
     *
     * <p>No se borra ni se edita nada: «un asiento equivocado no se corrige, se reversa» (V2). Por
     * cada asiento que la cobranza escribio queda su opuesto, con {@code asiento_reversado_id}
     * apuntando al original, y el libro conserva las dos filas. Es la unica forma de que el estado
     * de cuenta siga contando lo que de verdad paso: que se cobro el 15 de marzo y que se anulo el
     * mismo dia.
     *
     * <p>Se reversan <b>todos</b> los asientos del documento, cargos incluidos. Al cobrar, {@link
     * #abonarPagoIntegro} cristaliza el reajuste y el interes devengados con un cargo antes de
     * abonarlos; deshacer solo los abonos dejaria ese cargo vivo y la obligacion debiendo un
     * interes que ya nadie va a cobrar por esa via.
     *
     * <p>Tras la reversion, {@code deudaActualizadaA(hoy)} vuelve a mostrar la deuda pendiente. No
     * porque se haya escrito esa cifra en ningun sitio, sino porque el neteo de cargos contra
     * abonos vuelve a dar lo que daba: es la consecuencia de que el libro sea la unica verdad
     * (ADR-0006).
     *
     * @param documentoOrigen el documento cuyos asientos se deshacen; en tesoreria, {@code "RECIBO
     *     001-0000123"}
     * @param documentoDeLaReversion el documento que sustenta los asientos nuevos. <b>Tiene que ser
     *     distinto del anterior</b>, y no es una formalidad: si la reversion se marcara con el
     *     mismo documento, una segunda llamada la encontraria y reversaria la reversion
     * @param fecha fecha valor de la reversion; decide ademas en que particion caen los asientos
     *     nuevos
     * @param observacion por que se reversa (regla 10); queda como {@code motivo} de cada asiento
     * @return cuantos asientos se escribieron y cuanto vuelve a deberse
     * @throws SinAbonosQueReversar si ese documento no origino ningun asiento reversable
     */
    ReversionDeAbonos reversarAbonos(
            String documentoOrigen,
            String documentoDeLaReversion,
            LocalDate fecha,
            Observacion observacion);

    /**
     * Ese documento no origino ningun asiento que se pueda reversar.
     *
     * <p>O nunca los tuvo —un recibo de caja de tasas no toca el libro: un derecho de tramite no es
     * deuda tributaria— o ya se reversaron. En los dos casos quien llama sabe algo que este
     * contexto no: si eso es un error o lo esperado.
     */
    final class SinAbonosQueReversar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public SinAbonosQueReversar(String mensaje) {
            super(mensaje);
        }
    }

    /**
     * Ninguna de las obligaciones marcadas tenia deuda a la fecha de pago.
     *
     * <p>Es el error que ve el cajero cuando alguien cobra dos veces: la primera cobranza dejo el
     * saldo en cero y la segunda no encuentra nada que abonar. No es un fallo tecnico, es el
     * sistema diciendo que esa deuda ya se pago.
     */
    final class SinDeudaQueAbonar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public SinDeudaQueAbonar(String mensaje) {
            super(mensaje);
        }
    }
}
