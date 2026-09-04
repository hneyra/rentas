package kamayuk.rentas.rentas.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.rentas.aplicacion.ConsultasDeRentas;
import kamayuk.rentas.rentas.dominio.CriterioDeBeneficio;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Beneficios y exoneraciones: {@code GET /api/v1/rentas/beneficios} (NEG-03, RF-029).
 *
 * <p><b>Solo lectura.</b> El alta y el cese viven en {@code RegistrarBeneficio} y no se publican
 * todavia: el contrato no declara ningun {@code POST} ni {@code PUT} en esta ruta.
 */
@RestController
@RequestMapping(Api.RAIZ + "/rentas/beneficios")
@RequiereAcceso(acceso = "beneficios", privilegio = Privilegio.LECTURA)
public class BeneficioController {

    private static final String ORDEN_POR_OMISION = "vigencia_desde";
    private static final String ESTADO_VIGENTE = "VIGENTE";

    private final ConsultasDeRentas consulta;
    private final Clock reloj;

    public BeneficioController(ConsultasDeRentas consulta, Clock reloj) {
        this.consulta = consulta;
        this.reloj = reloj;
    }

    @GetMapping
    public RespuestaPaginada<BeneficioResource> buscar(
            @RequestParam(required = false) @Nullable String contribuyente,
            @RequestParam(required = false) @Nullable String tipo,
            @RequestParam(required = false) @Nullable String estado,
            ParametrosDePaginacion paginacion) {

        CriterioDeBeneficio criterio =
                new CriterioDeBeneficio(contribuyente, tipo, vigentesA(estado));

        return RespuestaPaginada.de(
                consulta.beneficios(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                BeneficioResource::de);
    }

    /**
     * El filtro «Estado» de la pantalla no trae una fecha, trae {@code VIGENTE} o nada: la fecha de
     * referencia es hoy, y sale del reloj inyectado, nunca de {@code LocalDate.now()} suelto.
     */
    private @Nullable LocalDate vigentesA(@Nullable String estado) {
        if (estado == null || !ESTADO_VIGENTE.equalsIgnoreCase(estado.strip())) {
            return null;
        }
        return LocalDate.now(reloj);
    }
}
