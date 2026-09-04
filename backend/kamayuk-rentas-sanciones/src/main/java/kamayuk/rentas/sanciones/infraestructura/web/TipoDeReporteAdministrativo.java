package kamayuk.rentas.sanciones.infraestructura.web;

/**
 * Los reportes que emite {@code adm_reportes} (#53, RF-074).
 *
 * <p>Un enumerado y no texto libre: el «tipo de reporte» decide qué consulta se ejecuta, y un texto
 * que no coincida con ninguno tiene que salir como 422 nombrando los que hay, no como una respuesta
 * vacía que parece que no hay datos.
 */
public enum TipoDeReporteAdministrativo {

    /** La relación de notificaciones emitidas, con la papeleta que las siguió. */
    PADRON_NOTIFICACIONES,

    /** Cuántas papeletas administrativas hay y por cuánto, agrupadas. */
    RESUMEN_PAPELETAS,

    /** Lo recaudado por multas administrativas, según el libro. */
    RESUMEN_RECAUDACION
}
