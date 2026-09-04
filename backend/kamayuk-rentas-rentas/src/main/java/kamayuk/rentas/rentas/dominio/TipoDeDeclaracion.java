package kamayuk.rentas.rentas.dominio;

/**
 * Que formulario es, en los mismos cinco valores que {@code declaracion_jurada_tipo_check} (V2).
 *
 * <p>{@code HR} (Hoja Resumen), {@code PU} (Predio Urbano) y {@code PR} (Predio Rustico) son los
 * tres del titulo de #28: la declaracion jurada del predial. {@code VEHICULAR} es la del padron de
 * #26. {@code RECTIFICATORIA} es un formulario en si mismo —no una combinacion con los otros
 * cuatro— y siempre trae {@link DeclaracionJurada#djRectificaId} apuntando a la DJ que sustituye
 * (regla 4): la anterior no se modifica, queda intacta y sustituida.
 */
public enum TipoDeDeclaracion {
    HR,
    PU,
    PR,
    VEHICULAR,
    RECTIFICATORIA
}
