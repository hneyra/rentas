package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.sanciones.aplicacion.ConsultasDeSanciones;
import kamayuk.rentas.sanciones.dominio.CriterioDeNotificacion;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notificaciones vencidas: {@code GET /api/v1/infracciones/administrativas/reportes/vencidas}
 * (RF-074, #47).
 *
 * <p>"Notificaciones cuyo plazo de subsanación venció sin acreditarse el cumplimiento": el corte se
 * calcula contra el <b>plazo parametrizado</b> de cada notificación —{@code fecha + plazoDias}—,
 * nunca uno fijo en el código (#47 AC3). Sin {@code vencidasAl}, se pide al reloj inyectado.
 *
 * <p>El contrato trae {@code fiscalizador}, que no es columna de {@code
 * notificacion_administrativa}: se traduce al usuario que registró la fila, lo más cerca que hay.
 */
@RestController
@RequestMapping(Api.RAIZ + "/infracciones/administrativas/reportes/vencidas")
@RequiereAcceso(acceso = "adm_notificaciones_vencidas", privilegio = Privilegio.LECTURA)
public class NotificacionesVencidasController {

    private static final String ORDEN_POR_OMISION = "fecha";

    private final ConsultasDeSanciones consulta;
    private final Clock reloj;

    public NotificacionesVencidasController(ConsultasDeSanciones consulta, Clock reloj) {
        this.consulta = consulta;
        this.reloj = reloj;
    }

    @GetMapping
    public RespuestaPaginada<NotificacionAdministrativaResource> buscar(
            @RequestParam(required = false) @Nullable String vencidasAl,
            @RequestParam(required = false) @Nullable String fiscalizador,
            @RequestParam(required = false) @Nullable String infraccion,
            @RequestParam(required = false) @Nullable String conPapeleta,
            ParametrosDePaginacion paginacion) {

        CriterioDeNotificacion criterio =
                new CriterioDeNotificacion(
                        null,
                        null,
                        fechaDe(vencidasAl),
                        fiscalizador,
                        infraccion,
                        conPapeletaDe(conPapeleta));

        return RespuestaPaginada.de(
                consulta.notificacionesVencidas(
                        criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                NotificacionAdministrativaResource::de);
    }

    /**
     * La fecha de corte, o hoy si no viene.
     *
     * <p>Por {@link PeticionesDeSanciones#fechaSiViene} y no con {@code LocalDate.parse} suelto
     * (#422): {@code DateTimeParseException} no es una {@code IllegalArgumentException}, y una
     * fecha tecleada {@code 14/08/2026} salia como un 500 con incidencia ERROR en vez del 422 que
     * nombra el parametro.
     */
    private LocalDate fechaDe(@Nullable String texto) {
        LocalDate fecha = PeticionesDeSanciones.fechaSiViene(texto, "vencidasAl");
        return fecha == null ? LocalDate.now(reloj) : fecha;
    }

    private static @Nullable Boolean conPapeletaDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(texto.strip());
    }
}
