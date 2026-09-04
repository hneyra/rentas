package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.sanciones.aplicacion.ConsultasDeSanciones;
import kamayuk.rentas.sanciones.dominio.CriterioDelProcedimiento;
import kamayuk.rentas.sanciones.dominio.FaseDelProcedimiento;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Infracciones administrativas: {@code GET /api/v1/infracciones/actas} (RF-071, #47, #397).
 *
 * <h2>Qué cambió en #397, y por qué no bastaba con añadir un parámetro</h2>
 *
 * <p>Hasta #397 esto servía {@code PapeletaResource} filtrado por {@code nroDeActa}, {@code
 * administrado} y {@code codigoCuis}. El contrato declara además un filtro «Estado» —el que dibuja
 * la pantalla: {@code PREVENTIVA}, {@code CONSTATADA}, {@code SANCIONADA}, {@code PAGADA}, {@code
 * COACTIVA}— y no había parámetro que lo recibiera; y la única columna de estado que el recurso
 * publicaba era {@code EstadoDePapeleta}, que es <b>otro vocabulario</b>: el de la deuda. Conectar
 * la pantalla así habría dejado un filtro que no filtra nada al lado de una columna que habla otro
 * idioma que el que ese mismo filtro promete, y RNF-080 no deja renombrar ninguno de los dos.
 *
 * <p>Ahora sirve {@link ProcedimientoSancionadorResource}, que publica <b>los dos</b> con sus
 * nombres —{@code fase} y {@code estadoDeLaDeuda}—, y acepta {@code estado} como la fase del
 * procedimiento. La fase no es una columna: se deriva de los hechos que ya están escritos (ver
 * {@link FaseDelProcedimiento}).
 *
 * <h2>La fase se resuelve a una fecha, y la fecha sale del reloj inyectado</h2>
 *
 * <p>Mismo reparto que {@code NotificacionesVencidasController}: el vencimiento del plazo de
 * subsanación se calcula contra el plazo de cada notificación, y la fecha de corte es la de hoy. No
 * se publica un parámetro para elegirla porque ninguna pantalla del manual dibuja ese filtro, y un
 * parámetro que solo tiene el backend se declararía en {@code DEL_BACKEND} del generador, no
 * inventado aquí (#312). La fecha usada viaja en cada fila ({@code faseAlDia}): una fase sin su
 * fecha es una fase que mañana es otra sin que nadie lo sepa.
 *
 * <h2>Solo lectura</h2>
 *
 * <p>El registro ({@code RegistrarPapeleta.registrarAdministrativa}) no se publica todavía —igual
 * que {@code papeletas} de tránsito (#46)—; el contrato no declara ningún {@code POST} en esta
 * ruta.
 */
@RestController
@RequestMapping(Api.RAIZ + "/infracciones/actas")
@RequiereAcceso(acceso = "infracciones_adm", privilegio = Privilegio.LECTURA)
public class InfraccionesAdministrativasController {

    private static final String ORDEN_POR_OMISION = "fechaInfraccion";

    private final ConsultasDeSanciones consulta;
    private final Clock reloj;

    public InfraccionesAdministrativasController(ConsultasDeSanciones consulta, Clock reloj) {
        this.consulta = consulta;
        this.reloj = reloj;
    }

    /**
     * @param administrado el documento del administrado —DNI o RUC—, igual que en las otras
     *     lecturas de papeletas administrativas (#47). El manual dibuja un campo de texto llamado
     *     «Administrado» y no dice si espera el nombre o el documento; se conserva el criterio con
     *     el que ya se buscaba, en vez de cambiarlo de significado en este issue
     * @param estado la fase del <b>procedimiento</b>. Un valor que no sea una de las cinco es 422 y
     *     dice cuáles admite; «Todos» no llega hasta aquí —no filtrar por fase es no mandar el
     *     parámetro, y eso lo resuelve la pantalla (ADR-0010)—
     */
    @GetMapping
    public RespuestaPaginada<ProcedimientoSancionadorResource> buscar(
            @RequestParam(required = false) @Nullable String nroDeActa,
            @RequestParam(required = false) @Nullable String administrado,
            @RequestParam(required = false) @Nullable String codigoCuis,
            @RequestParam(required = false) @Nullable String estado,
            ParametrosDePaginacion paginacion) {

        CriterioDelProcedimiento criterio =
                new CriterioDelProcedimiento(
                        nroDeActa,
                        administrado,
                        codigoCuis,
                        PeticionesDeSanciones.enumeradoSiViene(
                                FaseDelProcedimiento.class, estado, "estado"),
                        LocalDate.now(reloj));

        return RespuestaPaginada.de(
                consulta.procedimientos(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                ProcedimientoSancionadorResource::de);
    }
}
