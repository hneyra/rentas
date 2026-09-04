package kamayuk.rentas.fiscalizacion.dominio;

/**
 * En qué punto está el acta. {@code LIQUIDADA}, {@code RELIQUIDADA} y {@code TRANSFERIDA} son del
 * alcance de #49 —este contexto todavía no calcula ni transfiere nada—; se declaran aquí porque son
 * parte del mismo dominio de la columna {@code estado} (V4), no porque #45 los produzca.
 */
public enum EstadoDeActa {
    ABIERTA,
    LIQUIDADA,
    RELIQUIDADA,
    TRANSFERIDA,
    ANULADA
}
