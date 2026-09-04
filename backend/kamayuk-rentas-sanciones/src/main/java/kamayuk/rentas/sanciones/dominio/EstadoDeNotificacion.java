package kamayuk.rentas.sanciones.dominio;

/**
 * En qué punto está la notificación administrativa previa (V4: {@code
 * notificacion_administrativa.estado}).
 */
public enum EstadoDeNotificacion {
    EMITIDA,
    SUBSANADA,
    VENCIDA,
    ANULADA
}
