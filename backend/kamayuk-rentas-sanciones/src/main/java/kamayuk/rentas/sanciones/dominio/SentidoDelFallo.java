package kamayuk.rentas.sanciones.dominio;

/**
 * Con qué sentido resuelve la gerencia el recurso del administrado (V41: {@code
 * resolucion_gerencia.sentido}).
 *
 * <p>El vocabulario es el del desplegable «Sentido del fallo» de la pantalla {@code
 * transito_descargos}.
 */
public enum SentidoDelFallo {
    FUNDADO,
    FUNDADO_EN_PARTE,
    INFUNDADO,
    IMPROCEDENTE
}
