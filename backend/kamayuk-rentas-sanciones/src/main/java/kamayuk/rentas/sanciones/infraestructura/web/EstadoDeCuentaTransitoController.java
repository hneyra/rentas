package kamayuk.rentas.sanciones.infraestructura.web;

import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.sanciones.aplicacion.ConsultasDeSanciones;
import kamayuk.rentas.sanciones.dominio.CriterioDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estado de cuenta de infracciones: {@code GET /api/v1/transito/estado-cuenta} (RF-062).
 *
 * <p>Papeletas pendientes de pago de un conductor o de un vehículo, con su importe y su beneficio
 * —los dos ya guardados en la propia papeleta (#46)—. La situación de coactiva que describe el
 * contrato no sale de aquí todavía: {@code coactiva} es un contexto acotado vacío; cuando publique
 * su API, este controlador la incorpora sin cambiar la ruta.
 */
@RestController
@RequestMapping(Api.RAIZ + "/transito/estado-cuenta")
@RequiereAcceso(acceso = "transito_estado_cuenta", privilegio = Privilegio.LECTURA)
public class EstadoDeCuentaTransitoController {

    private static final String ORDEN_POR_OMISION = "fechaInfraccion";

    private final ConsultasDeSanciones consulta;

    public EstadoDeCuentaTransitoController(ConsultasDeSanciones consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    public RespuestaPaginada<PapeletaResource> buscar(
            @RequestParam(required = false) @Nullable String conductor,
            @RequestParam(required = false) @Nullable String placa,
            ParametrosDePaginacion paginacion) {

        CriterioDePapeleta criterio =
                new CriterioDePapeleta(
                        Familia.TRANSITO,
                        null,
                        placa,
                        conductor,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true);

        return RespuestaPaginada.de(
                consulta.papeletas(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                PapeletaResource::de);
    }
}
