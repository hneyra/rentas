package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDePadronesDeSanciones;
import kamayuk.rentas.sanciones.aplicacion.ModelosDeLosReportesDeSanciones;
import kamayuk.rentas.sanciones.dominio.PapeletaDelPadron;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los dos records de tránsito: {@code /transito/reportes/record-conductor} y {@code
 * …/record-vehicular} (#53, RF-068).
 *
 * <h2>Son el padrón con otro filtro, y por eso comparten consulta</h2>
 *
 * <p>«Historial de infracciones cometidas por un conductor» y «historial de papeletas de un solo
 * vehículo» son la misma pregunta acotada por la licencia o el documento del infractor, o por la
 * placa. Dos consultas separadas para la misma cuenta serían dos oportunidades de divergir, y la
 * que se mira menos es la que se queda mal.
 *
 * <h2>Un record sin sujeto no es un record</h2>
 *
 * <p>Los dos exigen su filtro: el de conductor, la licencia o el documento; el vehicular, la placa.
 * Sin él la consulta devolvería el padrón entero bajo el título «record», que es la manera más
 * silenciosa de entregar el historial de todo el mundo a quien pidió el de una persona.
 */
@RestController
public class RecordsDeTransitoController {

    private static final String ORDEN_POR_OMISION = "fechaInfraccion";

    private final ConsultaDePadronesDeSanciones consulta;
    private final GeneradorDeDocumentos documentos;
    private final Clock reloj;

    public RecordsDeTransitoController(
            ConsultaDePadronesDeSanciones consulta, GeneradorDeDocumentos documentos, Clock reloj) {
        this.consulta = consulta;
        this.documentos = documentos;
        this.reloj = reloj;
    }

    // ---------- Record de conductor ----------

    @GetMapping(Api.RAIZ + "/transito/reportes/record-conductor")
    @RequiereAcceso(acceso = "transito_record_conductor", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<PapeletaDelPadronResource> recordDeConductor(
            @RequestParam(required = false) @Nullable String licencia,
            @RequestParam(required = false) @Nullable String documento,
            ParametrosDePaginacion paginacion) {

        return RespuestaPaginada.de(
                paginaDelConductor(licencia, documento, paginacion), PapeletaDelPadronResource::de);
    }

    @GetMapping(value = Api.RAIZ + "/transito/reportes/record-conductor", params = "formato")
    @RequiereAcceso(acceso = "transito_record_conductor", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> recordDeConductorComoDocumento(
            @RequestParam(required = false) @Nullable String licencia,
            @RequestParam(required = false) @Nullable String documento,
            @RequestParam String formato,
            ParametrosDePaginacion paginacion) {

        Pagina<PapeletaDelPadron> pagina = paginaDelConductor(licencia, documento, paginacion);
        return ReportesDeSanciones.documento(
                documentos,
                ModelosDeLosReportesDeSanciones.delPadronDePapeletas(
                        "Record de conductor",
                        List.of(
                                Campo.de("Licencia de conducir", texto(licencia)),
                                Campo.de("Documento del infractor", texto(documento))),
                        pagina,
                        LocalDate.now(reloj)),
                formato,
                "record-de-conductor");
    }

    // ---------- Record vehicular ----------

    @GetMapping(Api.RAIZ + "/transito/reportes/record-vehicular")
    @RequiereAcceso(acceso = "transito_record_vehicular", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<PapeletaDelPadronResource> recordVehicular(
            @RequestParam(required = false) @Nullable String placa,
            ParametrosDePaginacion paginacion) {

        return RespuestaPaginada.de(
                paginaDelVehiculo(placa, paginacion), PapeletaDelPadronResource::de);
    }

    @GetMapping(value = Api.RAIZ + "/transito/reportes/record-vehicular", params = "formato")
    @RequiereAcceso(acceso = "transito_record_vehicular", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> recordVehicularComoDocumento(
            @RequestParam(required = false) @Nullable String placa,
            @RequestParam String formato,
            ParametrosDePaginacion paginacion) {

        Pagina<PapeletaDelPadron> pagina = paginaDelVehiculo(placa, paginacion);
        return ReportesDeSanciones.documento(
                documentos,
                ModelosDeLosReportesDeSanciones.delPadronDePapeletas(
                        "Record vehicular",
                        List.of(Campo.de("Placa", texto(placa))),
                        pagina,
                        LocalDate.now(reloj)),
                formato,
                "record-vehicular");
    }

    // ------------------------------------------------------------------

    private Pagina<PapeletaDelPadron> paginaDelConductor(
            @Nullable String licencia,
            @Nullable String documento,
            ParametrosDePaginacion paginacion) {

        return consulta.papeletas(
                CriteriosDeTransito.delConductor(licencia, documento),
                paginacion.aPaginacion(ORDEN_POR_OMISION));
    }

    private Pagina<PapeletaDelPadron> paginaDelVehiculo(
            @Nullable String placa, ParametrosDePaginacion paginacion) {

        return consulta.papeletas(
                CriteriosDeTransito.delVehiculo(placa), paginacion.aPaginacion(ORDEN_POR_OMISION));
    }

    private static String texto(@Nullable String valor) {
        return valor == null ? "" : valor;
    }
}
