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
 * Búsqueda avanzada de papeletas: {@code GET /api/v1/transito/papeletas/busqueda} (RF-061).
 *
 * <p>Distinta ruta que {@link PapeletasController} porque el contrato la declara como una opción de
 * menú aparte, con sus propios filtros. {@code estadoDeDeuda} se traduce a {@link
 * CriterioDePapeleta#soloPendientes()}: es lo único de "estado de coactiva, último pago y usuario
 * que registró" que ya se puede resolver sin depender de {@code coactiva} —contexto todavía vacío—
 * ni de una consulta cruzada con {@code tesoreria}. {@code usuarioRegistro} sí queda: es un dato
 * propio de la papeleta.
 */
@RestController
@RequestMapping(Api.RAIZ + "/transito/papeletas/busqueda")
@RequiereAcceso(acceso = "transito_busqueda", privilegio = Privilegio.LECTURA)
public class BusquedaDePapeletasController {

    private static final String ORDEN_POR_OMISION = "fechaInfraccion";

    private final ConsultasDeSanciones consulta;

    public BusquedaDePapeletasController(ConsultasDeSanciones consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    public RespuestaPaginada<PapeletaResource> buscar(
            @RequestParam(required = false) @Nullable String papeleta,
            @RequestParam(required = false) @Nullable String nPlaca,
            @RequestParam(required = false) @Nullable String estadoDeDeuda,
            @RequestParam(required = false) @Nullable String ingresadoPor,
            ParametrosDePaginacion paginacion) {

        CriterioDePapeleta criterio =
                new CriterioDePapeleta(
                        Familia.TRANSITO,
                        papeleta,
                        nPlaca,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        ingresadoPor,
                        estadoDeDeuda != null && !estadoDeDeuda.isBlank());

        return RespuestaPaginada.de(
                consulta.papeletas(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                PapeletaResource::de);
    }
}
