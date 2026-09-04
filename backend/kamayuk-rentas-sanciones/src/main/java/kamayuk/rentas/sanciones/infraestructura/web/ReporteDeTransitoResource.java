package kamayuk.rentas.sanciones.infraestructura.web;

import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;

/**
 * Lo que devuelve el emisor de reportes de tránsito cuando no se le pide un formato (#396).
 *
 * <p>Exactamente <b>una</b> de las cuatro secciones viene llena, y la dice {@link #reporte}. Es una
 * unión y no cuatro respuestas distintas porque la pantalla es una sola: el «tipo de reporte» de la
 * cabecera decide qué grilla se dibuja, y con cuatro formas de respuesta el cliente tendría que
 * adivinar cuál le llegó.
 *
 * @param reporte cuál se emitió
 * @param papeletas lleno en los dos padrones de papeletas y en los dos records
 * @param constancias lleno solo en {@code PADRON_CONSTANCIAS}
 * @param resumenDePapeletas lleno en los tres resúmenes de papeletas
 * @param recaudacion llena solo en {@code RESUMEN_RECAUDACION}
 */
public record ReporteDeTransitoResource(
        String reporte,
        @Nullable RespuestaPaginada<PapeletaDelPadronResource> papeletas,
        @Nullable RespuestaPaginada<ConstanciaLibreResource> constancias,
        @Nullable ResumenDePapeletasResource resumenDePapeletas,
        @Nullable RecaudacionDeMultasResource recaudacion) {

    static ReporteDeTransitoResource dePapeletas(
            TipoDeReporteDeTransito reporte, RespuestaPaginada<PapeletaDelPadronResource> pagina) {
        return new ReporteDeTransitoResource(reporte.name(), pagina, null, null, null);
    }

    static ReporteDeTransitoResource deConstancias(
            RespuestaPaginada<ConstanciaLibreResource> pagina) {
        return new ReporteDeTransitoResource(
                TipoDeReporteDeTransito.PADRON_CONSTANCIAS.name(), null, pagina, null, null);
    }

    static ReporteDeTransitoResource delResumen(
            TipoDeReporteDeTransito reporte, ResumenDePapeletasResource resumen) {
        return new ReporteDeTransitoResource(reporte.name(), null, null, resumen, null);
    }

    static ReporteDeTransitoResource deLaRecaudacion(RecaudacionDeMultasResource recaudacion) {
        return new ReporteDeTransitoResource(
                TipoDeReporteDeTransito.RESUMEN_RECAUDACION.name(), null, null, null, recaudacion);
    }
}
