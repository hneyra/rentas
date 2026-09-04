package kamayuk.rentas.rentas.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.rentas.aplicacion.ConsultasDeRentas;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ImporteActualizado;
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
 * {@code consulta_predios}: {@code GET /api/v1/consultas/predios} (#25).
 *
 * <p>Vive en {@code rentas} y no en {@code catastro}: la deuda de cada predio es de {@code
 * cuentacorriente}, y {@code catastro} no puede depender de ella (ARQ-01 §4 regla 2). Combina la
 * API publica de los dos —{@link PrediosDelContribuyente} y {@link ConsultaDeDeudaPublica}—, mismo
 * patron que {@code ConsultaVehiculosController}.
 *
 * <p><b>{@code codigoPredial}, {@code calle}, {@code manzana} y {@code lote} son filtros que el
 * contrato declara y esta pantalla no resuelve todavia</b>: {@link PrediosDelContribuyente} solo
 * sabe listar los predios de un contribuyente, no buscar por ubicacion ni por codigo — eso necesita
 * una busqueda propia en {@code catastro} que no existe. Se aceptan para no romper la pantalla y no
 * filtran nada, igual que {@code autoManual} en {@code AltasBajasController}. {@code contribuyente}
 * si es obligatorio: sin el no hay que listar.
 *
 * <p>El autovaluo que menciona el contrato tampoco viaja: depende de la determinacion predial (#30,
 * #188), bloqueada por D-02a.
 */
@RestController
@RequestMapping(Api.RAIZ + "/consultas/predios")
@RequiereAcceso(acceso = "consulta_predios", privilegio = Privilegio.LECTURA)
public class ConsultaPrediosController {

    private static final String ORDEN_POR_OMISION = "codigoReferenciaCatastral";

    private final PrediosDelContribuyente predios;
    private final ConsultaDeDeudaPublica deuda;
    // Solo para contribuyentePorCodigo: vive en TransferenciaRepository por el mismo motivo que en
    // AsientoRepository, y no hace falta un repositorio nuevo para el mismo cruce por SQL.
    private final ConsultasDeRentas consulta;
    private final Clock reloj;

    public ConsultaPrediosController(
            PrediosDelContribuyente predios,
            ConsultaDeDeudaPublica deuda,
            ConsultasDeRentas consulta,
            Clock reloj) {
        this.predios = predios;
        this.deuda = deuda;
        this.consulta = consulta;
        this.reloj = reloj;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public RespuestaPaginada<PredioEncontradoResource> buscar(
            @RequestParam(required = false) @Nullable String contribuyente,
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String codigoPredial,
            @RequestParam(required = false) @Nullable String calle,
            @RequestParam(required = false) @Nullable String manzana,
            @RequestParam(required = false) @Nullable String lote,
            @RequestParam(required = false) @Nullable String fecha,
            ParametrosDePaginacion parametros) {

        Paginacion paginacion = parametros.aPaginacion(ORDEN_POR_OMISION);
        String codigo = primeroNoVacio(codContribuyente, contribuyente);
        if (codigo == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que decir de quien son los predios: falta «codContribuyente» (o su otro"
                            + " nombre, «contribuyente»)");
        }
        Optional<Long> contribuyenteId =
                consulta.contribuyentePorCodigo(codigo.toUpperCase(Locale.ROOT));
        if (contribuyenteId.isEmpty()) {
            throw noEstaEnElPadron(codigo);
        }

        LocalDate fechaDeCorte = fechaDe(fecha);
        List<PredioDelContribuyente> todos =
                new ArrayList<>(predios.de(contribuyenteId.get(), fechaDeCorte));
        todos.sort(Comparator.comparing(PredioDelContribuyente::codigoReferenciaCatastral));

        List<ObligacionPublica> obligaciones =
                deuda.deTodoElContribuyente(contribuyenteId.get(), fechaDeCorte);

        int desde = Math.min(paginacion.desplazamiento(), todos.size());
        int hasta = Math.min(desde + paginacion.tamano(), todos.size());

        List<PredioEncontradoResource> contenido = new ArrayList<>();
        for (PredioDelContribuyente predio : todos.subList(desde, hasta)) {
            contenido.add(
                    PredioEncontradoResource.de(
                            predio, deudaDe(predio.predioId(), obligaciones, fechaDeCorte)));
        }

        return RespuestaPaginada.de(Pagina.de(contenido, paginacion, todos.size()));
    }

    /**
     * La deuda de un predio es la suma de sus obligaciones: un predio puede tener el predial de mas
     * de un ejercicio a la vez. Las obligaciones comparten la misma fecha de corte —vienen de una
     * sola llamada a {@link ConsultaDeDeudaPublica#deTodoElContribuyente}—, asi que sumarlas no
     * mezcla cifras de fechas distintas.
     */
    private static ImporteActualizado deudaDe(
            long predioId, List<ObligacionPublica> obligaciones, LocalDate fecha) {
        Dinero total = Dinero.CERO;
        for (ObligacionPublica obligacion : obligaciones) {
            Long delPredio = obligacion.predioId();
            if (delPredio != null && delPredio == predioId) {
                total = total.mas(obligacion.total());
            }
        }
        return new ImporteActualizado(total, fecha);
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
