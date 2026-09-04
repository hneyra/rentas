package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
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
import kamayuk.rentas.sanciones.dominio.CriterioDePadron;
import kamayuk.rentas.sanciones.dominio.CriterioDelPadronDeNotificaciones;
import kamayuk.rentas.sanciones.dominio.EstadoDeNotificacion;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.NotificacionDelPadron;
import kamayuk.rentas.sanciones.dominio.ResumenDePapeletas;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los tres reportes de infracciones administrativas: el padrón de notificaciones, el resumen de
 * recaudación y el emisor que la pantalla {@code adm_reportes} presenta (#53, RF-074).
 *
 * <h2>El emisor no duplica las consultas: las llama</h2>
 *
 * <p>{@code POST /infracciones/administrativas/reportes} es la pantalla «emisor de reportes» del
 * manual: una sola opción que emite varios. Detrás no hay ninguna consulta nueva —llama a los
 * mismos {@code ConsultaDe…} que los {@code GET}—, porque dos caminos para la misma cuenta son dos
 * oportunidades de divergir y el que se mira menos es el que se queda mal.
 *
 * <h2>Recaudación, del libro; papeletas, de las actas</h2>
 *
 * <p>La misma división que en tránsito, y por el mismo motivo (AC 3 de #53): lo recaudado es
 * exactamente la suma de los abonos vivos, no la suma de los importes de las papeletas pagadas.
 */
@RestController
public class ReportesAdministrativosController {

    private final ConsultaDePadronesDeSanciones padrones;
    private final ConsultaDeResumenesDeSanciones resumenes;
    private final GeneradorDeDocumentos documentos;
    private final Clock reloj;

    public ReportesAdministrativosController(
            ConsultaDePadronesDeSanciones padrones,
            ConsultaDeResumenesDeSanciones resumenes,
            GeneradorDeDocumentos documentos,
            Clock reloj) {
        this.padrones = padrones;
        this.resumenes = resumenes;
        this.documentos = documentos;
        this.reloj = reloj;
    }

    // ---------- Padron de notificaciones ----------

    @GetMapping(Api.RAIZ + "/infracciones/administrativas/reportes/padron-notificaciones")
    @RequiereAcceso(acceso = "adm_padron_notificaciones", privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<NotificacionDelPadronResource> padronDeNotificaciones(
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String estado,
            ParametrosDePaginacion paginacion) {

        return RespuestaPaginada.de(
                paginaDeNotificaciones(desde, hasta, estado, paginacion.aPaginacion("fecha")),
                NotificacionDelPadronResource::de);
    }

    @GetMapping(
            value = Api.RAIZ + "/infracciones/administrativas/reportes/padron-notificaciones",
            params = "formato")
    @RequiereAcceso(acceso = "adm_padron_notificaciones", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> padronDeNotificacionesComoDocumento(
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String estado,
            @RequestParam String formato,
            ParametrosDePaginacion paginacion) {

        return ReportesDeSanciones.documento(
                documentos,
                modeloDelPadron(
                        paginaDeNotificaciones(
                                desde, hasta, estado, paginacion.aPaginacion("fecha")),
                        desde,
                        hasta,
                        estado),
                formato,
                "padron-de-notificaciones");
    }

    // ---------- Resumen de recaudacion ----------

    @GetMapping(Api.RAIZ + "/infracciones/administrativas/reportes/resumen-recaudacion")
    @RequiereAcceso(acceso = "adm_resumen_recaudacion", privilegio = Privilegio.LECTURA)
    public RecaudacionDeMultasResource resumenDeRecaudacion(
            @RequestParam(required = false) @Nullable String ano) {
        return RecaudacionDeMultasResource.de(recaudacion(ano));
    }

    @GetMapping(
            value = Api.RAIZ + "/infracciones/administrativas/reportes/resumen-recaudacion",
            params = "formato")
    @RequiereAcceso(acceso = "adm_resumen_recaudacion", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> resumenDeRecaudacionComoDocumento(
            @RequestParam(required = false) @Nullable String ano, @RequestParam String formato) {

        return ReportesDeSanciones.documento(
                documentos,
                ModelosDeLosReportesDeSanciones.deLaRecaudacion(
                        "Resumen de recaudacion por multas administrativas",
                        List.of(Campo.de("Familia", Familia.ADMINISTRATIVA.name())),
                        recaudacion(ano)),
                formato,
                "resumen-de-recaudacion-administrativas");
    }

    // ---------- El emisor de reportes ----------

    @PostMapping(Api.RAIZ + "/infracciones/administrativas/reportes")
    @RequiereAcceso(acceso = "adm_reportes", privilegio = Privilegio.IMPRESION)
    public ResponseEntity<?> emitir(@RequestBody PeticionDeReporteAdministrativo peticion) {
        TipoDeReporteAdministrativo reporte =
                PeticionesDeSanciones.enumeradoDe(
                        TipoDeReporteAdministrativo.class, peticion.reporte(), "reporte");
        boolean comoDocumento = ReportesDeSanciones.pideDocumento(peticion.formato());
        String formato = comoDocumento ? peticion.formato() : null;

        return switch (reporte) {
            case PADRON_NOTIFICACIONES -> {
                Pagina<NotificacionDelPadron> pagina =
                        paginaDeNotificaciones(
                                peticion.desde(),
                                peticion.hasta(),
                                peticion.estado(),
                                Paginacion.de(0, Paginacion.TAMANO_MAXIMO, "fecha"));
                yield comoDocumento
                        ? ReportesDeSanciones.documento(
                                documentos,
                                modeloDelPadron(
                                        pagina,
                                        peticion.desde(),
                                        peticion.hasta(),
                                        peticion.estado()),
                                java.util.Objects.requireNonNull(formato),
                                "padron-de-notificaciones")
                        : ResponseEntity.ok(
                                ReporteAdministrativoResource.delPadron(
                                        RespuestaPaginada.de(
                                                pagina, NotificacionDelPadronResource::de)));
            }
            case RESUMEN_PAPELETAS -> {
                ResumenDePapeletas resumen =
                        resumen(
                                peticion.desde(),
                                peticion.hasta(),
                                peticion.estado(),
                                peticion.agrupadoPor());
                yield comoDocumento
                        ? ReportesDeSanciones.documento(
                                documentos,
                                ModelosDeLosReportesDeSanciones.delResumenDePapeletas(
                                        "Resumen de papeletas administrativas",
                                        List.of(Campo.de("Familia", Familia.ADMINISTRATIVA.name())),
                                        resumen),
                                java.util.Objects.requireNonNull(formato),
                                "resumen-de-papeletas-administrativas")
                        : ResponseEntity.ok(
                                ReporteAdministrativoResource.delResumen(
                                        ResumenDePapeletasResource.de(resumen)));
            }
            case RESUMEN_RECAUDACION -> {
                RecaudadoEnElLibro recaudado = recaudacionEntre(peticion.desde(), peticion.hasta());
                yield comoDocumento
                        ? ReportesDeSanciones.documento(
                                documentos,
                                ModelosDeLosReportesDeSanciones.deLaRecaudacion(
                                        "Resumen de recaudacion por multas administrativas",
                                        List.of(Campo.de("Familia", Familia.ADMINISTRATIVA.name())),
                                        recaudado),
                                java.util.Objects.requireNonNull(formato),
                                "resumen-de-recaudacion-administrativas")
                        : ResponseEntity.ok(
                                ReporteAdministrativoResource.deLaRecaudacion(
                                        RecaudacionDeMultasResource.de(recaudado)));
            }
        };
    }

    // ------------------------------------------------------------------

    private Pagina<NotificacionDelPadron> paginaDeNotificaciones(
            @Nullable String desde,
            @Nullable String hasta,
            @Nullable String estado,
            Paginacion paginacion) {

        try {
            CriterioDelPadronDeNotificaciones criterio =
                    new CriterioDelPadronDeNotificaciones(
                            inicio(desde),
                            fin(hasta),
                            null,
                            PeticionesDeSanciones.enumeradoSiViene(
                                    EstadoDeNotificacion.class, estado, "estado"),
                            null);
            return padrones.notificaciones(criterio, paginacion);
        } catch (IllegalArgumentException invalido) {
            throw PeticionesDeSanciones.invalido(invalido);
        }
    }

    private ResumenDePapeletas resumen(
            @Nullable String desde,
            @Nullable String hasta,
            @Nullable String estado,
            @Nullable String agrupadoPor) {

        AgrupacionDelResumen agrupacion =
                agrupadoPor == null || agrupadoPor.isBlank()
                        ? AgrupacionDelResumen.ESTADO
                        : PeticionesDeSanciones.enumeradoDe(
                                AgrupacionDelResumen.class, agrupadoPor, "agrupadoPor");
        try {
            CriterioDePadron criterio =
                    new CriterioDePadron(
                            Familia.ADMINISTRATIVA,
                            inicio(desde),
                            fin(hasta),
                            PeticionesDeSanciones.enumeradoSiViene(
                                    EstadoDePapeleta.class, estado, "estado"),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            false);
            return resumenes.resumir(criterio, agrupacion, LocalDate.now(reloj));
        } catch (IllegalArgumentException invalido) {
            throw PeticionesDeSanciones.invalido(invalido);
        }
    }

    private RecaudadoEnElLibro recaudacion(@Nullable String ano) {
        LocalDate hoy = LocalDate.now(reloj);
        int ejercicio = hoy.getYear();
        if (ano != null && !ano.isBlank()) {
            try {
                ejercicio = Integer.parseInt(ano.strip());
            } catch (NumberFormatException noEsUnNumero) {
                throw new kamayuk.rentas.web.ProblemaDeNegocio(
                        kamayuk.rentas.web.CodigoDeError.VALIDACION,
                        "El ano va como un entero de cuatro cifras: " + ano);
            }
        }
        return resumenes.recaudacion(
                Familia.ADMINISTRATIVA,
                LocalDate.of(ejercicio, 1, 1),
                LocalDate.of(ejercicio, 12, 31),
                hoy);
    }

    private RecaudadoEnElLibro recaudacionEntre(@Nullable String desde, @Nullable String hasta) {
        return resumenes.recaudacion(
                Familia.ADMINISTRATIVA, inicio(desde), fin(hasta), LocalDate.now(reloj));
    }

    private ModeloDeDocumento modeloDelPadron(
            Pagina<NotificacionDelPadron> pagina,
            @Nullable String desde,
            @Nullable String hasta,
            @Nullable String estado) {

        return ModelosDeLosReportesDeSanciones.delPadronDeNotificaciones(
                List.of(
                        Campo.de("Desde", inicio(desde).toString()),
                        Campo.de("Hasta", fin(hasta).toString()),
                        Campo.de("Estado", estado == null ? "" : estado)),
                pagina,
                LocalDate.now(reloj));
    }

    /** Sin rango, el ejercicio en curso; y el reporte dice cuál, porque el rango viaja con él. */
    private LocalDate inicio(@Nullable String desde) {
        return desde == null || desde.isBlank()
                ? LocalDate.of(LocalDate.now(reloj).getYear(), 1, 1)
                : PeticionesDeSanciones.fechaDe(desde, "desde");
    }

    private LocalDate fin(@Nullable String hasta) {
        return hasta == null || hasta.isBlank()
                ? LocalDate.of(LocalDate.now(reloj).getYear(), 12, 31)
                : PeticionesDeSanciones.fechaDe(hasta, "hasta");
    }
}
