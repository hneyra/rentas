package kamayuk.rentas.nucleo.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeVehiculos;
import kamayuk.rentas.nucleo.dominio.CriterioDeVehiculo;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ficha del vehiculo: {@code GET /api/v1/rentas/vehiculos/{placa}} (RF-024).
 *
 * <p>La placa de la ruta se compara <b>sin el guion</b>, asi que {@code ABC-123} y {@code ABC123}
 * llevan a la misma ficha. Es lo que se necesita en ventanilla: quien pregunta trae la placa
 * escrita como se le ocurrio, y el sistema no puede contestar «no existe» a una diferencia de
 * puntuacion.
 *
 * <p>La ficha incluye el <b>historial de placas</b>. Es el dato que convierte una consulta en una
 * respuesta util cuando alguien reclama una papeleta a nombre de una placa que ya no es la suya:
 * dice cuando cambio, quien lo hizo y con que sustento.
 *
 * <p>El controlador no habla con el repositorio: llama al caso de uso, que es quien abre la
 * transaccion. Sin transaccion no hay {@code SET LOCAL} y la politica RLS no tiene que leer, asi
 * que una lectura «simple» desde aqui no funcionaria nunca.
 *
 * <h2>Y la coleccion de un contribuyente: {@code GET /api/v1/rentas/vehiculos} (#524)</h2>
 *
 * <p>La consulta ya existia y la sirve este mismo contexto —{@code ConsultaVehiculosController},
 * {@code GET /consultas/vehiculos}—, pero bajo la opcion <b>del modulo Consultas</b> y su permiso.
 * El expediente del contribuyente de Rentas (#503 F2) no puede tomarla prestada de ahi: las
 * conexiones de la interfaz llegan con el trozo de su modulo (#433), y quien tenga Rentas y no
 * Consultas veria un aviso de permiso ajeno dentro de su propio expediente.
 *
 * <p><b>El contribuyente es obligatorio, y esa es la decision que sostiene el endpoint.</b> Sin el,
 * esto seria una segunda puerta al padron vehicular entero <b>detras de un permiso mas
 * estrecho</b>: quien solo tiene {@code vehiculos} hoy llega a una ficha por placa —tiene que saber
 * la placa— y pasaria a poder listarlo todo. Con el criterio exigido, la operacion es lo que dice
 * ser: los vehiculos de una persona.
 *
 * <p>Se pide con <b>dos nombres</b>, {@code contribuyente} y {@code codContribuyente}, y uno de los
 * dos es obligatorio. Es el mismo par que {@code GET /rentas/predios} ya admite: las dos lecturas
 * llenan la <b>misma</b> seccion del expediente —«Predios y vehiculos»— y quien conecta la segunda
 * copiando la primera escribia {@code codContribuyente}, recibia un 422 que nombra un parametro que
 * la pantalla no dibuja, y el sintoma no se parecia a la causa (#595).
 *
 * <h2>Tres cosas distintas que se decian igual (#595)</h2>
 *
 * <p>Hasta #595 esta operacion respondia {@code 200} con la pagina vacia en <b>dos</b> casos que no
 * son el mismo, y solo uno de los dos es «este contribuyente no tiene vehiculos»:
 *
 * <ul>
 *   <li><b>un codigo que no esta en el padron</b> —tecleado mal, o de otra municipalidad—: ahora
 *       {@code 404} nombrando el codigo, como su hermana de predios desde #541. Es lo que se
 *       pregunta en ventanilla, y las dos respuestas eran identicas byte a byte;
 *   <li><b>un contribuyente del padron sin ningun vehiculo</b>: sigue siendo {@code 200} con cero
 *       filas, que es lo unico que de verdad significa «no tiene».
 * </ul>
 *
 * <p>Y las dos se leen juntas: con un codigo inexistente la pantalla decia a la vez «ese codigo no
 * esta en el padron» —de predios— y «esta persona no tiene ningun vehiculo a su nombre», una debajo
 * de la otra, y la segunda era falsa.
 */
@RestController
@RequestMapping(Api.RAIZ + "/rentas/vehiculos")
@RequiereAcceso(acceso = "vehiculos", privilegio = Privilegio.LECTURA)
public class VehiculoController {

    private static final String ORDEN_POR_OMISION = "placa";

    private final ConsultaDeVehiculos consulta;
    private final DirectorioDeContribuyentes directorio;
    private final Clock reloj;

    public VehiculoController(
            ConsultaDeVehiculos consulta, DirectorioDeContribuyentes directorio, Clock reloj) {
        this.consulta = consulta;
        this.directorio = directorio;
        this.reloj = reloj;
    }

    @GetMapping("/{placa}")
    public VehiculoResource porPlaca(@PathVariable String placa) {
        // `Placa` valida y normaliza: una placa mal formada sale como 422 con un
        // mensaje que habla del dato, no como un 404 que haria pensar que el
        // vehiculo no esta.
        ConsultaDeVehiculos.FichaDeVehiculo ficha = consulta.porPlaca(Placa.de(placa));
        return VehiculoResource.de(ficha.vehiculo(), ficha.historial());
    }

    /**
     * Los vehiculos de un contribuyente, con su deuda a la fecha (#524).
     *
     * <p>La fila es la misma que publica {@code /consultas/vehiculos} —{@link
     * VehiculoEncontradoResource}, con su {@code ImporteActualizado}—: dos formas distintas de la
     * misma lectura dirian dos cosas del mismo vehiculo, y la que se leyera en el expediente seria
     * la que nadie compara.
     *
     * <p>La fecha es la del corte de la deuda (regla 9). Sin ella, la de hoy.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public RespuestaPaginada<VehiculoEncontradoResource> delContribuyente(
            @RequestParam(required = false) @Nullable String contribuyente,
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String fecha,
            ParametrosDePaginacion parametros) {

        String codigo = primeroNoVacio(codContribuyente, contribuyente);
        if (codigo == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que decir de quien son los vehiculos: falta «codContribuyente» (o su"
                            + " otro nombre, «contribuyente»)");
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

        // El criterio se compone **solo** con el contribuyente: los otros tres que
        // `CriterioDeVehiculo` admite —placa, motor, estado— son los de la busqueda del
        // padron, y esta operacion no es una busqueda. Anadirlos aqui seria devolver por
        // la puerta estrecha lo que el parametro obligatorio acaba de cerrar.
        //
        // Y el codigo que viaja es el **canonico del padron**, no el tecleado: quien lo
        // escribio en minusculas pregunta por la misma persona.
        CriterioDeVehiculo criterio =
                new CriterioDeVehiculo(null, null, encontrado.get().codigo(), null);

        return RespuestaPaginada.de(
                consulta.buscar(
                        criterio, fechaDe(fecha), parametros.aPaginacion(ORDEN_POR_OMISION)),
                VehiculoEncontradoResource::de);
    }

    private static @Nullable String primeroNoVacio(@Nullable String uno, @Nullable String otro) {
        String primero = limpio(uno);
        return primero != null ? primero : limpio(otro);
    }

    private static @Nullable String limpio(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String sinBlancos = texto.strip();
        return sinBlancos.isEmpty() ? null : sinBlancos;
    }

    private LocalDate fechaDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return LocalDate.now(reloj);
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (java.time.format.DateTimeParseException excepcion) {
            throw new IllegalArgumentException(
                    "La fecha debe tener formato AAAA-MM-DD: '" + texto + "'", excepcion);
        }
    }
}
