package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.RecaudadoEnElLibro;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDePadronesDeSanciones;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeResumenesDeSanciones;
import kamayuk.rentas.sanciones.aplicacion.ModelosDeLosReportesDeSanciones;
import kamayuk.rentas.sanciones.dominio.AgrupacionDelResumen;
import kamayuk.rentas.sanciones.dominio.ConstanciaLibre;
import kamayuk.rentas.sanciones.dominio.CriterioDePadron;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.PapeletaDelPadron;
import kamayuk.rentas.sanciones.dominio.ResumenDePapeletas;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * El emisor de los reportes de tránsito: {@code POST /api/v1/transito/reportes} (#396, RF-068,
 * RF-073, RF-074).
 *
 * <h2>El emisor no duplica las consultas: las llama</h2>
 *
 * <p>Es la pantalla «emisor de reportes» del manual y la entrada del centro de reportes (ADR-0014
 * §5): una sola opción tras la que se pliegan las hojas del módulo. Detrás no hay ninguna consulta
 * nueva —llama a los mismos {@code ConsultaDe…} que los {@code GET} y arma sus criterios con el
 * mismo {@link CriteriosDeTransito}—, porque dos caminos para la misma cuenta son dos oportunidades
 * de divergir y el que se mira menos es el que se queda mal.
 *
 * <h2>Un criterio que el reporte no usa se rechaza con 422 nombrándolo</h2>
 *
 * <p>Nueve hojas con criterios que no se parecen: una placa, una licencia, un número de constancia,
 * un año, un agrupador. Aceptar «placa» al pedir el resumen de recaudación y no mirarla devolvería
 * la recaudación de <b>todas</b> las placas bajo una hoja que quien la pidió cree acotada a una, y
 * ni el papel ni la respuesta lo dirían. Es el mismo principio que ya rechaza el «ejecutor» en el
 * padrón de coactiva.
 *
 * <h2>Sin {@code formato} devuelve JSON; con {@code formato}, el documento</h2>
 *
 * <p>Los tres de RF-132, dibujados por los renderizadores de {@code kamayuk.rentas.documentos}:
 * aquí no se escribe un exportador propio.
 */
@RestController
public class ReportesDeTransitoController {

    /**
     * El emisor no pagina: emite la hoja entera hasta el tope.
     *
     * <p>La pantalla del emisor no dibuja paginador —elige un reporte y lo emite—, así que pedir
     * «la página 3 del padrón» no es una pregunta que se pueda hacer desde ahí. El tope sigue
     * siendo el de {@link Paginacion}: una hoja no se lleva el padrón entero a memoria.
     */
    private static final String ORDEN_POR_OMISION = "fechaInfraccion";

    private final ConsultaDePadronesDeSanciones padrones;
    private final ConsultaDeResumenesDeSanciones resumenes;
    private final GeneradorDeDocumentos documentos;
    private final Clock reloj;

    public ReportesDeTransitoController(
            ConsultaDePadronesDeSanciones padrones,
            ConsultaDeResumenesDeSanciones resumenes,
            GeneradorDeDocumentos documentos,
            Clock reloj) {
        this.padrones = padrones;
        this.resumenes = resumenes;
        this.documentos = documentos;
        this.reloj = reloj;
    }

