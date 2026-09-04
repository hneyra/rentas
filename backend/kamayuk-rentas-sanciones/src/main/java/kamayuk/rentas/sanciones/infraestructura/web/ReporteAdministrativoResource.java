package kamayuk.rentas.sanciones.infraestructura.web;

import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;

/**
 * Lo que devuelve el emisor de reportes administrativos cuando no se le pide un formato (#53,
 * RF-074).
 *
 * <p>Exactamente <b>una</b> de las tres secciones viene llena, y la dice {@link #reporte}. Es una
 * unión y no tres respuestas distintas porque la pantalla es una sola: el «tipo de reporte» de la
 * cabecera decide qué grilla se dibuja, y con tres formas de respuesta el cliente tendría que
 * adivinar cuál le llegó.
 *
 * @param reporte cuál se emitió
 * @param padronDeNotificaciones lleno solo en {@code PADRON_NOTIFICACIONES}
 * @param resumenDePapeletas lleno solo en {@code RESUMEN_PAPELETAS}
 * @param recaudacion llena solo en {@code RESUMEN_RECAUDACION}
 */
public record ReporteAdministrativoResource(
        String reporte,
        @Nullable RespuestaPaginada<NotificacionDelPadronResource> padronDeNotificaciones,
        @Nullable ResumenDePapeletasResource resumenDePapeletas,
        @Nullable RecaudacionDeMultasResource recaudacion) {

    static ReporteAdministrativoResource delPadron(
            RespuestaPaginada<NotificacionDelPadronResource> padron) {
        return new ReporteAdministrativoResource(
                TipoDeReporteAdministrativo.PADRON_NOTIFICACIONES.name(), padron, null, null);
    }

    static ReporteAdministrativoResource delResumen(ResumenDePapeletasResource resumen) {
        return new ReporteAdministrativoResource(
                TipoDeReporteAdministrativo.RESUMEN_PAPELETAS.name(), null, resumen, null);
    }

    static ReporteAdministrativoResource deLaRecaudacion(RecaudacionDeMultasResource recaudacion) {
        return new ReporteAdministrativoResource(
                TipoDeReporteAdministrativo.RESUMEN_RECAUDACION.name(), null, null, recaudacion);
    }
}
