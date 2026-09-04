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
 * Relación impresa del CUIS vigente: {@code GET
 * /api/v1/infracciones/administrativas/codigos/reporte} (#43, RF-072).
 *
 * <p>Mismo catálogo que {@link CodigosCuisController}, con privilegio de {@link
 * Privilegio#IMPRESION}: emitir esta relación es un acto administrativo, no una simple lectura
 * (javadoc de {@link Privilegio}).
 */
@RestController
@RequestMapping(Api.RAIZ + "/infracciones/administrativas/codigos/reporte")
@RequiereAcceso(acceso = "adm_codigos_reporte", privilegio = Privilegio.IMPRESION)
public class ReporteCodigosAdministrativosController {

    private static final String ORDEN_POR_OMISION = "codigo";

    private final ConsultasDeSanciones consulta;
    private final Clock reloj;

    public ReporteCodigosAdministrativosController(ConsultasDeSanciones consulta, Clock reloj) {
        this.consulta = consulta;
        this.reloj = reloj;
    }

    @GetMapping
    public RespuestaPaginada<CodigoInfraccionResource> reporte(
            @RequestParam(required = false) @Nullable String codigo,
            @RequestParam(required = false) @Nullable String descripcionContiene,
            @RequestParam(required = false) @Nullable String fecha,
            ParametrosDePaginacion paginacion) {

        CriterioDeCodigoInfraccion criterio =
                new CriterioDeCodigoInfraccion(
                        Familia.ADMINISTRATIVA, codigo, descripcionContiene, fechaDe(fecha));

        return RespuestaPaginada.de(
                consulta.codigos(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                CodigoInfraccionResource::de);
    }

    /**
     * {@code fecha} es la forma explícita de pedir el catálogo tal como regía en el pasado. Sin
     * ella, el reporte es el vigente hoy: es lo que se imprime.
     */
    private LocalDate fechaDe(@Nullable String fecha) {
        return fecha == null || fecha.isBlank() ? LocalDate.now(reloj) : LocalDate.parse(fecha);
    }
}