    @PostMapping(Api.RAIZ + "/transito/reportes")
    @RequiereAcceso(acceso = "transito_reportes", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<?> emitir(@RequestBody PeticionDeReporteDeTransito peticion) {
        TipoDeReporteDeTransito reporte =
                PeticionesDeSanciones.enumeradoDe(
                        TipoDeReporteDeTransito.class, peticion.reporte(), "reporte");
        rechazarLosCriteriosDeMas(peticion, reporte);

        boolean comoDocumento = ReportesDeSanciones.pideDocumento(peticion.formato());
        String formato = comoDocumento ? Objects.requireNonNull(peticion.formato()) : "";

        return switch (reporte) {
            case PADRON ->
                    hojaDePapeletas(
                            reporte,
                            CriteriosDeTransito.delPadron(
                                    peticion.desde(), peticion.hasta(), peticion.estado(), null),
                            "Padron de papeletas de transito",
                            List.of(
                                    Campo.de("Desde", texto(peticion.desde())),
                                    Campo.de("Hasta", texto(peticion.hasta())),
                                    Campo.de("Estado", texto(peticion.estado()))),
                            "padron-de-papeletas",
                            comoDocumento,
                            formato);
            case PADRON_COACTIVA ->
                    hojaDePapeletas(
                            reporte,
                            CriteriosDeTransito.delPadron(
                                    peticion.desde(), peticion.hasta(), null, Boolean.TRUE),
                            "Padron de papeletas con resolucion de multa emitida",
                            List.of(
                                    Campo.de("Desde", texto(peticion.desde())),
                                    Campo.de("Hasta", texto(peticion.hasta())),
                                    Campo.de("Estado", "")),
                            "padron-coactiva",
                            comoDocumento,
                            formato);
            case RECORD_CONDUCTOR ->
                    hojaDePapeletas(
                            reporte,
                            CriteriosDeTransito.delConductor(
                                    peticion.licencia(), peticion.documento()),
                            "Record de conductor",
                            List.of(
                                    Campo.de("Licencia de conducir", texto(peticion.licencia())),
                                    Campo.de(
                                            "Documento del infractor",
                                            texto(peticion.documento()))),
                            "record-de-conductor",
                            comoDocumento,
                            formato);
            case RECORD_VEHICULAR ->
                    hojaDePapeletas(
                            reporte,
                            CriteriosDeTransito.delVehiculo(peticion.placa()),
                            "Record vehicular",
                            List.of(Campo.de("Placa", texto(peticion.placa()))),
                            "record-vehicular",
                            comoDocumento,
                            formato);
            case PADRON_CONSTANCIAS -> {
                Pagina<ConstanciaLibre> pagina =
                        padrones.constancias(
                                CriteriosDeTransito.deConstancias(
                                        peticion.desde(),
                                        peticion.hasta(),
                                        peticion.nDeConstancia(),
                                        peticion.usuarioQueEmitio()),
                                Paginacion.de(0, Paginacion.TAMANO_MAXIMO, "fechaEmision"));
                yield comoDocumento
                        ? ReportesDeSanciones.documento(
                                documentos,
                                ModelosDeLosReportesDeSanciones.delPadronDeConstancias(
                                        List.of(
                                                Campo.de("Desde", texto(peticion.desde())),
                                                Campo.de("Hasta", texto(peticion.hasta())),
                                                Campo.de("Numero", texto(peticion.nDeConstancia())),
                                                Campo.de(
                                                        "Usuario que emitio",
                                                        texto(peticion.usuarioQueEmitio()))),
                                        pagina,
                                        hoy()),
                                formato,
                                "padron-de-constancias")
                        : ResponseEntity.ok(
                                ReporteDeTransitoResource.deConstancias(
                                        RespuestaPaginada.de(pagina, ConstanciaLibreResource::de)));
            }
            case RESUMEN_PAPELETAS ->
                    hojaDeResumen(
                            reporte,
                            CriteriosDeTransito.delResumen(
                                    hoy(), peticion.desde(), peticion.hasta(), null, null, null),
                            agrupacionDe(peticion.agrupadoPor()),
                            "Resumen de papeletas pendientes y pagadas",
                            List.of(Campo.de("Familia", Familia.TRANSITO.name())),
                            "resumen-de-papeletas",
                            comoDocumento,
                            formato);
            case RESUMEN_CODIGO ->
                    hojaDeResumen(
                            reporte,
                            CriteriosDeTransito.delResumen(
                                    hoy(),
                                    peticion.desde(),
                                    peticion.hasta(),
                                    peticion.estado(),
                                    peticion.codigoDeInfraccion(),
                                    null),
                            AgrupacionDelResumen.CODIGO,
                            "Resumen de papeletas por codigo de infraccion",
                            List.of(
                                    Campo.de(
                                            "Codigo de infraccion",
                                            texto(peticion.codigoDeInfraccion())),
                                    Campo.de("Estado", texto(peticion.estado()))),
                            "resumen-por-codigo",
                            comoDocumento,
                            formato);
            case RESUMEN_PLACA ->
                    hojaDeResumen(
                            reporte,
                            CriteriosDeTransito.delResumen(
                                    hoy(),
                                    peticion.desde(),
                                    peticion.hasta(),
                                    peticion.estado(),
                                    null,
                                    peticion.iniciales2Letras()),
                            AgrupacionDelResumen.PLACA,
                            "Resumen de papeletas por iniciales de placa",
                            List.of(
                                    Campo.de("Iniciales", texto(peticion.iniciales2Letras())),
                                    Campo.de("Estado", texto(peticion.estado()))),
                            "resumen-por-placa",
                            comoDocumento,
                            formato);
            case RESUMEN_RECAUDACION -> {
                int ejercicio = CriteriosDeTransito.ejercicioDe(peticion.ano(), hoy());
                RecaudadoEnElLibro recaudado =
                        resumenes.recaudacion(
                                Familia.TRANSITO,
                                LocalDate.of(ejercicio, 1, 1),
                                LocalDate.of(ejercicio, 12, 31),
                                hoy());
                yield comoDocumento
                        ? ReportesDeSanciones.documento(
                                documentos,
                                ModelosDeLosReportesDeSanciones.deLaRecaudacion(
                                        "Resumen de recaudacion por papeletas de transito",
                                        List.of(Campo.de("Familia", Familia.TRANSITO.name())),
                                        recaudado),
                                formato,
                                "resumen-de-recaudacion")
                        : ResponseEntity.ok(
                                ReporteDeTransitoResource.deLaRecaudacion(
                                        RecaudacionDeMultasResource.de(recaudado)));
            }
        };
    }

    // ------------------------------------------------------------------

    private ResponseEntity<?> hojaDePapeletas(
            TipoDeReporteDeTransito reporte,
            CriterioDePadron criterio,
            String titulo,
            List<Campo> cabecera,
            String nombreBase,
            boolean comoDocumento,
            String formato) {

        Pagina<PapeletaDelPadron> pagina =
                padrones.papeletas(
                        criterio, Paginacion.de(0, Paginacion.TAMANO_MAXIMO, ORDEN_POR_OMISION));

        if (!comoDocumento) {
            return ResponseEntity.ok(
                    ReporteDeTransitoResource.dePapeletas(
                            reporte, RespuestaPaginada.de(pagina, PapeletaDelPadronResource::de)));
        }
        ModeloDeDocumento modelo =
                ModelosDeLosReportesDeSanciones.delPadronDePapeletas(
                        titulo, cabecera, pagina, hoy());
        return ReportesDeSanciones.documento(documentos, modelo, formato, nombreBase);
    }

    private ResponseEntity<?> hojaDeResumen(
            TipoDeReporteDeTransito reporte,
            CriterioDePadron criterio,
            AgrupacionDelResumen agrupacion,
            String titulo,
            List<Campo> cabecera,
            String nombreBase,
            boolean comoDocumento,
            String formato) {

        ResumenDePapeletas resumen = resumenes.resumir(criterio, agrupacion, hoy());

        if (!comoDocumento) {
            return ResponseEntity.ok(
                    ReporteDeTransitoResource.delResumen(
                            reporte, ResumenDePapeletasResource.de(resumen)));
        }
        ModeloDeDocumento modelo =
                ModelosDeLosReportesDeSanciones.delResumenDePapeletas(titulo, cabecera, resumen);
        return ReportesDeSanciones.documento(documentos, modelo, formato, nombreBase);
    }

    /**
     * El agrupador del resumen de papeletas, con el mismo valor por omisión que su {@code GET}.
     *
     * <p>Por <b>año</b>, que es la primera columna que dibuja la pantalla (#398).
     */
    private static AgrupacionDelResumen agrupacionDe(@Nullable String agrupadoPor) {
        return agrupadoPor == null || agrupadoPor.isBlank()
                ? AgrupacionDelResumen.ANO
                : PeticionesDeSanciones.enumeradoDe(
                        AgrupacionDelResumen.class, agrupadoPor, "agrupadoPor");
    }

    private static void rechazarLosCriteriosDeMas(
            PeticionDeReporteDeTransito peticion, TipoDeReporteDeTransito reporte) {

        List<String> sobran = peticion.criteriosDeMas(reporte);
        if (sobran.isEmpty()) {
            return;
        }
        throw new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                "El reporte "
                        + reporte.name()
                        + " no usa "
                        + sobran
                        + ": mandarlos y no mirarlos daria una hoja correcta a una pregunta que no"
                        + " es la que se hizo. Los criterios de este reporte son "
                        + reporte.criteriosOrdenados());
    }

    private LocalDate hoy() {
        return LocalDate.now(reloj);
    }

    private static String texto(@Nullable String valor) {
        return valor == null ? "" : valor;
    }
}
