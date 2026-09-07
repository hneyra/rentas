package kamayuk.rentas.nucleo.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeVehiculos;
import kamayuk.rentas.nucleo.dominio.CriterioDeVehiculo;
import kamayuk.rentas.nucleo.dominio.EstadoVehiculo;
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
 * {@code consulta_vehiculos}: {@code GET /api/v1/consultas/vehiculos} (RF-024, #25).
 *
 * <p>Vive en {@code rentas} y no en {@code cuentacorriente}: es el contexto mas rico de los dos
 * para esta pantalla —el padron vehicular es suyo—, y consulta la deuda de cada fila a traves de
 * {@link kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica}, la API publica del otro (ARQ-01
 * §4).
 *
 * <h2>{@code estado}: los cuatro del padron acotan, y lo demas es 422 (#42)</h2>
 *
 * <p>{@code estado} filtra por el estado del vehiculo en el padron, y sus valores son los cuatro
 * del enumerado {@link EstadoVehiculo} —{@code ACTIVO}, {@code TRANSFERIDO}, {@code BAJA}, {@code
 * ROBADO}—, que son los del {@code CHECK} de la tabla. Los cuatro acotan: {@code
 * VehiculoRepositoryJdbc} los lleva a {@code v.estado = :estado}.
 *
 * <p><b>Hasta #42 solo {@code BAJA} se traducia y los otros tres devolvian {@code null}</b>, que en
 * {@link CriterioDeVehiculo} significa <i>sin filtro</i>: quien pedia {@code estado=ACTIVO} recibia
 * el padron vehicular entero ordenado por placa, con aspecto de resultado acotado. Y el prototipo
 * empeora el caso, porque dibuja «AFECTO/INAFECTO/EXONERADO/BAJA», que <b>no</b> son valores de
 * esta columna sino de la afectacion calculada de cada fila: tres de las cuatro opciones del
 * desplegable no filtraban nada.
 *
 * <p>Ahora las tres se contestan con <b>422 nombrando el parametro</b> y diciendo que vocabulario
 * se admite, igual que {@code ArbitriosController} con «zona» y «uso» (#541). Un filtro que dice
 * que no es otra cosa que uno que se traga la pregunta; y devolver la lista vacia tampoco valdria,
 * porque «ningun vehiculo exonerado» se lee como un hecho del padron y no como «esa palabra no es
 * de esta columna».
 */
@RestController
@RequestMapping(Api.RAIZ + "/consultas/vehiculos")
@RequiereAcceso(acceso = "consulta_vehiculos", privilegio = Privilegio.LECTURA)
public class ConsultaVehiculosController {

    private static final String ORDEN_POR_OMISION = "placa";

    private final ConsultaDeVehiculos consulta;
    private final DirectorioDeContribuyentes directorio;
    private final Clock reloj;

    public ConsultaVehiculosController(
            ConsultaDeVehiculos consulta, DirectorioDeContribuyentes directorio, Clock reloj) {
        this.consulta = consulta;
        this.directorio = directorio;
        this.reloj = reloj;
    }

    @GetMapping
    public RespuestaPaginada<VehiculoEncontradoResource> buscar(
            @RequestParam(required = false) @Nullable String placa,
            @RequestParam(required = false) @Nullable String nroMotor,
            @RequestParam(required = false) @Nullable String contribuyente,
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String estado,
            @RequestParam(required = false) @Nullable String fecha,
            ParametrosDePaginacion parametros) {

        // Aqui el contribuyente es un FILTRO de la busqueda del padron y no el sujeto de la
        // consulta, asi que puede faltar. Lo que no puede es traer un codigo que no existe y
        // contestar como si la persona no tuviera vehiculos (#622).
        String codigo = primeroNoVacio(codContribuyente, contribuyente);
        if (codigo != null && directorio.porCodigo(codigo.toUpperCase(Locale.ROOT)).isEmpty()) {
            throw noEstaEnElPadron(codigo);
        }

        CriterioDeVehiculo criterio =
                new CriterioDeVehiculo(placa, nroMotor, codigo, estadoDe(estado));

        return RespuestaPaginada.de(
                consulta.buscar(
                        criterio, fechaDe(fecha), parametros.aPaginacion(ORDEN_POR_OMISION)),
                VehiculoEncontradoResource::de);
    }

    /**
     * El estado pedido, o 422 nombrando el parametro si no es del padron (#42).
     *
     * <p>Ausente es «sin filtro» y es lo unico que puede serlo: cualquier palabra que llegue tiene
     * que acotar o rechazarse, porque la tercera salida —leerla y no aplicarla— devuelve el padron
     * entero con aspecto de respuesta.
     */
    private static @Nullable EstadoVehiculo estadoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String pedido = texto.strip().toUpperCase(Locale.ROOT);
        for (EstadoVehiculo estado : EstadoVehiculo.values()) {
            if (estado.name().equals(pedido)) {
                return estado;
            }
        }
        throw new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                "El filtro «estado» admite los estados del padron vehicular —"
                        + vocabularioDelPadron()
                        + "—, y llego '"
                        + texto
                        + "'. «AFECTO», «INAFECTO» y «EXONERADO» son afectacion calculada de cada"
                        + " ejercicio y no una columna del padron, asi que no hay contra que"
                        + " compararlas: acotar por ellas devolveria el padron entero");
    }

    private static String vocabularioDelPadron() {
        StringBuilder nombres = new StringBuilder();
        for (EstadoVehiculo estado : EstadoVehiculo.values()) {
            if (nombres.length() > 0) {
                nombres.append(", ");
            }
            nombres.append(estado.name());
        }
        return nombres.toString();
    }

    private LocalDate fechaDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return LocalDate.now(reloj);
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException excepcion) {
            throw new IllegalArgumentException(
                    "La fecha debe tener formato AAAA-MM-DD: '" + texto + "'", excepcion);
        }
    }

    /**
     * Un codigo que no esta en el padron es {@code 404} nombrandolo, no una pagina vacia (#622).
     *
     * <p>Es el defecto que #541 y #595 cerraron en las dos lecturas de Rentas, en la pantalla de al
     * lado: el expediente de Consultas pedia siete lecturas con el mismo codigo, una contestaba 404
     * y las otras seis «existe y no tiene nada». Las dos que listan padron —esta y su gemela—
     * contradecian ademas a la pantalla de Rentas sobre la misma persona.
     */
    private static RuntimeException noEstaEnElPadron(String codigo) {
        return new ProblemaDeNegocio(
                CodigoDeError.NO_ENCONTRADO,
                "En el padron de esta municipalidad no hay ningun contribuyente con codigo '"
                        + codigo
                        + "'");
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
}
