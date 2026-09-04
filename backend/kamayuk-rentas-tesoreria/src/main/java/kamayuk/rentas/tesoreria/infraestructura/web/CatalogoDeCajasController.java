package kamayuk.rentas.tesoreria.infraestructura.web;

import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.tesoreria.aplicacion.ConsultaDeCajas;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.RespuestaPaginada;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El catalogo de ventanillas de la municipalidad (#618, RF-080).
 *
 * <p>Va aparte de {@link CajaController} y no dentro de el porque aquel es {@code /tesoreria/caja}
 * —la ventanilla como <b>acto</b>: cobrar, y nada mas que {@code POST}— y esto es {@code
 * /tesoreria/cajas}, la lista de las que hay. Meter un {@code GET} de catalogo bajo la ruta del
 * cobro obligaria a inventar un segmento («/tesoreria/caja/catalogo») que no nombra a nadie.
 *
 * <h2>Quien puede leerlo, y por que son cinco opciones</h2>
 *
 * <p>El acceso propio es {@code caja_tributaria} con {@code LECTURA}, y las otras cuatro llegan por
 * {@code oTambien} (#548). No es generosidad: son <b>exactamente</b> las opciones del catalogo cuya
 * operacion exige el codigo de una caja y que sin esta lectura no lo pueden ofrecer —{@code
 * caja_tasas} y {@code cierre_caja} lo llevan en el cuerpo, {@code avance_recaudacion} y {@code
 * duplicado_recibo} como parametro de consulta—. Sin el mecanismo, la unica salida es otorgar
 * {@code caja_tributaria} entera en cada implantacion a quien solo tiene que cerrar su turno, y lo
 * que se olvida no avisa: el desplegable contesta 403 y la pantalla parece rota por otro motivo.
 *
 * <p>Cada una esta censada en {@code AccesosCompartidosTest}: la lista no puede crecer sin que el
 * diff lo diga.
 *
 * <h2>Sin ningun parametro que no sea la paginacion</h2>
 *
 * <p>Ni filtro de codigo —quien sabe el codigo no necesita el catalogo— ni de estado. Desde #539 un
 * parametro declarado que ningun argumento reclame se contesta con 422 nombrandolo, asi que
 * declarar aqui un filtro que no se lea no seria un silencio sino un rechazo.
 */
@RestController
@RequestMapping(Api.RAIZ + "/tesoreria/cajas")
public class CatalogoDeCajasController {

    /** La opcion del catalogo (NEG-03) de la que es la lectura. */
    static final String ACCESO = "caja_tributaria";

    /**
     * Por que se ordena cuando el cliente no lo dice: el codigo es como se rotula la ventanilla.
     */
    private static final String ORDEN_POR_OMISION = "codigo";

    private final ConsultaDeCajas catalogo;

    public CatalogoDeCajasController(ConsultaDeCajas catalogo) {
        this.catalogo = catalogo;
    }

    /**
     * Las ventanillas de la municipalidad, paginadas.
     *
     * <p>Solo las suyas: lo garantiza la politica RLS con el {@code SET LOCAL} que abre la
     * transaccion del caso de uso, no un {@code WHERE} que alguien tenga que recordar. Dos
     * municipalidades con una caja {@code C-01} cada una reciben cada una la suya.
     *
     * <p>Una municipalidad sin ninguna caja cargada devuelve una <b>pagina vacia</b> con {@code
     * totalElementos: 0}, nunca un 404: buscar y no encontrar no es un error, y aqui ademas «no hay
     * ninguna» es el estado normal de una instalacion recien implantada.
     */
    @GetMapping
    @RequiereAcceso(
            acceso = ACCESO,
            oTambien = {"caja_tasas", "cierre_caja", "avance_recaudacion", "duplicado_recibo"},
            privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<CajaEnListaResource> listar(ParametrosDePaginacion paginacion) {
        return RespuestaPaginada.de(
                catalogo.listar(paginacion.aPaginacion(ORDEN_POR_OMISION)),
                CajaEnListaResource::de);
    }
}
