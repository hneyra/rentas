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
 * Estado de cuenta de papeleta administrativa: {@code GET
 * /api/v1/infracciones/administrativas/estado-cuenta} (RF-074, #47).
 *
 * <p>Mismo patrón que {@code EstadoDeCuentaTransitoController} (#46): papeletas pendientes de pago,
 * con su importe y su beneficio ya guardados en la propia fila. El reajuste, el interés y los
 * gastos que describe el contrato no salen de aquí: dependen de {@code tesoreria}, que todavía no
 * publica su cálculo de deuda actualizada.
 */
@RestController
@RequestMapping(Api.RAIZ + "/infracciones/administrativas/estado-cuenta")
@RequiereAcceso(acceso = "adm_estado_cuenta", privilegio = Privilegio.LECTURA)
public class EstadoDeCuentaAdministrativoController {

    private static final String ORDEN_POR_OMISION = "fechaInfraccion";

    private final ConsultasDeSanciones consulta;

    public EstadoDeCuentaAdministrativoController(ConsultasDeSanciones consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    public RespuestaPaginada<PapeletaResource> buscar(
            @RequestParam(required = false) @Nullable String papeleta,
            @RequestParam(required = false) @Nullable String codContribuyente,
            ParametrosDePaginacion paginacion) {

        CriterioDePapeleta criterio =
                new CriterioDePapeleta(
                        Familia.ADMINISTRATIVA,
                        papeleta,
                        null,
                        null,
                        codContribuyente,
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
