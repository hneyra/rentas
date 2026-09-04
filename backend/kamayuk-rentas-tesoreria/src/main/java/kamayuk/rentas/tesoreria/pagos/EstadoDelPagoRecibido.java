package kamayuk.rentas.tesoreria.pagos;

/**
 * En que esta un pago del buzon. Los tres valores de {@code pago_recibido_estado_ck} (V8).
 *
 * <p>Son estados de <b>la imputacion</b>, no del dinero: el dinero esta cobrado desde que la caja
 * emitio el recibo. Lo que estos dicen es si el libro ya se entero.
 */
public enum EstadoDelPagoRecibido {
    /**
     * Llego y todavia no se imputo.
     *
     * <p><b>Es el «pago en transito» de ADR-0026 §4.</b> Entre el cobro y el asiento el saldo esta
     * desactualizado, y tiene que VERSE asi —no como si el contribuyente no hubiera pagado—. Su
     * hora es {@code recibido_en}.
     */
    EN_TRANSITO,
    /** Se imputo. Trae su hora y cuantos asientos dejo. */
    APLICADO,
    /**
     * El libro no lo admitio.
     *
     * <p>Es dinero cobrado que alguien tiene que mirar, y por eso lleva su motivo y la conciliacion
     * del dia lo cuenta aparte. <b>No se reintenta solo</b>: si la obligacion ya se extinguio o el
     * contribuyente no existe, reintentar no lo va a arreglar y solo escondera el problema.
     */
    RECHAZADO
}
