package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDePadronesDeSanciones;
import kamayuk.rentas.sanciones.aplicacion.ModelosDeLosReportesDeSanciones;
import kamayuk.rentas.sanciones.dominio.ConstanciaLibre;
import kamayuk.rentas.sanciones.dominio.PapeletaDelPadron;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los tres padrones de tránsito: {@code /transito/reportes/padron}, {@code …/padron-coactiva} y
 * {@code …/padron-constancias} (#53, RF-068, RF-073, RF-074).
 *
 * <h2>Sin {@code formato} devuelven JSON; con {@code ?formato=PDF|XLS|RTF}, el documento</h2>
 *
 * <p>Los tres formatos, porque el manual los promete en todo reporte (RF-132), y por el mismo
 * parámetro y no por rutas nuevas: el contrato no las tiene, y publicar rutas que ninguna pantalla
 * llama las deja sin dueño. Los dibujan los renderizadores de {@code kamayuk.rentas.documentos};
 * aquí no se escribe un exportador propio.
 *
 * <h2>El padrón de coactiva de aquí no sabe de ejecutores</h2>
 *
 * <p>La pantalla ofrece filtrar por «ejecutor» y por «estado del expediente». Ninguno de los dos es
 * columna de {@code papeleta}: viven en {@code coactiva}, que es un contexto distinto con sus
 * propias tablas y su propia operación ({@code GET /coactiva/expedientes}). Este endpoint los
 * <b>rechaza</b> con 422 diciéndolo, en vez de aceptarlos y devolver el padrón sin filtrar: un
 * filtro que se ignora en silencio es peor que uno que no existe, porque quien opera cree que está
 * mirando una parte cuando está mirando el todo.
 */
@RestController
public class PadronesDeTransitoController {

    private static final String ORDEN_POR_OMISION = "fechaInfraccion";

    private final ConsultaDePadronesDeSanciones consulta;
    private final GeneradorDeDocumentos documentos;
    private final Clock reloj;

    public PadronesDeTransitoController(
            ConsultaDePadronesDeSanciones consulta, GeneradorDeDocumentos documentos, Clock reloj) {
        this.consulta = consulta;
        this.documentos = documentos;
        this.reloj = reloj;
    }

    // ---------- Padron de papeletas de transito ----------

    @GetMapping(Api.RAIZ + "/transito/reportes/padron")
    @RequiereAcceso(acceso = "transito_padron", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<PapeletaDelPadronResource> padron(
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String estado,
            ParametrosDePaginacion paginacion) {

        return RespuestaPaginada.de(
                paginaDelPadron(desde, hasta, estado, null, paginacion),
                PapeletaDelPadronResource::de);
    }

    @GetMapping(value = Api.RAIZ + "/transito/reportes/padron", params = "formato")
    @RequiereAcceso(acceso = "transito_padron", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> padronComoDocumento(
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String estado,
            @RequestParam String formato,
            ParametrosDePaginacion paginacion) {

        Pagina<PapeletaDelPadron> pagina = paginaDelPadron(desde, hasta, estado, null, paginacion);
        return ReportesDeSanciones.documento(
                documentos,
                ModelosDeLosReportesDeSanciones.delPadronDePapeletas(
                        "Padron de papeletas de transito",
                        criterioDePapeletas(desde, hasta, estado),
                        pagina,
                        LocalDate.now(reloj)),
                formato,
                "padron-de-papeletas");
    }

    // ---------- Padron de papeletas enviadas a coactiva ----------

    @GetMapping(Api.RAIZ + "/transito/reportes/padron-coactiva")
    @RequiereAcceso(acceso = "transito_padron_coactiva", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<PapeletaDelPadronResource> padronCoactiva(
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String ejecutor,
            @RequestParam(required = false) @Nullable String estadoDelExpediente,
            ParametrosDePaginacion paginacion) {

        rechazarLosDeCoactiva(ejecutor, estadoDelExpediente);
        return RespuestaPaginada.de(
                paginaDelPadron(desde, hasta, null, Boolean.TRUE, paginacion),
                PapeletaDelPadronResource::de);
    }

    @GetMapping(value = Api.RAIZ + "/transito/reportes/padron-coactiva", params = "formato")
    @RequiereAcceso(acceso = "transito_padron_coactiva", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> padronCoactivaComoDocumento(
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String ejecutor,
            @RequestParam(required = false) @Nullable String estadoDelExpediente,
            @RequestParam String formato,
            ParametrosDePaginacion paginacion) {

        rechazarLosDeCoactiva(ejecutor, estadoDelExpediente);
        Pagina<PapeletaDelPadron> pagina =
                paginaDelPadron(desde, hasta, null, Boolean.TRUE, paginacion);
        return ReportesDeSanciones.documento(
                documentos,
                ModelosDeLosReportesDeSanciones.delPadronDePapeletas(
                        "Padron de papeletas con resolucion de multa emitida",
                        criterioDePapeletas(desde, hasta, null),
                        pagina,
                        LocalDate.now(reloj)),
                formato,
                "padron-coactiva");
    }

    // ---------- Padron de constancias libres ----------

    @GetMapping(Api.RAIZ + "/transito/reportes/padron-constancias")
    @RequiereAcceso(acceso = "transito_padron_constancias", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<ConstanciaLibreResource> padronDeConstancias(
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String nDeConstancia,
            @RequestParam(required = false) @Nullable String usuarioQueEmitio,
            ParametrosDePaginacion paginacion) {

        return RespuestaPaginada.de(
                paginaDeConstancias(desde, hasta, nDeConstancia, usuarioQueEmitio, paginacion),
                ConstanciaLibreResource::de);
    }

    @GetMapping(value = Api.RAIZ + "/transito/reportes/padron-constancias", params = "formato")
    @RequiereAcceso(acceso = "transito_padron_constancias", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> padronDeConstanciasComoDocumento(
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String nDeConstancia,
            @RequestParam(required = false) @Nullable String usuarioQueEmitio,
            @RequestParam String formato,
            ParametrosDePaginacion paginacion) {

        Pagina<ConstanciaLibre> pagina =
                paginaDeConstancias(desde, hasta, nDeConstancia, usuarioQueEmitio, paginacion);
        List<Campo> criterio =
                List.of(
                        Campo.de("Desde", texto(desde)),
                        Campo.de("Hasta", texto(hasta)),
                        Campo.de("Numero", texto(nDeConstancia)),
                        Campo.de("Usuario que emitio", texto(usuarioQueEmitio)));

        return ReportesDeSanciones.documento(
                documentos,
                ModelosDeLosReportesDeSanciones.delPadronDeConstancias(
                        criterio, pagina, LocalDate.now(reloj)),
                formato,
                "padron-de-constancias");
    }

    // ------------------------------------------------------------------

    private Pagina<PapeletaDelPadron> paginaDelPadron(
            @Nullable String desde,
            @Nullable String hasta,
            @Nullable String estado,
            @Nullable Boolean conValorEmitido,
            ParametrosDePaginacion paginacion) {

        return consulta.papeletas(
                CriteriosDeTransito.delPadron(desde, hasta, estado, conValorEmitido),
                paginacion(paginacion));
    }

    private Pagina<ConstanciaLibre> paginaDeConstancias(
            @Nullable String desde,
            @Nullable String hasta,
            @Nullable String numero,
            @Nullable String usuarioQueEmitio,
            ParametrosDePaginacion paginacion) {

        return consulta.constancias(
                CriteriosDeTransito.deConstancias(desde, hasta, numero, usuarioQueEmitio),
                paginacion.aPaginacion("fechaEmision"));
    }

    private static Paginacion paginacion(ParametrosDePaginacion parametros) {
        return parametros.aPaginacion(ORDEN_POR_OMISION);
    }

    private static List<Campo> criterioDePapeletas(
            @Nullable String desde, @Nullable String hasta, @Nullable String estado) {
        return List.of(
                Campo.de("Desde", texto(desde)),
                Campo.de("Hasta", texto(hasta)),
                Campo.de("Estado", texto(estado)));
    }

    /**
     * Los dos filtros que este padrón no puede servir, y por qué.
     *
     * <p>Se rechazan solo si vienen con valor: la pantalla los manda vacíos mientras nadie los
     * toque, y un 422 por un parámetro en blanco impediría abrir el padrón.
     */
    private static void rechazarLosDeCoactiva(
            @Nullable String ejecutor, @Nullable String estadoDelExpediente) {
        if (PeticionesDeSanciones.vacioEsNulo(ejecutor) != null
                || PeticionesDeSanciones.vacioEsNulo(estadoDelExpediente) != null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El ejecutor y el estado del expediente no son columnas de la papeleta: viven"
                            + " en el expediente coactivo. Ese filtro lo sirve GET"
                            + " /coactiva/expedientes; este padron lista las papeletas con"
                            + " resolucion de multa emitida");
        }
    }

    private static String texto(@Nullable String valor) {
        return valor == null ? "" : valor;
    }
}
