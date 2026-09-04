package kamayuk.rentas.valores.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.valores.aplicacion.ConsultaDeValores;
import kamayuk.rentas.valores.dominio.CriterioDeConsultaDeValores;
import kamayuk.rentas.valores.dominio.SituacionDelValor;
import kamayuk.rentas.valores.dominio.TipoValor;
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
 * {@code consulta_valores}: {@code GET /api/v1/consultas/valores} (RF-041, #25).
 *
 * <p>Vive en {@code valores} y no en {@code cuentacorriente} porque es el contexto que mas datos
 * aporta —la cabecera, el detalle congelado, la diligencia y el pase son suyos—, mismo criterio que
 * {@code consulta_vehiculos} en {@code rentas}. A diferencia de aquella, esta consulta <b>no</b>
 * llama a {@code ConsultaDeDeudaPublica}: lo que la pantalla muestra en «Monto S/» es el desglose
 * congelado del valor, no la deuda de hoy.
 *
 * <h2>En que se diferencia de {@code GET /valores}</h2>
 *
 * <p>{@code valores_busqueda} (RF-092) lista la cabecera con los filtros de la cabecera. Esta anade
 * lo que la cabecera no guarda y la pantalla si muestra: que tributo y que periodo formaliza,
 * cuando se notifico, y —sobre todo— <b>en que punto de la cobranza esta a dia de hoy</b>, que no
 * es la columna {@code estado} sino una funcion de ella y de la fecha (ver {@link
 * SituacionDelValor}).
 *
 * <h2>Los cuatro filtros son los que declara el contrato, y uno se rechaza</h2>
 *
 * <p>{@code nroDeValor}, {@code codContribuyente}, {@code tipo} y {@code estado}. Del ultimo, el
 * prototipo ofrece «FIRME» —que aqui es {@link SituacionDelValor#EXIGIBLE}, y se acepta con las dos
 * palabras— y «RECLAMADO», que <b>no existe en el dominio</b>: no hay reclamacion de valores
 * todavia. Se responde 422 con el motivo en vez de ignorar el filtro, porque ignorarlo devolveria
 * el listado completo y quien lo mira creeria estar viendo solo los reclamados —el mismo criterio
 * con que {@code ConsultaController} de catastro rechaza {@code conciliadaConRentas}—.
 *
 * <h2>Por que no hay parametro de fecha</h2>
 *
 * <p>Porque el contrato no lo declara: la pantalla pregunta «como esta esto hoy». La fecha sale del
 * {@link Clock} inyectado —no de {@code LocalDate.now()} suelto, que ninguna prueba puede fijar— y
 * <b>viaja en la respuesta</b> como {@code situacionA}, de modo que la cifra y la situacion que se
 * imprimen dicen a que dia corresponden (regla 9, RNF-075).
 */
@RestController
@RequestMapping(Api.RAIZ + "/consultas/valores")
@RequiereAcceso(acceso = "consulta_valores", privilegio = Privilegio.LECTURA)
public class ConsultaValoresController {

    /** Cronologico por emision, como se recorre un lote de valores. */
    private static final String ORDEN_POR_OMISION = "fechaEmision";

    /** Lo que el desplegable del prototipo llama «no filtres». */
    private static final String TODOS = "TODOS";

    private final ConsultaDeValores consulta;
    private final Clock reloj;

    public ConsultaValoresController(ConsultaDeValores consulta, Clock reloj) {
        this.consulta = consulta;
        this.reloj = reloj;
    }

    @GetMapping
    public RespuestaPaginada<ValorConsultadoResource> consultar(
            @RequestParam(required = false) @Nullable String nroDeValor,
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String tipo,
            @RequestParam(required = false) @Nullable String estado,
            ParametrosDePaginacion parametros) {

        Paginacion paginacion = parametros.aPaginacion(ORDEN_POR_OMISION);
        LocalDate hoy = LocalDate.now(reloj);

        Long contribuyenteId = null;
        String codigo = limpio(codContribuyente);
        if (codigo != null) {
            Optional<ResumenDeContribuyente> encontrado = consulta.contribuyentePorCodigo(codigo);
            if (encontrado.isEmpty()) {
                // Hasta #622 esto devolvia la pagina vacia, y esa respuesta se lee como «este
                // contribuyente no tiene ningun valor». Un codigo que no esta en el padron es
                // 404 nombrandolo, igual que en las otras seis lecturas del expediente.
                throw noEstaEnElPadron(codigo);
            }
            contribuyenteId = encontrado.get().id();
        }

        CriterioDeConsultaDeValores criterio =
                new CriterioDeConsultaDeValores(
                        nroDeValor, contribuyenteId, tipoDe(tipo), null, situacionDe(estado), hoy);

        return RespuestaPaginada.de(
                consulta.buscar(criterio, paginacion), ValorConsultadoResource::de);
    }

    private static @Nullable TipoValor tipoDe(@Nullable String texto) {
        String limpio = filtroPedido(texto);
        if (limpio == null) {
            return null;
        }
        try {
            return TipoValor.porCodigo(limpio);
        } catch (IllegalArgumentException noEsCodigo) {
            // El desplegable del prototipo manda el nombre largo -«ORDEN DE PAGO»-, no el codigo.
            return porNombreLargo(limpio);
        }
    }

    /** «ORDEN DE PAGO», «RES. DETERMINACIÓN» y «RES. DE MULTA», como los escribe la pantalla. */
    private static TipoValor porNombreLargo(String texto) {
        String normalizado = sinTildes(texto).toUpperCase(java.util.Locale.ROOT);
        if (normalizado.startsWith("ORDEN")) {
            return TipoValor.ORDEN_DE_PAGO;
        }
        if (normalizado.contains("DETERMINACION")) {
            return TipoValor.RESOLUCION_DE_DETERMINACION;
        }
        if (normalizado.contains("MULTA")) {
            return TipoValor.RESOLUCION_DE_MULTA;
        }
        throw new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                "Tipo de valor desconocido: '"
                        + texto
                        + "'. Se admite OP, RD o RM, o «ORDEN DE PAGO», «RES. DETERMINACION» y"
                        + " «RES. DE MULTA»");
    }

    private static @Nullable SituacionDelValor situacionDe(@Nullable String texto) {
        String limpio = filtroPedido(texto);
        if (limpio == null) {
            return null;
        }
        try {
            return SituacionDelValor.porNombre(sinTildes(limpio));
        } catch (SituacionDelValor.SinEquivalenteEnElDominio sinDominio) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(sinDominio));
        } catch (IllegalArgumentException desconocida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(desconocida));
        }
    }

    /** El valor que el usuario eligio, o {@code null} si eligio «Todos» o no eligio nada. */
    private static @Nullable String filtroPedido(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String limpio = texto.strip();
        return TODOS.equalsIgnoreCase(limpio) ? null : limpio;
    }

    /**
     * El desplegable manda «RES. DETERMINACIÓN» con tilde; la comparacion no debe depender de eso.
     */
    private static String sinTildes(String texto) {
        return java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El filtro recibido no es valido" : mensaje;
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

    private static @Nullable String limpio(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String sinBlancos = texto.strip();
        return sinBlancos.isEmpty() ? null : sinBlancos;
    }
}
