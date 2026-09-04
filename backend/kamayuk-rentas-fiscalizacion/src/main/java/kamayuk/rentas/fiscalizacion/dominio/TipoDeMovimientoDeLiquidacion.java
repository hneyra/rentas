package kamayuk.rentas.fiscalizacion.dominio;

/** Qué hace un movimiento del historial de una liquidación (#49). */
public enum TipoDeMovimientoDeLiquidacion {

    /** La abre. Una por liquidación, y la base lo garantiza con un índice único parcial (V39). */
    APERTURA,

    /** Le cambia el estado. */
    ESTADO
}
