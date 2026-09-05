package kamayuk.rentas.nucleo.dominio;

/**
 * En que situacion esta una determinacion. Los tres valores son los del {@code CHECK} de la tabla
 * {@code determinacion} (V2).
 */
public enum EstadoDeDeterminacion {
    BORRADOR,
    EMITIDA,
    ANULADA
}
