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
 * Cuadro único de infracciones y sanciones administrativas (CUIS): {@code GET
 * /api/v1/infracciones/cuis} (#43, RF-072, NEG-03).
 *
 * <p>Mismo modelo que {@link CodigosTransitoController}; lo único que cambia es la familia que
 * filtra ({@link Familia#ADMINISTRATIVA}) y el privilegio de la opción.
 */
@RestController
@RequestMapping(Api.RAIZ + "/infracciones/cuis")
@RequiereAcceso(acceso = "codigos_cuis", privilegio = Privilegio.LECTURA)
public class CodigosCuisController {

    private static final String ORDEN_POR_OMISION = "codigo";

    private final ConsultasDeSanciones consulta;
    private final Clock reloj;

    public CodigosCuisController(ConsultasDeSanciones consulta, Clock reloj) {
        this.consulta = consulta;
        this.reloj = reloj;
    }

    @GetMapping
    public RespuestaPaginada<CodigoInfraccionResource> buscar(
            @RequestParam(required = false) @Nullable String codigo,
            @RequestParam(required = false) @Nullable String materia,
            @RequestParam(required = false) @Nullable String fecha,
            ParametrosDePaginacion paginacion) {

        CriterioDeCodigoInfraccion criterio =
                new CriterioDeCodigoInfraccion(
                        Familia.ADMINISTRATIVA, codigo, materia, fechaDe(fecha));

        return RespuestaPaginada.de(
                consulta.codigos(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                CodigoInfraccionResource::de);
    }

    private LocalDate fechaDe(@Nullable String fecha) {
        return fecha == null || fecha.isBlank() ? LocalDate.now(reloj) : LocalDate.parse(fecha);
    }
}
