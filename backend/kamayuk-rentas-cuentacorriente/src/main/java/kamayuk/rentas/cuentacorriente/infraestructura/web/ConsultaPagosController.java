package kamayuk.rentas.cuentacorriente.infraestructura.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultasDelLibro;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDePagos;
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
 * {@code consulta_pagos}: {@code GET /api/v1/consultas/pagos} (RF-048, #25).
 *
 * <p>El historial de pagos de un contribuyente: cada fila es el asiento {@code ABONO} de concepto
 * {@code PAGO} con que se registro el cobro, con su {@code documentoOrigen} como recibo y su fecha
 * valor (regla 9, RNF-075). Reusa {@link AsientoResource}, la misma forma que {@code
 * consulta_altas_bajas} —es la misma tabla, filtrada distinto—.
 *
 * <p><b>{@code medioDePago} es un filtro que el contrato declara y esta pantalla no resuelve</b>:
 * ningun campo del asiento distingue efectivo de tarjeta o transferencia, porque esa distincion es
 * de caja —{@code tesoreria}, que todavia no existe—. Se ignora en vez de fallar la peticion, igual
 * que {@code autoManual} en {@code AltasBajasController}. Lo mismo para «la caja» que menciona el
 * resumen del manual: no hay ninguna columna que la registre todavia.
 */
@RestController
@RequestMapping(Api.RAIZ + "/consultas/pagos")
@RequiereAcceso(acceso = "consulta_pagos", privilegio = Privilegio.LECTURA)
public class ConsultaPagosController {

    /** Cronologico, como se lee cualquier movimiento de cuenta corriente. */
    private static final String ORDEN_POR_OMISION = "fecha_valor";

    private final ConsultasDelLibro consulta;

    public ConsultaPagosController(ConsultasDelLibro consulta) {
        this.consulta = consulta;
    }

    /**
     * {@code @Transactional(readOnly = true)} directo en el controlador: es un passthrough de
     * lectura, sin caso de uso intermedio que lo justifique, igual que {@code
     * AltasBajasController}.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public RespuestaPaginada<AsientoResource> pagos(
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            ParametrosDePaginacion parametros) {

        String codigo = exigirContribuyente(codContribuyente);
        if (consulta.contribuyentePorCodigo(codigo).isEmpty()) {
            throw noEstaEnElPadron(codigo);
        }

        CriterioDePagos criterio =
                new CriterioDePagos(codigo, fechaDe("desde", desde), fechaDe("hasta", hasta));

        return RespuestaPaginada.de(
                consulta.pagos(criterio, parametros.aPaginacion(ORDEN_POR_OMISION)),
                AsientoResource::de);
    }

    private static @Nullable LocalDate fechaDe(String nombre, @Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException excepcion) {
            throw new IllegalArgumentException(
                    "El filtro «" + nombre + "» debe tener formato AAAA-MM-DD: '" + texto + "'",
                    excepcion);
        }
    }

    /**
     * Lo que la peticion diga de quien es la consulta, con los dos nombres (#622).
     *
     * <p>Uno de los dos es <b>obligatorio</b>: sin ninguno, 422. Sin esa exigencia esto seria una
     * puerta al padron entero.
     */
    private static String exigirContribuyente(@Nullable String canonico) {
        String codigo = limpio(canonico);
        if (codigo == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que decir de quien es la consulta: falta «codContribuyente»");
        }
        return codigo;
    }

    private static @Nullable String limpio(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String sinBlancos = texto.strip();
        return sinBlancos.isEmpty() ? null : sinBlancos;
    }

    /**
     * Un codigo que no esta en el padron es {@code 404} nombrandolo, no una pagina vacia (#622).
     *
     * <p>Es el mismo defecto que #541 y #595 cerraron en las dos lecturas de Rentas: el expediente
     * pide siete lecturas con el mismo codigo, una contestaba 404 y las otras seis «existe y no
     * tiene nada». Quien atiende leia seis afirmaciones de que la persona existe debajo de una que
     * decia que no — y la que mas cuesta es la de deuda, porque «no tiene deuda pendiente» sobre
     * alguien que el padron no reconoce es lo que se dice antes de emitir una constancia de no
     * adeudo.
     */
    private static RuntimeException noEstaEnElPadron(String codigo) {
        return new ProblemaDeNegocio(
                CodigoDeError.NO_ENCONTRADO,
                "En el padron de esta municipalidad no hay ningun contribuyente con codigo '"
                        + codigo
                        + "'");
    }
}
