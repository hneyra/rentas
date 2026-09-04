package kamayuk.rentas.catastro.infraestructura.web;

import java.time.LocalDate;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.aplicacion.ModeloDeLaFichaDelContribuyente;
import kamayuk.rentas.catastro.aplicacion.ReporteDeFichaDelContribuyente;
import kamayuk.rentas.catastro.aplicacion.ReporteDeFichaDelContribuyente.Reporte;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ficha del contribuyente: {@code GET /api/v1/catastro/contribuyentes/{codigo}/ficha.pdf} (RF-010).
 *
 * <p><b>Sin {@code formato}, la ruta acaba en {@code .pdf} y devuelve JSON, y esta bien.</b> Es lo
 * que el contrato declara —{@code application/json}—, porque la ruta salio del nombre que le da al
 * boton la pantalla del prototipo. Ese es el <i>contenido</i> del documento, y es lo que la
 * interfaz pinta.
 *
 * <p><b>Con {@code ?formato=PDF|XLS|RTF} devuelve el documento.</b> Los tres, porque el manual los
 * promete en todo reporte (RF-132) y anadirlos al final obligaria a volver sobre cada uno. Se
 * distinguen por el parametro y no por rutas nuevas: el contrato no las tiene, y publicar rutas que
 * ninguna pantalla llama las deja sin dueno.
 *
 * <p>Este reporte <b>no se registra</b> como documento emitido. Es una consulta: se mira, no se
 * emite. Numerar cada vez que alguien abre una ficha llenaria el correlativo de ruido. Lo que si se
 * registra —y se reimprime identico— son los valores, recibos y papeletas, que es para lo que
 * existe {@code EmitirDocumento}.
 *
 * <p><b>El documento se dibuja despues de que cierre la transaccion de la lectura, y eso esta
 * bien</b> (#535): dibujar no toca la base. Lo unico que toca es la pregunta por el regimen de la
 * instalacion —si el papel sale marcado (#122)—, y esa se hace dentro de una transaccion propia que
 * abre {@code RegimenDeLaInstalacionJdbc}, que es donde vive el motivo. Envolver aqui la peticion
 * entera mantendria una conexion tomada mientras se escribe el PDF, y habria que repetirlo en los
 * dieciocho endpoints que generan documentos.
 */
@RestController
@RequestMapping(Api.RAIZ + "/catastro/contribuyentes")
@RequiereAcceso(acceso = "ficha_contribuyente_reporte", privilegio = Privilegio.LECTURA)
public class ReporteController {

    private final ReporteDeFichaDelContribuyente reporte;
    private final GeneradorDeDocumentos documentos;

    public ReporteController(
            ReporteDeFichaDelContribuyente reporte, GeneradorDeDocumentos documentos) {
        this.reporte = reporte;
        this.documentos = documentos;
    }

    @GetMapping(value = "/{codigo}/ficha.pdf", params = "formato")
    public ResponseEntity<byte[]> documento(
            @PathVariable String codigo,
            @RequestParam String formato,
            @RequestParam(required = false) @Nullable String fecha) {

        FormatoDeDocumento elegido = formatoDe(formato);
        Reporte contenido = buscar(codigo, fecha);
        byte[] archivo = documentos.generar(ModeloDeLaFichaDelContribuyente.de(contenido), elegido);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(elegido.tipoDeMedio()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(elegido.nombreDeArchivo("ficha-" + codigo))
                                .build()
                                .toString())
                .body(archivo);
    }

    private static FormatoDeDocumento formatoDe(String formato) {
        try {
            return FormatoDeDocumento.valueOf(formato.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El formato va entre PDF, XLS y RTF: '" + formato + "'");
        }
    }

    @GetMapping("/{codigo}/ficha.pdf")
    public ReporteResource ficha(
            @PathVariable String codigo, @RequestParam(required = false) @Nullable String fecha) {
        return ReporteResource.de(buscar(codigo, fecha));
    }

    private Reporte buscar(String codigo, @Nullable String fecha) {
        LocalDate cuando = fecha == null || fecha.isBlank() ? null : parsear(fecha);
        return reporte.de(codigo, cuando)
                .orElseThrow(
                        () ->
                                new ProblemaDeNegocio(
                                        CodigoDeError.NO_ENCONTRADO,
                                        "No hay ningun contribuyente con ese codigo"));
    }

    private static LocalDate parsear(String fecha) {
        try {
            return LocalDate.parse(fecha);
        } catch (java.time.format.DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "La fecha va en formato AAAA-MM-DD: '" + fecha + "'");
        }
    }
}
