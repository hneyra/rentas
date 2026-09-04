package kamayuk.rentas.tesoreria.pagos;

import java.util.Objects;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * El segundo {@code COMMIT} del camino del dinero (ADR-0026 §3), visto desde fuera.
 *
 * <h2>ESTE OBJETO NO ABRE TRANSACCION, y ahi esta todo</h2>
 *
 * <p>La abre {@link ImputacionDelPago#recibirEImputar}, y lo que este hace es <b>atrapar fuera de
 * ella</b> lo que el libro rechazo. Envolver las dos cosas en una sola transaccion es el defecto
 * que #328, #54, #72 y #430 midieron cuatro veces: la excepcion sale de un {@code @Transactional}
 * anidado y deja la de fuera marcada como <i>rollback-only</i>, asi que atraparla no sirve de nada
 * — el {@code commit} muere igual y se lleva por delante la marca del rechazo <b>y la fila del
 * buzon</b>.
 *
 * <p>Lo encontro la prueba de este buzon, no una revision, y en <b>tres</b> vueltas:
 *
 * <ol>
 *   <li>la primera version atrapaba dentro de la transaccion: {@code UnexpectedRollbackException};
 *   <li>la segunda marcaba el rechazo en una transaccion nueva y <b>seguia fallando</b>, porque la
 *       fila que iba a marcar <b>tampoco estaba</b> —se habia ido con la transaccion deshecha—. Por
 *       eso el rechazo se INSERTA y no se marca;
 *   <li>y la tercera tenia los dos metodos en la misma clase, asi que {@code @Transactional} no se
 *       aplicaba por auto-invocacion y salia {@code unrecognized configuration parameter
 *       "app.municipalidad_id"} — sin transaccion no hay {@code SET LOCAL} y RLS <b>revienta</b>
 *       (#486). De ahi que {@link ImputacionDelPago} sea otra clase.
 * </ol>
 *
 * <h2>La imputacion es de este sistema</h2>
 *
 * <p>La caja cobra un importe contra una orden y publica el pago. <b>Que parte de la deuda extingue
 * ese importe lo decide aqui</b> —interes antes que insoluto, deuda mas antigua primero—, porque
 * esa regla es el art. 31 del Codigo Tributario y escrita dos veces la que decide de verdad acaba
 * siendo la que nadie recuerda que existe (ADR-0026 §2).
 */
@Service
public class RecibirPago {

    private final ImputacionDelPago imputacion;
    private final RechazoDelPago rechazo;

    public RecibirPago(ImputacionDelPago imputacion, RechazoDelPago rechazo) {
        this.imputacion = imputacion;
        this.rechazo = rechazo;
    }

    /**
     * Recibe un pago y lo imputa.
     *
     * <p><b>Un pago inyectado dos veces con el mismo {@code pagoId} produce UN solo asiento</b> —el
     * criterio 3 de P5D—, y lo sostiene {@code pago_recibido_uq}, no un {@code if}.
     *
     * <p>Y un pago que no se puede imputar <b>no se pierde y no se reintenta para siempre</b>:
     * queda {@code RECHAZADO} con su motivo, y la conciliacion del dia lo cuenta aparte. Es dinero
     * cobrado que alguien tiene que mirar. Reintentarlo indefinidamente lo escondería: si la
     * obligacion ya se extinguio o la referencia no se puede leer, ningun reintento lo va a
     * arreglar.
     *
     * @return el pago tal como quedo. Si ya estaba, el que estaba: <b>sin volver a imputar</b>
     */
    public Recibido recibir(PagoRecibido pago) {
        Objects.requireNonNull(pago, "No se recibe un pago nulo");
        exigirQueNoHayaTransaccionAbierta();
        try {
            return imputacion.recibirEImputar(pago);
        } catch (RegistroDeAbonos.SinDeudaQueAbonar
                | RegistroDeAbonos.SinAbonosQueReversar
                | ReferenciaDeObligacion.ReferenciaIlegible noSePudo) {
            return new Recibido(rechazo.rechazar(pago, motivoDe(noSePudo)), true);
        }
    }

    /**
     * Se niega a correr dentro de una transaccion ajena, y esto lo enseño un INTERBLOQUEO.
     *
     * <p>Si quien llama ya tiene una transaccion abierta, {@link ImputacionDelPago} se une a ella
     * en vez de abrir la suya. Cuando el libro rechaza, esa transaccion —la de fuera— <b>sigue
     * abierta</b> con la fila del buzon ya insertada y marcada <i>rollback-only</i>; y entonces
     * {@link RechazoDelPago} abre una transaccion NUEVA que intenta insertar <b>el mismo {@code
     * pago_id}</b>. El indice unico la hace esperar a que la primera termine, y la primera espera a
     * que la segunda vuelva: <b>las dos se quedan bloqueadas para siempre</b>.
     *
     * <p>Medido: la corrida se colgo sin un solo mensaje, y lo que lo dijo fue el catalogo del
     * motor —{@code pg_stat_activity} con la primera {@code idle in transaction} y la segunda en
     * {@code Lock / transactionid} sobre el {@code INSERT INTO pago_recibido}—. Un cuelgue sin
     * mensaje en el camino del dinero es lo peor que puede pasar, y por eso la guarda esta aqui y
     * no en un comentario.
     *
     * <p>El borde HTTP lo cumple —{@code PagoController.recibir} no lleva {@code @Transactional}—,
     * que es lo que hace que en produccion esto no pueda pasar hoy.
     */
    /**
     * Lo que sale de recibir un pago.
     *
     * <p>{@code nuevo} va aparte del pago y no se deriva de su estado, y hay un motivo: un pago que
     * ya estaba puede estar APLICADO o RECHAZADO, y las dos cosas se distinguen de «acaba de
     * llegar» de la misma manera. Es lo que el borde traduce a 201 o 409 — y ese 409 no es un
     * error, es «ya lo tengo», que es lo que permite al publicador de la caja reintentar sin miedo.
     */
    public record Recibido(PagoRecibido pago, boolean nuevo) {}

    private static void exigirQueNoHayaTransaccionAbierta() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "RecibirPago.recibir NO se puede llamar dentro de una transaccion: cuando el"
                            + " libro rechaza, el rechazo se escribe en una transaccion NUEVA que"
                            + " insertaria el mismo pago_id, y esa espera a que la de fuera termine"
                            + " mientras la de fuera espera a que esta vuelva. Es un interbloqueo, y se"
                            + " cuelga sin decir nada");
        }
    }

    private static String motivoDe(RuntimeException noSePudo) {
        String mensaje = noSePudo.getMessage();
        String texto = mensaje == null ? noSePudo.getClass().getSimpleName() : mensaje;
        return texto.length() <= 400 ? texto : texto.substring(0, 400);
    }
}
