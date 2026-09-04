package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.cuentacorriente.RecaudadoEnElLibro;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeResumenesDeSanciones;
import kamayuk.rentas.sanciones.aplicacion.ModelosDeLosReportesDeSanciones;
import kamayuk.rentas.sanciones.dominio.AgrupacionDelResumen;
import kamayuk.rentas.sanciones.dominio.CriterioDePadron;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.ResumenDePapeletas;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los cuatro resúmenes de tránsito: recaudación, papeletas pendientes y pagadas, por código de
 * infracción y por iniciales de placa (#53, RF-073).
 *
 * <h2>Los tres de papeletas cuentan actas; el de recaudación pregunta al libro</h2>
 *
 * <p>No es un matiz: es el tercer criterio de aceptación de #53. «Cuántas papeletas hay y por
 * cuánto» se contesta contando papeletas; «cuánto se recaudó» se contesta con la suma de los abonos
 * vivos del libro. Sumar los importes de las papeletas en estado {@code PAGADA} y llamarlo
 * recaudación daría una cifra parecida y distinta —no cuenta los intereses cobrados, cuenta entero
 * un pago parcial, y sigue contando un recibo anulado—, que es la peor clase de cifra: la que nadie
 * comprueba porque se parece a la buena.
 *
 * <h2>Sin rango, el ejercicio en curso</h2>
 *
 * <p>Un resumen sin fechas no existe (regla 9, RNF-075). Cuando la pantalla no las manda —la
 * primera vez que se abre—, se toma el año del reloj inyectado y el resumen <b>dice cuál</b>: el
 * {@code desde} y el {@code hasta} viajan en la respuesta y salen impresos en la hoja.
 *
 * <h2>Lo que no se filtra aquí, y por qué</h2>
 *
 * <p>La pantalla de recaudación ofrece filtrar por «caja». Una caja es de {@code tesoreria}, no de
 * {@code sanciones}: el libro no sabe en qué ventanilla se cobró. Ese corte lo sirve {@code GET
 * /tesoreria/recaudacion/por-area}, y aquí se rechaza con 422 en vez de devolver el total de todas
 * las cajas como si fuera el de una.
 *
 * <p>La de papeletas ofrece «cobranza: ordinaria o coactiva». No se filtra porque no hace falta: la
 * respuesta trae las dos, en columnas separadas, para cada línea. Filtrar obligaría a pedir el
 * resumen dos veces para ver lo que ya viene junto.
 */
@RestController
public class ResumenesDeTransitoController {

    private final ConsultaDeResumenesDeSanciones consulta;
    private final GeneradorDeDocumentos documentos;
    private final Clock reloj;

    public ResumenesDeTransitoController(
            ConsultaDeResumenesDeSanciones consulta,
            GeneradorDeDocumentos documentos,
            Clock reloj) {
        this.consulta = consulta;
        this.documentos = documentos;
        this.reloj = reloj;
    }

    // ---------- Resumen de recaudacion ----------

    @GetMapping(Api.RAIZ + "/transito/reportes/resumen-recaudacion")
    @RequiereAcceso(acceso = "transito_resumen_recaudacion", privilegio = Privilegio.LECTURA)
    public RecaudacionDeMultasResource resumenDeRecaudacion(
            @RequestParam(required = false) @Nullable String ano,
            @RequestParam(required = false) @Nullable String caja) {

        return RecaudacionDeMultasResource.de(recaudacion(ano, caja));
    }

    @GetMapping(value = Api.RAIZ + "/transito/reportes/resumen-recaudacion", params = "formato")
    @RequiereAcceso(acceso = "transito_resumen_recaudacion", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> resumenDeRecaudacionComoDocumento(
            @RequestParam(required = false) @Nullable String ano,
            @RequestParam(required = false) @Nullable String caja,
            @RequestParam String formato) {

        return ReportesDeSanciones.documento(
                documentos,
                ModelosDeLosReportesDeSanciones.deLaRecaudacion(
                        "Resumen de recaudacion por papeletas de transito",
                        List.of(Campo.de("Familia", Familia.TRANSITO.name())),
                        recaudacion(ano, caja)),
                formato,
                "resumen-de-recaudacion");
    }

    // ---------- Resumen de papeletas pendientes y pagadas ----------

    @GetMapping(Api.RAIZ + "/transito/reportes/resumen-papeletas")
    @RequiereAcceso(acceso = "transito_resumen_papeletas", privilegio = Privilegio.LECTURA)
    public ResumenDePapeletasResource resumenDePapeletas(
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String agrupadoPor) {

        return ResumenDePapeletasResource.de(resumenDePapeletasDe(desde, hasta, agrupadoPor));
    }

    @GetMapping(value = Api.RAIZ + "/transito/reportes/resumen-papeletas", params = "formato")
    @RequiereAcceso(acceso = "transito_resumen_papeletas", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> resumenDePapeletasComoDocumento(
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String agrupadoPor,
            @RequestParam String formato) {

        return ReportesDeSanciones.documento(
                documentos,
                ModelosDeLosReportesDeSanciones.delResumenDePapeletas(
                        "Resumen de papeletas pendientes y pagadas",
                        List.of(Campo.de("Familia", Familia.TRANSITO.name())),
                        resumenDePapeletasDe(desde, hasta, agrupadoPor)),
                formato,
                "resumen-de-papeletas");
    }

    // ---------- Resumen por codigo de infraccion ----------

    @GetMapping(Api.RAIZ + "/transito/reportes/resumen-por-codigo")
    @RequiereAcceso(acceso = "transito_resumen_codigo", privilegio = Privilegio.LECTURA)
    public ResumenDePapeletasResource resumenPorCodigo(
            @RequestParam(required = false) @Nullable String codigoDeInfraccion,
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String estado) {

        return ResumenDePapeletasResource.de(porCodigo(codigoDeInfraccion, desde, hasta, estado));
    }

    @GetMapping(value = Api.RAIZ + "/transito/reportes/resumen-por-codigo", params = "formato")
    @RequiereAcceso(acceso = "transito_resumen_codigo", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> resumenPorCodigoComoDocumento(
            @RequestParam(required = false) @Nullable String codigoDeInfraccion,
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String estado,
            @RequestParam String formato) {

        return ReportesDeSanciones.documento(
                documentos,
                ModelosDeLosReportesDeSanciones.delResumenDePapeletas(
                        "Resumen de papeletas por codigo de infraccion",
                        List.of(
                                Campo.de("Codigo de infraccion", texto(codigoDeInfraccion)),
                                Campo.de("Estado", texto(estado))),
                        porCodigo(codigoDeInfraccion, desde, hasta, estado)),
                formato,
                "resumen-por-codigo");
    }

    // ---------- Resumen por iniciales de placa ----------

    @GetMapping(Api.RAIZ + "/transito/reportes/resumen-por-placa")
    @RequiereAcceso(acceso = "transito_resumen_placa", privilegio = Privilegio.LECTURA)
    public ResumenDePapeletasResource resumenPorPlaca(
            @RequestParam(required = false) @Nullable String iniciales2Letras,
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String estado) {

        return ResumenDePapeletasResource.de(porPlaca(iniciales2Letras, desde, hasta, estado));
    }

    @GetMapping(value = Api.RAIZ + "/transito/reportes/resumen-por-placa", params = "formato")
    @RequiereAcceso(acceso = "transito_resumen_placa", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> resumenPorPlacaComoDocumento(
            @RequestParam(required = false) @Nullable String iniciales2Letras,
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String estado,
            @RequestParam String formato) {

        return ReportesDeSanciones.documento(
                documentos,
                ModelosDeLosReportesDeSanciones.delResumenDePapeletas(
                        "Resumen de papeletas por iniciales de placa",
                        List.of(
                                Campo.de("Iniciales", texto(iniciales2Letras)),
                                Campo.de("Estado", texto(estado))),
                        porPlaca(iniciales2Letras, desde, hasta, estado)),
                formato,
                "resumen-por-placa");
    }

    // ------------------------------------------------------------------

    private RecaudadoEnElLibro recaudacion(@Nullable String ano, @Nullable String caja) {
        if (PeticionesDeSanciones.vacioEsNulo(caja) != null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El libro no sabe en que caja se cobro: la caja es de tesoreria. Ese corte lo"
                            + " sirve GET /tesoreria/recaudacion/por-area; aqui la recaudacion sale"
                            + " agrupada por ejercicio, mes y tipo de cobranza");
        }
        LocalDate hoy = LocalDate.now(reloj);
        int ejercicio = CriteriosDeTransito.ejercicioDe(ano, hoy);
        return consulta.recaudacion(
                Familia.TRANSITO,
                LocalDate.of(ejercicio, 1, 1),
                LocalDate.of(ejercicio, 12, 31),
                hoy);
    }

    /**
     * El resumen de papeletas pendientes y pagadas, agrupado.
     *
     * <p>Sin {@code agrupadoPor}, por <b>año</b> y no por estado (#398): la pantalla dibuja «Año»
     * como primera columna, y el agrupador por omisión tiene que ser el que la llena. Con {@code
     * ESTADO} —lo que este endpoint hacía— la columna se rellenaba con nombres de estado bajo un
     * rótulo que dice «Año», que es lo que RNF-080 no permite.
     */
    private ResumenDePapeletas resumenDePapeletasDe(
            @Nullable String desde, @Nullable String hasta, @Nullable String agrupadoPor) {

        AgrupacionDelResumen agrupacion =
                agrupadoPor == null || agrupadoPor.isBlank()
                        ? AgrupacionDelResumen.ANO
                        : PeticionesDeSanciones.enumeradoDe(
                                AgrupacionDelResumen.class, agrupadoPor, "agrupadoPor");

        return consulta.resumir(criterio(desde, hasta, null, null, null), agrupacion, hoy());
    }

    private ResumenDePapeletas porCodigo(
            @Nullable String codigo,
            @Nullable String desde,
            @Nullable String hasta,
            @Nullable String estado) {

        return consulta.resumir(
                criterio(desde, hasta, estado, PeticionesDeSanciones.vacioEsNulo(codigo), null),
                AgrupacionDelResumen.CODIGO,
                hoy());
    }

    private ResumenDePapeletas porPlaca(
            @Nullable String iniciales,
            @Nullable String desde,
            @Nullable String hasta,
            @Nullable String estado) {

        return consulta.resumir(
                criterio(desde, hasta, estado, null, PeticionesDeSanciones.vacioEsNulo(iniciales)),
                AgrupacionDelResumen.PLACA,
                hoy());
    }

    /**
     * El criterio de los tres resúmenes, con el rango por omisión (ver {@link
     * CriteriosDeTransito}).
     */
    private CriterioDePadron criterio(
            @Nullable String desde,
            @Nullable String hasta,
            @Nullable String estado,
            @Nullable String codigo,
            @Nullable String prefijoDePlaca) {

        return CriteriosDeTransito.delResumen(hoy(), desde, hasta, estado, codigo, prefijoDePlaca);
    }

    private LocalDate hoy() {
        return LocalDate.now(reloj);
    }

    private static String texto(@Nullable String valor) {
        return valor == null ? "" : valor;
    }
}
