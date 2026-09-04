package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.sanciones.aplicacion.ConsultasDeSanciones;
import kamayuk.rentas.sanciones.dominio.CriterioDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notificaciones —papeletas administrativas, pese al nombre del contrato— por contribuyente: {@code
 * GET /api/v1/infracciones/administrativas/reportes/por-contribuyente} (RF-074, #47).
 *
 * <p>El resumen dice "papeletas administrativas ... con el estado de la multa y los datos de su
 * pago": es la misma tabla {@code papeleta} que {@link InfraccionesAdministrativasController}, no
 * {@code notificacion_administrativa}. {@code agrupadoPor} —año y mes— es una agrupación de
 * presentación que esta lista no calcula; el listado ya trae la fecha de cada infracción, con la
 * que el consumidor agrupa.
 */
@RestController
@RequestMapping(Api.RAIZ + "/infracciones/administrativas/reportes/por-contribuyente")
@RequiereAcceso(acceso = "adm_notificaciones_contribuyente", privilegio = Privilegio.LECTURA)
public class NotificacionesPorContribuyenteController {

    private static final String ORDEN_POR_OMISION = "fechaInfraccion";

    private final ConsultasDeSanciones consulta;

    public NotificacionesPorContribuyenteController(ConsultasDeSanciones consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    public RespuestaPaginada<PapeletaResource> buscar(
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String ano,
            @RequestParam(required = false) @Nullable String estadoDeDeuda,
            ParametrosDePaginacion paginacion) {

        LocalDate desde = null;
        LocalDate hasta = null;
        if (ano != null && !ano.isBlank()) {
            int anio = anioDe(ano);
            desde = LocalDate.of(anio, 1, 1);
            hasta = LocalDate.of(anio, 12, 31);
        }

        CriterioDePapeleta criterio =
                new CriterioDePapeleta(
                        Familia.ADMINISTRATIVA,
                        null,
                        null,
                        null,
                        codContribuyente,
                        null,
                        desde,
                        hasta,
                        null,
                        null,
                        estadoDeDeuda != null && !estadoDeDeuda.isBlank());

        return RespuestaPaginada.de(
                consulta.papeletas(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                PapeletaResource::de);
    }

    private static int anioDe(String texto) {
        try {
            return Integer.parseInt(texto.strip());
        } catch (NumberFormatException malFormado) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El campo 'ano' va en digitos: '" + texto + "'");
        }
    }
}
