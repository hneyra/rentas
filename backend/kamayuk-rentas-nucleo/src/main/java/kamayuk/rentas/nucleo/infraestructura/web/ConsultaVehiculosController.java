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
 * <p>{@code estado} filtra por el estado del vehiculo en el padron ({@code ACTIVO}, {@code
 * TRANSFERIDO}, {@code BAJA}, {@code ROBADO}): el prototipo dibuja
 * «AFECTO/INAFECTO/EXONERADO/BAJA», que no son valores de esta columna sino de la afectacion
 * calculada de cada fila. Solo {@code BAJA} coincide entre los dos vocabularios; el resto se ignora
 * como filtro —igual que {@code ConsultaDeudaController} ignora una «Fase» que no traduce—, y queda
 * para cuando la pantalla se conecte.
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

    /** Solo {@code BAJA} tiene equivalente en el padron; el resto no filtra (ver el javadoc). */
    private static @Nullable EstadoVehiculo estadoDe(@Nullable String texto) {
        if (texto == null || !"BAJA".equalsIgnoreCase(texto.strip())) {
            return null;
        }
        return EstadoVehiculo.BAJA;
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
