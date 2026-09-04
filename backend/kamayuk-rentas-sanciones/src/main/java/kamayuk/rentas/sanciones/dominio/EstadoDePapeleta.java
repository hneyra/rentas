package kamayuk.rentas.sanciones.dominio;

/** En qué punto está la papeleta (V4: {@code papeleta.estado}). */
public enum EstadoDePapeleta {
    IMPUESTA,
    NOTIFICADA,
    RESUELTA,
    PAGADA,
    COACTIVA,
    ANULADA,
    PRESCRITA
}
