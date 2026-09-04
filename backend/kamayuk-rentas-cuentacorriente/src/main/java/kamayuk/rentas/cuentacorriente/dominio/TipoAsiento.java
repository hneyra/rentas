package kamayuk.rentas.cuentacorriente.dominio;

/**
 * {@code CARGO} aumenta la deuda; {@code ABONO} la reduce (ADR-0006).
 *
 * <p>El importe de un {@link Asiento} <b>nunca</b> se guarda en negativo: el signo lo pone este
 * campo, no el {@code monto}. Guardar un abono como importe negativo obligaria a que cada consulta
 * supiera sumar con signo en vez de filtrar por tipo, y tarde o temprano alguien sumaria sin mirar.
 */
public enum TipoAsiento {
    CARGO,
    ABONO;

    /** El tipo contrario: es lo que lleva una reversion (ver {@link Asiento#reversionDe}). */
    public TipoAsiento opuesto() {
        return this == CARGO ? ABONO : CARGO;
    }
}
