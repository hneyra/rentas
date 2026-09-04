package kamayuk.rentas.sanciones.dominio;

/**
 * Qué recurso presenta el administrado contra la papeleta (V41: {@code descargo.tipo_recurso}).
 *
 * <p>El vocabulario es el del desplegable «Tipo de recurso» de la pantalla {@code
 * transito_descargos}, y coincide exactamente con el {@code CHECK} de la columna: si aquí
 * apareciera un quinto, el {@code INSERT} fallaría en ejecución, que es tarde.
 */
public enum TipoDeRecurso {
    DESCARGO,
    RECONSIDERACION,
    APELACION,
    NULIDAD
}
