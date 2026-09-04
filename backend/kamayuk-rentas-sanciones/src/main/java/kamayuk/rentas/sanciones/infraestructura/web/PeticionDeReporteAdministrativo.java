package kamayuk.rentas.sanciones.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/infracciones/administrativas/reportes} (#53, RF-074). <b>Lista
 * blanca</b>: lo que no está aquí no entra.
 *
 * <p>Es el «emisor de reportes» del manual: una sola pantalla que emite varios, y por eso el tipo
 * de reporte viaja en el cuerpo. Los criterios que cada uno admite son los de este record; el que
 * un reporte no use uno lo ignora, y eso está bien porque los tres son de la misma familia y del
 * mismo rango.
 *
 * @param reporte cuál de los tres se emite
 * @param desde primer día del intervalo; si falta, el 1 de enero del ejercicio en curso
 * @param hasta último día; si falta, el 31 de diciembre
 * @param agrupadoPor cómo se agrupa el resumen de papeletas
 * @param estado acota el estado de la notificación o de la papeleta, según el reporte
 * @param formato PDF, XLS o RTF; si falta, sale el JSON en vez del documento (RF-132)
 */
public record PeticionDeReporteAdministrativo(
        @Nullable String reporte,
        @Nullable String desde,
        @Nullable String hasta,
        @Nullable String agrupadoPor,
        @Nullable String estado,
        @Nullable String formato) {}
