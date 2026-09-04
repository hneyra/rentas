package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeLaHojaDePapeleta;
import kamayuk.rentas.sanciones.aplicacion.ModelosDeLosReportesDeSanciones;
import kamayuk.rentas.sanciones.aplicacion.RegistrarDescargo;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * La hoja informativa de una papeleta: {@code GET
 * /api/v1/transito/papeletas/{numero}/hoja-informativa} (#396, RF-068).
 *
 * <h2>Una papeleta que no existe responde 404, no una hoja vacía</h2>
 *
 * <p>Es el criterio de aceptación del issue escrito en el {@code catch}: una hoja con todos los
 * campos en blanco es indistinguible de un acta sin datos, y quien la imprimiera creería tener el
 * acta de algo. El aislamiento por municipalidad no hace falta comprobarlo aquí —lo hace la
 * política RLS—: una papeleta de otra municipalidad sencillamente no existe para esta consulta, y
 * la respuesta es el mismo 404.
 *
 * <h2>Sin {@code formato} devuelve JSON; con {@code ?formato=PDF|XLS|RTF}, el documento</h2>
 *
 * <p>Como las trece hojas de #53, y por el mismo parámetro: el contrato no tiene rutas para los
 * formatos y publicar una ruta que ninguna pantalla llama la deja sin dueño. La dibujan los
 * renderizadores de {@code kamayuk.rentas.documentos}, así que la hoja lleva el mismo pie y el
 * mismo punto de firma que las demás (RNF-084).
 */
@RestController
public class HojaDePapeletaController {

    private final ConsultaDeLaHojaDePapeleta consulta;
    private final GeneradorDeDocumentos documentos;
    private final Clock reloj;

    public HojaDePapeletaController(
            ConsultaDeLaHojaDePapeleta consulta, GeneradorDeDocumentos documentos, Clock reloj) {
        this.consulta = consulta;
        this.documentos = documentos;
        this.reloj = reloj;
    }

    @GetMapping(Api.RAIZ + "/transito/papeletas/{numero}/hoja-informativa")
    @RequiereAcceso(acceso = "transito_papeleta_reporte", privilegio = Privilegio.LECTURA)
    public HojaInformativaResource hoja(@PathVariable String numero) {
        return HojaInformativaResource.de(hojaDe(numero));
    }

    @GetMapping(
            value = Api.RAIZ + "/transito/papeletas/{numero}/hoja-informativa",
            params = "formato")
    @RequiereAcceso(acceso = "transito_papeleta_reporte", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> hojaComoDocumento(
            @PathVariable String numero, @RequestParam String formato) {

        return ReportesDeSanciones.documento(
                documentos,
                ModelosDeLosReportesDeSanciones.deLaHojaDePapeleta(hojaDe(numero)),
                formato,
                "hoja-informativa-de-papeleta");
    }

    // ------------------------------------------------------------------

    private ConsultaDeLaHojaDePapeleta.Hoja hojaDe(@Nullable String numero) {
        String limpio = PeticionesDeSanciones.exigir(numero, "numero");
        try {
            return consulta.de(limpio, LocalDate.now(reloj));
        } catch (RegistrarDescargo.PapeletaInexistente noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO, PeticionesDeSanciones.mensajeDe(noExiste));
        }
    }
}
