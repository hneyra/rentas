package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.sanciones.aplicacion.ConsultasDeSanciones;
import kamayuk.rentas.sanciones.dominio.CriterioDePapeleta;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
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
 * Papeletas de tránsito: {@code GET /api/v1/transito/papeletas} (RF-060).
 *
 * <p>Solo lectura: el registro ({@code RegistrarPapeleta}) no se publica todavía —igual que {@code
 * arbitrios} en rentas (#31)—; el contrato no declara ningún {@code POST} en esta ruta.
 */
@RestController
@RequestMapping(Api.RAIZ + "/transito/papeletas")
@RequiereAcceso(acceso = "papeletas", privilegio = Privilegio.LECTURA)
public class PapeletasController {

    private static final String ORDEN_POR_OMISION = "fechaInfraccion";

    private final ConsultasDeSanciones consulta;

    public PapeletasController(ConsultasDeSanciones consulta) {
        this.consulta = consulta;
    }

    @GetMapping
    public RespuestaPaginada<PapeletaResource> buscar(
            @RequestParam(required = false) @Nullable String nroPapeleta,
            @RequestParam(required = false) @Nullable String placa,
            @RequestParam(required = false) @Nullable String documentoDelInfractor,
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String estado,
            ParametrosDePaginacion paginacion) {

        CriterioDePapeleta criterio =
                new CriterioDePapeleta(
                        Familia.TRANSITO,
                        nroPapeleta,
                        placa,
                        documentoDelInfractor,
                        null,
                        null,
                        fechaDe(desde, "desde"),
                        fechaDe(hasta, "hasta"),
                        estadoDe(estado),
                        null,
                        false);

        return RespuestaPaginada.de(
                consulta.papeletas(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                PapeletaResource::de);
    }

    private static @Nullable LocalDate fechaDe(@Nullable String texto, String campo) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' va en formato AAAA-MM-DD: '" + texto + "'");
        }
    }

    private static @Nullable EstadoDePapeleta estadoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return EstadoDePapeleta.valueOf(texto.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Estado de papeleta desconocido: '" + texto + "'");
        }
    }
}
