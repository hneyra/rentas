package kamayuk.rentas.tesoreria.pagos;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kamayuk.rentas.dominio.Dinero;

/** El buzon de entrada de pagos (ADR-0026 §3). */
public interface PagoRecibidoRepository {

    /**
     * Deja el pago en el buzon, <b>o devuelve el que ya estaba</b>.
     *
     * <p>Es UN solo {@code INSERT ... ON CONFLICT DO NOTHING RETURNING}, y no un {@code SELECT}
     * seguido de un {@code INSERT}. La diferencia es el criterio 3 del encargo de P5D: con dos
     * sentencias, dos entregas simultaneas del mismo reintento leen las dos «no esta» y las dos
     * imputan — dos asientos por el mismo pago, o sea el doble de deuda extinguida.
     *
     * @return el pago guardado y si es nuevo. Si no lo es, quien llama <b>no imputa</b>
     */
    Recepcion recibir(PagoRecibido pago);

    /** Lo que devuelve una recepcion: el pago y si llego por primera vez. */
    record Recepcion(PagoRecibido pago, boolean nuevo) {}

    /**
     * Deja el pago en el buzon <b>ya rechazado</b>, con su motivo.
     *
     * <p>Existe porque el rechazo llega DESPUES de que la transaccion que lo insertaba se haya
     * deshecho: {@code RegistroDeAbonos} es {@code @Transactional} y al lanzar deja la de fuera
     * marcada como rollback-only, asi que la fila del buzon se va con ella. Ver el javadoc de
     * {@code RechazoDelPago}.
     *
     * <p>Es idempotente por lo mismo que {@link #recibir}: dos entregas del mismo pago rechazado no
     * pueden dejar dos filas.
     */
    Recepcion recibirRechazado(PagoRecibido pago, String motivo);

    Optional<PagoRecibido> porPagoId(UUID pagoId);

    /** Marca la imputacion, con su hora y cuantos asientos dejo. */
    void marcarAplicado(long id, int asientos, Instant cuando);

    /**
     * Marca que el libro no lo admitio, con su motivo.
     *
     * <p><b>Corre en su PROPIA transaccion</b>, y no es una comodidad: cuando {@code
     * RegistroDeAbonos} rechaza, la excepcion sale de un metodo {@code @Transactional} anidado y
     * <b>marca la transaccion de fuera como rollback-only</b>. Atrapar la excepcion no la desmarca:
     * el {@code commit} de la de fuera muere con {@code UnexpectedRollbackException} y se lleva por
     * delante la marca del rechazo — el pago quedaria EN_TRANSITO para siempre y el publicador lo
     * reintentaria hasta matarlo, por un motivo que ya se conoce.
     *
     * <p>Es el defecto que #328, #54, #72 y #430 midieron cuatro veces con distinta forma, y la
     * quinta lo encontro la prueba de este buzon: la corrida entera reventaba con «Transaction
     * rolled back because it has been marked as rollback-only».
     */
    void marcarRechazado(long id, String motivo);

    /**
     * Los pagos de un contribuyente que todavia no se imputaron.
     *
     * <p>Es lo que la consulta de deuda lee para poder decir «hay un pago en camino» en vez de
     * mostrar un saldo como si no hubiera pagado (ADR-0026 §4).
     */
    List<PagoRecibido> enTransitoDe(long contribuyenteId);

    /** El recuento de un dia, para la conciliacion que la caja pregunta. */
    Recuento recuentoDe(LocalDate dia);

    /**
     * @param recibidos cuantos pagos de ese dia llegaron
     * @param aplicados cuantos se imputaron
     * @param rechazados cuantos no y estan esperando a alguien
     * @param importeAplicado por cuanto dinero
     */
    record Recuento(int recibidos, int aplicados, int rechazados, Dinero importeAplicado) {}
}
