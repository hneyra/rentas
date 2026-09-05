package kamayuk.rentas.nucleo.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.catastro.TitularDelPredio;
import kamayuk.rentas.catastro.TitularesDelPredio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code predios_rentas}: {@code GET /api/v1/rentas/predios} (#395).
 *
 * <p>El padron predial visto desde rentas: los predios de un contribuyente con lo que hace falta
 * para elegir uno y determinarlo —su identificador interno, su codigo, donde esta, para que se usa,
 * que parte es suya y en que condicion—.
 *
 * <p><b>No es una segunda copia de {@code consulta_predios}.</b> Aquella (#25) responde «cuanto
 * debe cada predio» y por eso trae la deuda; esta responde «que predios entran en la base» y por
 * eso trae el uso, el sector, el area y la condicion de la titularidad, que es lo que la pantalla
 * de Rentas · Registro dibuja. Las dos leen los mismos puertos de catastro, ninguna copia sus
 * tablas.
 *
 * <p>Los cuatro filtros que la pantalla dibuja se resuelven de verdad y en memoria, sobre la lista
 * del contribuyente: {@code codigoPredial} por prefijo del codigo de referencia catastral, {@code
 * sector} y {@code condicion} por igualdad sin distinguir mayusculas. No se aceptan filtros que no
 * filtren —el criterio de {@code AltasBajasController} sirve cuando la busqueda no existe; aqui la
 * lista ya esta en memoria y no filtrar seria una respuesta equivocada, no una incompleta—.
 *
 * <p>{@code contribuyente} y {@code codContribuyente} son el mismo filtro con dos nombres: el
 * contrato declara los dos porque el prototipo dibuja «Cod. Contribuyente» y el resto de las
 * lecturas usa {@code contribuyente}. Uno de los dos es <b>obligatorio</b>.
 *
 * <h2>Tres cosas distintas que se decian igual (#541)</h2>
 *
 * <p>Hasta #541 esta operacion respondia {@code 200} con la pagina vacia en <b>dos</b> casos que no
 * son el mismo, y ninguno de los dos es «este contribuyente no tiene predios»:
 *
 * <ul>
 *   <li><b>sin ningun contribuyente</b> —la peticion no dice a quien listar—: ahora {@code 422}
 *       nombrando el parametro, como su hermana {@code GET /rentas/vehiculos}, que ya lo hacia. Un
 *       200 con cero filas sobre 14 422 predios se lee como una respuesta, y no lo es;
 *   <li><b>un codigo que no esta en el padron</b> —tecleado mal, o de otra municipalidad—: ahora
 *       {@code 404} nombrando el codigo. Es la pregunta que se hace en ventanilla, y las dos
 *       respuestas eran identicas byte a byte.
 * </ul>
 *
 * <p>Lo que sigue siendo {@code 200} con cero filas es lo unico que de verdad lo es: un
 * contribuyente del padron que no tiene ningun predio.
 */
@RestController
@RequestMapping(Api.RAIZ + "/rentas/predios")
@RequiereAcceso(acceso = "predios_rentas", privilegio = Privilegio.LECTURA)
public class PrediosDeRentasController {

    private static final String ORDEN_POR_OMISION = "codigoReferenciaCatastral";

    private final PrediosDelContribuyente predios;
    private final LectorDeCaracteristicas caracteristicas;
    private final TitularesDelPredio titulares;
    private final DirectorioDeContribuyentes directorio;
    private final Clock reloj;

    public PrediosDeRentasController(
            PrediosDelContribuyente predios,
            LectorDeCaracteristicas caracteristicas,
            TitularesDelPredio titulares,
            DirectorioDeContribuyentes directorio,
            Clock reloj) {
        this.predios = predios;
        this.caracteristicas = caracteristicas;
        this.titulares = titulares;
        this.directorio = directorio;
        this.reloj = reloj;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public RespuestaPaginada<PredioDeRentasResource> listar(
            @RequestParam(required = false) @Nullable String contribuyente,
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String codigoPredial,
            @RequestParam(required = false) @Nullable String sector,
            @RequestParam(required = false) @Nullable String condicion,
            ParametrosDePaginacion parametros) {

        Paginacion paginacion = parametros.aPaginacion(ORDEN_POR_OMISION);
        String codigo = primeroNoVacio(codContribuyente, contribuyente);
        if (codigo == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que decir de quien son los predios: falta «codContribuyente» (o su otro"
                            + " nombre, «contribuyente»)");
        }
        Optional<ResumenDeContribuyente> encontrado =
                directorio.porCodigo(codigo.toUpperCase(Locale.ROOT));
        if (encontrado.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO,
                    "En el padron de esta municipalidad no hay ningun contribuyente con codigo '"
                            + codigo
                            + "'");
        }

        // La fecha de corte sale del reloj inyectado y no de la peticion: el contrato de esta
        // operacion no declara ninguna —la pantalla no dibuja un campo de fecha— y aceptar un
        // parametro que ningun cliente sabe mandar seria publicar una entrada invisible (#312).
        LocalDate fechaDeCorte = LocalDate.now(reloj);
        long contribuyenteId = encontrado.get().id();
        List<PredioDelContribuyente> suyos =
                new ArrayList<>(predios.de(contribuyenteId, fechaDeCorte));
        suyos.sort(Comparator.comparing(PredioDelContribuyente::codigoReferenciaCatastral));

        List<PredioDeRentasResource> todos = new ArrayList<>();
        for (PredioDelContribuyente predio : suyos) {
            CaracteristicasDelPredio rasgos =
                    caracteristicas.de(predio.predioId(), fechaDeCorte).orElse(null);
            PredioDeRentasResource fila =
                    PredioDeRentasResource.de(
                            predio,
                            rasgos,
                            condicionDe(predio.predioId(), contribuyenteId, fechaDeCorte));
            if (pasa(fila, codigoPredial, sector, condicion)) {
                todos.add(fila);
            }
        }

        int desde = Math.min(paginacion.desplazamiento(), todos.size());
        int hasta = Math.min(desde + paginacion.tamano(), todos.size());
        return RespuestaPaginada.de(
                Pagina.de(List.copyOf(todos.subList(desde, hasta)), paginacion, todos.size()));
    }

    /**
     * En que condicion tiene este contribuyente ese predio, a la fecha: la de <b>su</b> cuota de
     * titularidad, no la del primer titular que aparezca. Un predio con tres copropietarios tiene
     * tres condiciones, y la que se dibuja en su fila es la suya.
     */
    private @Nullable String condicionDe(long predioId, long contribuyenteId, LocalDate fecha) {
        for (TitularDelPredio titular : titulares.de(predioId, fecha)) {
            if (titular.contribuyenteId() == contribuyenteId) {
                return titular.condicion();
            }
        }
        return null;
    }

    private static boolean pasa(
            PredioDeRentasResource fila,
            @Nullable String codigoPredial,
            @Nullable String sector,
            @Nullable String condicion) {
        String prefijo = filtro(codigoPredial);
        if (prefijo != null && !fila.codigoReferenciaCatastral().startsWith(prefijo)) {
            return false;
        }
        String elSector = filtro(sector);
        if (elSector != null && !elSector.equalsIgnoreCase(fila.sector())) {
            return false;
        }
        String laCondicion = filtro(condicion);
        return laCondicion == null || laCondicion.equalsIgnoreCase(fila.condicion());
    }

    private static @Nullable String filtro(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    private static @Nullable String primeroNoVacio(@Nullable String uno, @Nullable String otro) {
        String primero = filtro(uno);
        return primero != null ? primero : filtro(otro);
    }
}
