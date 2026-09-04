package kamayuk.rentas.licencias.infraestructura.web;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.licencias.aplicacion.EmitirLicenciaDeEdificacion;
import kamayuk.rentas.licencias.aplicacion.RevalidarLicenciaDeEdificacion;
import kamayuk.rentas.licencias.dominio.VigenciaDeLaLicencia;
import org.jspecify.annotations.Nullable;

/**
 * Lo que devuelve un acto sobre un FUE: la emision de la licencia y su revalidacion (#48).
 *
 * <h2>Los bytes no viajan en el JSON</h2>
 *
 * <p>Mismo criterio que {@link ActoDeLicenciaResource}: la respuesta lleva el numero del papel, su
 * formato, su resumen SHA-256 y su tamanio; la descarga es otra peticion.
 *
 * <h2>Las vigencias viajan enteras, y es el AC 4 leible desde fuera</h2>
 *
 * <p>Tras una revalidacion, {@link #vigencias} trae <b>los dos</b> tramos con su orden y sus
 * fechas. Que los dos salgan en el JSON es lo que permite comprobar, sin mirar la base, que la
 * revalidacion no sustituyo la vigencia original.
 *
 * @param nroExpediente el expediente sobre el que se actuo
 * @param nroLicencia el numero de la licencia
 * @param acto que paso: {@code EMISION} o {@code REVALIDACION}
 * @param fecha el dia del acto
 * @param resolucion el papel del acto
 * @param vigencias los tramos de vigencia de la licencia, en orden
 * @param valorDeObraNoDisponible por que el papel imprimio «—» donde iba el valor de obra; nulo
 *     cuando si se pudo valorizar (AC 2)
 */
public record ActoDeEdificacionResource(
        String nroExpediente,
        String nroLicencia,
        String acto,
        LocalDate fecha,
        ActoDeLicenciaResource.DocumentoResource resolucion,
        List<FueResource.VigenciaResource> vigencias,
        @Nullable String valorDeObraNoDisponible) {

    /** La emision de la licencia de edificacion. */
    public static ActoDeEdificacionResource de(
            EmitirLicenciaDeEdificacion.LicenciaEmitida emitida) {
        return new ActoDeEdificacionResource(
                emitida.fue().expediente(),
                emitida.numeroDeLicencia(),
                "EMISION",
                emitida.emision().fecha(),
                ActoDeLicenciaResource.DocumentoResource.de(emitida.documento()),
                List.of(vigenciaDe(emitida.vigencia())),
                emitida.valorizacion().motivo());
    }

    /** La revalidacion, con los dos tramos de vigencia. */
    public static ActoDeEdificacionResource de(
            RevalidarLicenciaDeEdificacion.Revalidacion revalidacion,
            List<VigenciaDeLaLicencia> vigencias) {
        return new ActoDeEdificacionResource(
                revalidacion.expedienteDeRevalidacion().expediente(),
                revalidacion.numeroDeLicencia(),
                "REVALIDACION",
                revalidacion.movimiento().fecha(),
                ActoDeLicenciaResource.DocumentoResource.de(revalidacion.resolucion()),
                vigencias.stream().map(ActoDeEdificacionResource::vigenciaDe).toList(),
                null);
    }

    private static FueResource.VigenciaResource vigenciaDe(VigenciaDeLaLicencia vigencia) {
        return new FueResource.VigenciaResource(
                vigencia.orden(), vigencia.desde(), vigencia.hasta());
    }
}
