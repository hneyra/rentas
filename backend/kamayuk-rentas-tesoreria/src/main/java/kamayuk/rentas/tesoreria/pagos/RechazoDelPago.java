package kamayuk.rentas.tesoreria.pagos;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marca un pago como rechazado, <b>en su propia transaccion</b> (P5D).
 *
 * <h2>Por que esto es una clase aparte y no dos lineas dentro de {@code RecibirPago}</h2>
 *
 * <p>Porque {@link Propagation#REQUIRES_NEW} sobre un metodo de la misma clase <b>no se aplica</b>:
 * la auto-invocacion no pasa por el proxy de Spring, y la anotacion seria una promesa del javadoc.
 * Es la leccion que #536 midio con el bucle de la carga cartografica y #430 con {@code
 * ImportarCajas}.
 *
 * <h2>Y por que hace falta una transaccion nueva</h2>
 *
 * <p>Cuando {@code RegistroDeAbonos} rechaza un abono, la excepcion sale de un metodo
 * {@code @Transactional} anidado y <b>deja la transaccion de fuera marcada como rollback-only</b>.
 * Atraparla no la desmarca: el {@code commit} muere con {@code UnexpectedRollbackException} y se
 * lleva por delante todo lo escrito, <b>incluida la fila del buzon</b>. El pago volveria a entrar
 * en la siguiente entrega, se rechazaria otra vez, y la caja lo daria por MUERTO tras agotar sus
 * intentos por un motivo que ya se conocia desde el primero.
 *
 * <p>Lo encontro la prueba del buzon, no una revision: la corrida entera reventaba con «Transaction
 * rolled back because it has been marked as rollback-only».
 *
 * <p><b>Lo que cuesta, dicho aqui:</b> con la transaccion nueva, la fila del buzon queda escrita
 * aunque la de fuera se deshaga. Es lo que se quiere —un pago cobrado que no se pudo imputar tiene
 * que quedar registrado con su motivo, no desaparecer— y es exactamente lo contrario de lo que
 * conviene en cualquier otro sitio de este sistema.
 */
@Service
public class RechazoDelPago {

    private final PagoRecibidoRepository buzon;

    public RechazoDelPago(PagoRecibidoRepository buzon) {
        this.buzon = buzon;
    }

    /**
     * Escribe el pago ya rechazado, en una transaccion nueva.
     *
     * <p>Se INSERTA, no se marca: cuando esto se llama, la transaccion que lo habia insertado ya se
     * deshizo y la fila no esta. Y se hace idempotente igual que el alta normal, porque el
     * publicador de la caja va a reintentar este mismo pago.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PagoRecibido rechazar(PagoRecibido pago, String motivo) {
        return buzon.recibirRechazado(pago, motivo).pago();
    }
}
