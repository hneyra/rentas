package kamayuk.rentas.tesoreria.pagos;

/** Que le paso al dinero en la caja. Los dos valores de {@code pago_recibido_tipo_ck} (V8). */
public enum TipoDePagoRecibido {
    /** Se cobro. Hay que imputarlo al libro. */
    PAGO_REGISTRADO,
    /**
     * Se anulo el mismo dia. Hay que <b>reversar</b>, nunca borrar.
     *
     * <p>El libro es inmutable (ADR-0006) y {@code cuenta_corriente_asiento} esta en las tablas
     * protegidas: un {@code DELETE} ahi rompe el build antes de llegar a ejecucion, que es la
     * segunda mitad del criterio 4 del encargo de P5D.
     */
    PAGO_ANULADO
}
