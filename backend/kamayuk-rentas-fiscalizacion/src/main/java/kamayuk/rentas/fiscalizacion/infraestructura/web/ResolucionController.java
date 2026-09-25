package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.TransferenciaDeFiscalizacion;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeResoluciones;
import kamayuk.rentas.fiscalizacion.aplicacion.LiquidarFiscalizacion;
import kamayuk.rentas.fiscalizacion.aplicacion.TransferirARentas;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeResoluciones;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionEnLaRelacion;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * La transferencia a rentas y su resolucion de determinacion por HTTP (#52, RF-054, RF-057).
 *
 * <h2>Dos rutas, dos opciones del catalogo</h2>
 *
 * <ul>
 *   <li>{@code POST /fiscalizacion/transferencias} es la accion de {@code fisc_resultados}: la
 *       pantalla declara su grilla como endpoint, y transferir necesita verbo propio. Exige el
 *       privilegio de <b>registro</b>: es el acto que cambia el padron.
 *   <li>{@code GET /fiscalizacion/resoluciones} es la <b>relacion</b> (#192), que hasta ese issue
 *       no existia.
 *   <li>{@code GET /fiscalizacion/resoluciones/{numero}} es {@code resolucion_determinacion_fisc},
 *       que el contrato ya publicaba y nadie servia.
 * </ul>
 *
 * <h2>La relacion, y por que faltaba (#192)</h2>
 *
 * <p>Hasta #192 la unica lectura era por numero exacto, y {@code
 * ConsultaDeResoluciones.deContribuyente} estaba escrita en la capa de aplicacion <b>sin ningun
 * controlador que la expusiera</b>. La consecuencia medida: la pantalla es la unica del sistema que
 * no puede tomar «la primera de la relacion» —las demas lo hacen, {@code coa-exp} entre ellas—
 * porque no habia relacion, de modo que abierta desde el menu no ensenaba una resolucion nunca; el
 * numero tenia que llegar en la direccion. Y el numero no lo publicaba ninguna otra operacion salvo
 * la respuesta de la transferencia que la creo.
 *
 * <p>Un filtro y no tres. Por que «Estado» y «Ejercicio» <b>no</b> viajan esta medido y escrito en
 * {@link kamayuk.rentas.fiscalizacion.dominio.CriterioDeResoluciones}: el primero no existe en el
 * dominio —{@code resolucion_determinacion} no admite {@code UPDATE} desde V49 y no hay historial
 * del que derivarlo— y el segundo es ambiguo, porque una resolucion tiene el ejercicio de su
 * numeracion y el periodo que fiscaliza, y no son el mismo.
 *
 * <p>No hay {@code PUT} ni {@code PATCH}, y no es un olvido: {@code resolucion_determinacion} no
 * admite {@code UPDATE} desde V49. Una resolucion equivocada se deja sin efecto con otro acto.
 */
@RestController
@RequestMapping(Api.RAIZ + "/fiscalizacion")
public class ResolucionController {

    /** Las dos opciones del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_RESULTADOS = "fisc_resultados";

    static final String ACCESO_RESOLUCION = "resolucion_determinacion_fisc";

    /** El orden por omision de la relacion: el numero, que es como se busca una resolucion. */
    private static final String ORDEN_POR_OMISION = "numero";

    private final TransferirARentas transferir;
    private final ConsultaDeResoluciones consulta;
    private final DirectorioDeContribuyentes contribuyentes;
    private final Clock reloj;

    public ResolucionController(
            TransferirARentas transferir,
            ConsultaDeResoluciones consulta,
            DirectorioDeContribuyentes contribuyentes,
            Clock reloj) {
        this.transferir = transferir;
        this.consulta = consulta;
        this.contribuyentes = contribuyentes;
        this.reloj = reloj;
    }

    /**
     * Transfiere el resultado al padron y emite su resolucion (RF-054).
     *
     * <p>Los errores se traducen uno a uno y no a un 500 generico: cada uno se arregla de una
     * manera distinta —cerrar la liquidacion, adjuntar el papel, transferir la version buena— y un
     * mensaje unico dejaria a quien opera adivinando.
     */
    @PostMapping("/transferencias")
    @RequiereAcceso(acceso = ACCESO_RESULTADOS, privilegio = Privilegio.REGISTRO)
    @ResponseStatus(HttpStatus.CREATED)
    public ResolucionResource transferir(@RequestBody PeticionDeTransferencia peticion) {
        Observacion observacion = observacionDe(peticion.observacion());

        TransferirARentas.Transferencia transferencia;
        try {
            transferencia =
                    transferir.transferir(
                            new TransferirARentas.Peticion(
                                    exigir(peticion.nLiquidacion(), "nLiquidacion"),
                                    fechaOpcional(peticion.fecha(), "fecha", LocalDate.now(reloj)),
                                    exigir(peticion.documentoSustento(), "documentoSustento"),
                                    exigir(peticion.sustento(), "sustento"),
                                    exigir(peticion.baseLegal(), "baseLegal")),
                            formatoDe(peticion.formato()),
                            observacion);
        } catch (TransferirARentas.LiquidacionInexistente
                | LiquidarFiscalizacion.ActaInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (ResolucionDeDeterminacionRepository.LiquidacionYaTransferida
                | TransferirARentas.LiquidacionSustituida
                | ActaFiscalizacion.ActaAnulada enConflicto) {
            // La visita anulada (#339) es 409 como las otras dos: la peticion esta bien, lo que
            // no la admite es la situacion, y reintentarla no sirve de nada.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(enConflicto));
        } catch (TransferirARentas.SinSustentoDocumental
                | TransferenciaDeFiscalizacion.SinFichaQueVersionar
                | IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        return ResolucionResource.de(
                consulta.porNumero(transferencia.resolucion().numero())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "La resolucion recien registrada tiene que poder"
                                                        + " leerse en la misma transaccion")),
                transferencia);
    }

    /**
     * La relacion de resoluciones de determinacion (#192, RF-057).
     *
     * <p>{@code ?contribuyente=} es el <b>codigo</b> del padron —{@code C-000001}—, el mismo que
     * teclea {@code GET /fiscalizacion/estado-cuenta} y no el identificador interno, que ninguna
     * pantalla ensena. Un codigo que no existe es {@code 404} y no una relacion sin filtrar:
     * devolver todas las resoluciones de la municipalidad a quien pregunto por una persona es
     * contestar otra cosa con formato de buena (#425, #541).
     */
    @GetMapping("/resoluciones")
    @RequiereAcceso(acceso = ACCESO_RESOLUCION, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<ResolucionEnLaRelacionResource> resoluciones(
            @RequestParam(required = false) @Nullable String contribuyente,
            ParametrosDePaginacion paginacion) {

        Pagina<ResolucionEnLaRelacion> pagina =
                consulta.buscar(
                        new CriterioDeResoluciones(contribuyenteOpcional(contribuyente)),
                        paginacion.aPaginacion(ORDEN_POR_OMISION));

        Map<Long, ResumenDeContribuyente> padron = padronDe(pagina);
        return RespuestaPaginada.de(
                pagina, fila -> ResolucionEnLaRelacionResource.de(fila, padron));
    }

    /** La resolucion de determinacion por su numero (RF-057). */
    @GetMapping("/resoluciones/{numero}")
    @RequiereAcceso(acceso = ACCESO_RESOLUCION, privilegio = Privilegio.LECTURA)
    public ResolucionResource resolucion(@PathVariable String numero) {
        return ResolucionResource.de(consultada(numero));
    }

    /**
     * La misma resolucion, como documento: {@code ?formato=PDF|XLS|RTF} (#593, RF-057, RF-132).
     *
     * <p>Era el <b>unico</b> documento del sistema que no se podia descargar: la ficha del
     * contribuyente, la constancia de no adeudo, los once reportes de #53 y el duplicado del recibo
     * sirven los tres formatos desde su misma ruta, y este —que es el valor que se notifica al
     * contribuyente y el que arranca el plazo del art. 137 para reclamar— solo devolvia JSON.
     *
     * <h2>Se mira, y no se emite otra vez</h2>
     *
     * <p><b>No registra nada</b>, y el motivo es mas fuerte que el de la ficha y la constancia: no
     * es que aqui no haya nada que numerar, es que <b>ya esta numerado</b>. La transferencia emitio
     * el papel, le puso su correlativo y guardo su modelo con su resumen SHA-256; lo que falta es
     * entregarlo. Volver a emitir gastaria un segundo correlativo para el mismo acto —dos numeros
     * de resolucion para una sola resolucion—, y reimprimir lo marcaria «DUPLICADO N° 1» la primera
     * vez que ese papel sale del sistema, porque {@code POST /fiscalizacion/transferencias}
     * devuelve JSON y <b>descarta los bytes que emitio</b>. Ver {@link EmitirDocumento#copia}.
     *
     * <h2>Basta {@code LECTURA}</h2>
     *
     * <p>Mismo reparto que {@code ConstanciaController} y {@code catastro.ReporteController}: el
     * documento es la misma hoja que esta pantalla ya dibuja con {@code lectura}, y que el
     * navegador ya imprime con Ctrl+P. Los padrones de #53 piden {@link Privilegio#IMPRESION}
     * porque sacan del sistema un listado que nadie llego a ver entero; aqui no hay nada que no
     * este ya en la respuesta JSON de al lado. Pedir un segundo privilegio negaria el archivo a
     * quien tiene el contenido delante, que es una descarga prometida contestando 403 (#332).
     */
    @GetMapping(value = "/resoluciones/{numero}", params = "formato")
    @RequiereAcceso(acceso = ACCESO_RESOLUCION, privilegio = Privilegio.LECTURA)
    public ResponseEntity<byte[]> documento(
            @PathVariable String numero, @RequestParam String formato) {

        ConsultaDeResoluciones.CopiaDeLaResolucion copia;
        try {
            copia =
                    consulta.copiaDe(numero, formatoPedido(formato))
                            .orElseThrow(() -> noHayResolucion(numero));
        } catch (EmitirDocumento.LaReimpresionNoCoincide distinto) {
            // 409 y no 500, igual que el duplicado del recibo: la peticion esta bien y el
            // sistema tampoco esta roto. Lo que pasa es que entregar esto seria dar un papel
            // distinto al que se emitio con ese mismo numero.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(distinto));
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(copia.formato().tipoDeMedio()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(copia.nombreDeArchivo())
                                .build()
                                .toString())
                .body(copia.contenido());
    }

    private ConsultaDeResoluciones.ResolucionConsultada consultada(String numero) {
        return consulta.porNumero(numero).orElseThrow(() -> noHayResolucion(numero));
    }

    /**
     * Los obligados de la pagina, resueltos a codigo y nombre en <b>una</b> consulta.
     *
     * <p>Mismo reparto que {@code OmisosController.padronDe}: resolverlo desde el cliente cuesta
     * una peticion por fila. Quien ya no este en el padron no sale del mapa y su fila se publica
     * con los dos campos nulos, que es lo que hay que poder ver.
     */
    private Map<Long, ResumenDeContribuyente> padronDe(Pagina<ResolucionEnLaRelacion> pagina) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (ResolucionEnLaRelacion fila : pagina.contenido()) {
            ids.add(fila.contribuyenteId());
        }
        return ids.isEmpty() ? Map.of() : contribuyentes.porIds(ids);
    }

    /** El contribuyente del filtro, por su codigo del padron. Sin filtro, todas. */
    private @Nullable Long contribuyenteOpcional(@Nullable String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return null;
        }
        String limpio = codigo.strip();
        return contribuyentes
                .porCodigo(limpio)
                .orElseThrow(
                        () ->
                                new ProblemaDeNegocio(
                                        CodigoDeError.NO_ENCONTRADO,
                                        "No hay ningun contribuyente con el codigo '"
                                                + limpio
                                                + "'"))
                .id();
    }

    private static ProblemaDeNegocio noHayResolucion(String numero) {
        return new ProblemaDeNegocio(
                CodigoDeError.NO_ENCONTRADO,
                "No hay ninguna resolucion de determinacion con el numero '" + numero + "'");
    }

    // ------------------------------------------------------------------

    /**
     * El formato <b>pedido por la consulta</b>. Sin valor valido, 422 nombrando los tres.
     *
     * <p>Son dos analizadores y no uno, y la diferencia es la que #593 pide fijar: en el cuerpo de
     * la transferencia {@code formato} es <b>opcional</b> —no se elige el papel, se elige en que se
     * dibuja el que se emite— y ausente significa PDF. En la descarga no hay omision posible:
     * {@code params = "formato"} elige este handler en cuanto el parametro esta, asi que {@code
     * ?formato=} llega aqui vacio y devolver PDF seria contestar con un formato que nadie pidio.
     */
    private static FormatoDeDocumento formatoPedido(String texto) {
        try {
            return FormatoDeDocumento.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El parametro 'formato' admite PDF, XLS o RTF: '" + texto + "'");
        }
    }

    private static FormatoDeDocumento formatoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return FormatoDeDocumento.PDF;
        }
        try {
            return FormatoDeDocumento.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo 'formato' admite PDF, XLS o RTF: '" + texto + "'");
        }
    }

    private static LocalDate fechaOpcional(
            @Nullable String texto, String campo, LocalDate porOmision) {
        if (texto == null || texto.isBlank()) {
            return porOmision;
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' va en formato ISO (2026-03-16): '" + texto + "'");
        }
    }

    private static Observacion observacionDe(@Nullable String texto) {
        try {
            return Observacion.de(exigir(texto, "observacion"));
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }
    }

    private static String exigir(@Nullable String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return valor.strip();
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "La operacion no se pudo completar" : mensaje;
    }

    /**
     * El cuerpo de una transferencia. <b>Lista blanca</b>: lo que no esta aqui no entra.
     *
     * <p>No lleva ni el predio, ni el area, ni el uso: todo eso sale de la liquidacion y del acta,
     * que es donde el fiscalizador lo dejo. Si el cuerpo pudiera traerlos, la transferencia
     * inscribiria en el padron lo que alguien teclea en la pantalla y no lo que se hallo en campo,
     * que es exactamente lo que esta frontera existe para impedir.
     */
    public record PeticionDeTransferencia(
            @Nullable String observacion,
            @Nullable String nLiquidacion,
            @Nullable String documentoSustento,
            @Nullable String sustento,
            @Nullable String baseLegal,
            @Nullable String fecha,
            @Nullable String formato) {}
}
