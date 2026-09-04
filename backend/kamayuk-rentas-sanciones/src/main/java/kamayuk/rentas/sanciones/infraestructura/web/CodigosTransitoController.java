package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.sanciones.aplicacion.ConsultasDeSanciones;
import kamayuk.rentas.sanciones.dominio.CriterioDeCodigoInfraccion;
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
 * Tabla de códigos de infracción de tránsito: {@code GET /api/v1/transito/codigos} (#43, RF-063,
 * NEG-03).
 *
 * <p>Sin {@code fecha}, trae el catálogo vigente hoy; con {@code fecha}, el vigente entonces — una
 * papeleta se explica con el código vigente el día de la infracción, no con el de hoy (regla 9).
 */
@RestController
@RequestMapping(Api.RAIZ + "/transito/codigos")
@RequiereAcceso(acceso = "codigos_transito", privilegio = Privilegio.LECTURA)
public class CodigosTransitoController {

    private static final String ORDEN_POR_OMISION = "codigo";

    private final ConsultasDeSanciones consulta;
    private final Clock reloj;

    public CodigosTransitoController(ConsultasDeSanciones consulta, Clock reloj) {
        this.consulta = consulta;
        this.reloj = reloj;
    }

    @GetMapping
    public RespuestaPaginada<CodigoInfraccionResource> buscar(
            @RequestParam(required = false) @Nullable String codigo,
            @RequestParam(required = false) @Nullable String textoDeLaInfraccion,
            @RequestParam(required = false) @Nullable String fecha,
            ParametrosDePaginacion paginacion) {

        CriterioDeCodigoInfraccion criterio =
                new CriterioDeCodigoInfraccion(
                        Familia.TRANSITO, codigo, textoDeLaInfraccion, fechaDe(fecha));

        return RespuestaPaginada.de(
                consulta.codigos(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                CodigoInfraccionResource::de);
    }

    private LocalDate fechaDe(@Nullable String fecha) {
        return fecha == null || fecha.isBlank() ? LocalDate.now(reloj) : LocalDate.parse(fecha);
    }
}
