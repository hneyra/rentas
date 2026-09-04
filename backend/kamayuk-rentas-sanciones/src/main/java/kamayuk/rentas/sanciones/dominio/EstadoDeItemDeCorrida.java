package kamayuk.rentas.sanciones.dominio;

/**
 * En qué punto está una papeleta candidata de una corrida masiva (V47: {@code
 * papeleta_masivo_item.estado}).
 *
 * <p>Cuatro estados y no dos, porque las tres maneras de no acabar en un valor se arreglan de
 * formas distintas y quien opera necesita saber cuál le tocó:
 *
 * <ul>
 *   <li>{@link #SIN_DEUDA} — la papeleta ya está pagada o se dio de baja. No hay nada que
 *       formalizar y no hay nada que hacer.
 *   <li>{@link #NO_PROCEDE} — falta la resolución de gerencia que ordena la cobranza, o está sin
 *       notificar, o su plazo todavía corre. Se arregla dictándola, notificándola o esperando, y el
 *       {@code motivo} de la fila dice cuál de las tres.
 *   <li>{@link #PENDIENTE} — todavía no se ha intentado, o el intento falló y se reintentará.
 * </ul>
 */
public enum EstadoDeItemDeCorrida {

    /** Todavía no procesada; la generación la recorre. */
    PENDIENTE,

    /** Se emitió su resolución de multa. */
    GENERADO,

    /** No debe nada a la fecha del criterio. */
    SIN_DEUDA,

    /** Le falta la resolución que ordena la cobranza, o su plazo. */
    NO_PROCEDE
}
