package kamayuk.rentas.cuentacorriente.infraestructura.web;

import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultasDelLibro;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeConsulta;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estado de cuenta corriente: {@code GET /api/v1/consultas/cuenta-corriente/{codigo}} (NEG-03,
 * RF-040).
 *
 * <p><b>Solo lectura.</b> El alta y la reversion de asientos las hacen los contextos que emiten
 * deuda —determinacion, tesoreria, coactiva— llamando a {@code RegistrarAsiento}; no se publican
 * aqui porque el contrato no declara ningun {@code POST} en esta ruta y ninguno de esos contextos
 * existe todavia.
 *
 * <p>{@code situacion} es un filtro que el contrato declara y esta pantalla no resuelve. El saldo
 * proyectado del que depende ya existe (#23), pero decidir que cuenta como «pendiente» a que fecha
 * es la consulta de deuda por contribuyente —#25, mas adelante en la secuencia—, no este listado
 * cronologico. Se ignora en vez de fallar la peticion.
 */
@RestController
@RequestMapping(Api.RAIZ + "/consultas/cuenta-corriente")
@RequiereAcceso(acceso = "cuenta_corriente", privilegio = Privilegio.LECTURA)
public class CuentaCorrienteController {

    /** Cronologico: es como se lee un estado de cuenta cuando no se pide otro orden. */
    private static final String ORDEN_POR_OMISION = "fecha_valor";

    private final ConsultasDelLibro consulta;

    public CuentaCorrienteController(ConsultasDelLibro consulta) {
        this.consulta = consulta;
    }

    /**
     * {@code @Transactional(readOnly = true)} directo en el controlador, y no un caso de uso
     * intermedio: es un passthrough de lectura sin ninguna regla que aplicar. Sin esta anotacion la
     * consulta {@code falla} en la base por falta de contexto —{@code RepositorioJdbc} no abre
     * transaccion propia, y sin una activa no hay {@code SET LOCAL}—, que es el sintoma exacto que
     * encontro la prueba de regresion de este mismo archivo.
     */
    @GetMapping("/{codigo}")
    @Transactional(readOnly = true)
    public RespuestaPaginada<AsientoResource> estadoDeCuenta(
            @PathVariable String codigo,
            @RequestParam(required = false) @Nullable String ejercicio,
            @RequestParam(required = false) @Nullable String tributo,
            ParametrosDePaginacion paginacion) {

        CriterioDeConsulta criterio =
                new CriterioDeConsulta(codigo, ejercicioDe(ejercicio), tributo, null);

        return RespuestaPaginada.de(
                consulta.asientos(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                AsientoResource::de);
    }

    /**
     * {@code null} si no viene o viene en blanco. Un valor no numerico llega como {@code
     * NumberFormatException}, que extiende {@code IllegalArgumentException} y el manejador global
     * de errores traduce a 422 sin exponer nada de la base.
     */
    private static @Nullable Ejercicio ejercicioDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return new Ejercicio(Integer.parseInt(texto.strip()));
    }
}
