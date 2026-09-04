package kamayuk.rentas.tesoreria.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.aplicacion.AnularRecibo;
import kamayuk.rentas.tesoreria.aplicacion.ConsultaDeRecibos;
import kamayuk.rentas.tesoreria.aplicacion.DuplicadoDeRecibo;
import kamayuk.rentas.tesoreria.dominio.CriterioDeRecibos;
import kamayuk.rentas.tesoreria.dominio.EstadoDeRecibo;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeReciboRepository;
import kamayuk.rentas.tesoreria.dominio.NumeroDeRecibo;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Lo que le pasa a un recibo despues de emitirse: su duplicado y su anulacion (RF-082, RF-083).
 *
 * <p>Ningun {@code PUT} ni {@code PATCH}, igual que en {@link CajaController} y por lo mismo: un
 * recibo no se corrige (regla 4, V29). Lo que le pasa llega como un recurso nuevo.
 *
 * <h2>Y como se encuentra el recibo cuando no se tiene el papel (#548)</h2>
 *
 * <p>{@code GET /tesoreria/recibos} lista los recibos emitidos con los filtros de la pantalla.
 * Hasta #548 no existia, y su ausencia dejaba a este controlador sirviendo solo a quien <b>ya sabe
 * el numero impreso</b> —que es justo lo contrario de quien viene a pedir un duplicado—.
 *
 * <h2>El numero, en la ruta</h2>
 *
 * <p>{@code {nro}} es el numero <b>impreso</b>, {@code 001-0000123}: lo que dice el papel que el
 * contribuyente trae a la ventanilla. Ni el identificador interno ni la serie y el correlativo por
 * separado —quien atiende tiene delante una sola cadena y la teclea entera—.
 *
 * <h2>Dos privilegios distintos para el duplicado</h2>
 *
 * <p>Mirar un recibo es {@code LECTURA}; sacarlo por la impresora es {@code IMPRESION}. El manual
 * separa los dos a proposito (cap. 4, RF-121) y aqui se nota: reimprimir un recibo de caja es un
 * acto —el papel circula— y la vista previa no.
 *
 * <h2>Y dos para la anulacion</h2>
 *
 * <p>{@code ELIMINACION} siempre: anular es la baja de un documento, que es lo que ese privilegio
 * gobierna. Y ademas {@code ESPECIAL} cuando el recibo lo cobro <b>otro cajero</b>, que es
 * literalmente el ejemplo con el que {@link Privilegio} describe ese privilegio: «anular un recibo
 * ajeno». Un cajero puede deshacer su propio error de la ultima hora; deshacer el de otro es
 * meterse en el arqueo de otro, y eso lo autoriza quien responde por la caja.
 *
 * <p>Esa segunda comprobacion vive aqui y no en la anotacion por lo mismo que en {@code
 * SectorController}: la anotacion no puede expresar «segun de quien sea el recibo», y el
 * interceptor corre antes de que se sepa. Lo que si se conserva es la respuesta —el mismo {@code
 * SIN_PRIVILEGIO} y un mensaje de la misma forma—, para que negar por esta via no se distinga de
 * negar por aquella.
 */
@RestController
@RequestMapping(Api.RAIZ + "/tesoreria/recibos")
public class ReciboController {

    /** Las dos opciones del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_DUPLICADO = "duplicado_recibo";

    static final String ACCESO_ANULACION = "anulacion_recibo";

    /** Por que se ordena el listado cuando el cliente no lo dice. */
    private static final String ORDEN_POR_OMISION = "fecha";

    private final DuplicadoDeRecibo duplicados;
    private final AnularRecibo anular;
    private final ConsultaDeRecibos listado;
    private final ComprobadorDeAcceso comprobador;
    private final Clock reloj;

    public ReciboController(
            DuplicadoDeRecibo duplicados,
            AnularRecibo anular,
            ConsultaDeRecibos listado,
            ComprobadorDeAcceso comprobador,
            Clock reloj) {
        this.duplicados = duplicados;
        this.anular = anular;
        this.listado = listado;
        this.comprobador = comprobador;
        this.reloj = reloj;
    }

    /**
     * El listado de recibos emitidos, paginado (#548, RF-082).
     *
     * <p><b>Es la puerta que le faltaba a la ventanilla.</b> Sin ella el unico camino a un recibo
     * era saber su numero impreso, de modo que quien perdia el papel —que es exactamente quien
     * viene a pedir un duplicado— no podia encontrarlo. La grilla «Recibos localizados» que el
     * manual dibuja no tenia con que llenarse.
     *
     * <p><b>Sin filtro por numero de recibo, y a proposito</b>: el numero exacto ya resuelve por
     * {@code GET /tesoreria/recibos/{nro}/duplicado}. Este listado es para quien no lo tiene.
     *
     * <p>El acceso es {@code duplicado_recibo} con {@code LECTURA} —la opcion del catalogo desde la
     * que se busca—, no {@code IMPRESION}: mirar la lista no emite ningun papel.
     *
     * <p>Un contribuyente sin recibos devuelve una <b>pagina vacia</b> con {@code totalElementos:
     * 0}, nunca un 404: buscar y no encontrar no es un error.
     */
    @GetMapping
    @RequiereAcceso(acceso = ACCESO_DUPLICADO, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<ReciboEnListaResource> listar(
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String caja,
            @RequestParam(required = false) @Nullable String cajero,
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            @RequestParam(required = false) @Nullable String estado,
            ParametrosDePaginacion paginacion) {

        CriterioDeRecibos criterio;
        try {
            criterio =
                    new CriterioDeRecibos(
                            vacioAnulo(codContribuyente),
                            vacioAnulo(caja),
                            vacioAnulo(cajero),
                            fechaOpcional(desde, "desde"),
                            fechaOpcional(hasta, "hasta"),
                            estadoDe(estado));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        return RespuestaPaginada.de(
                listado.listar(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                ReciboEnListaResource::de);
    }

    /**
     * El duplicado como documento: {@code ?formato=PDF|XLS|RTF} (RF-082, RF-132).
     *
     * <p>Escribe, aunque sea un {@code GET}. El verbo lo fija el prototipo y el manual exige que
     * cada reimpresion quede registrada con quien la genero; entre publicar una ruta que ninguna
     * pantalla llama y registrar el acto en el verbo que la pantalla usa, se registra. Por eso pide
     * la {@code observacion}: es una escritura, y la regla 10 no tiene excepciones para las
     * pequenas.
     */
    @GetMapping(value = "/{nro}/duplicado", params = "formato")
    @RequiereAcceso(acceso = ACCESO_DUPLICADO, privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> duplicado(
            @PathVariable String nro,
            @RequestParam String formato,
            @RequestParam(required = false) @Nullable String observacion) {

        DuplicadoDeRecibo.Duplicado impreso;
        try {
            impreso =
                    duplicados.imprimir(
                            numeroDe(nro), formatoDe(formato), observacionDe(observacion));
        } catch (DuplicadoDeRecibo.ReciboInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (DuplicadoDeRecibo.LaReimpresionNoCoincide distinto) {
            // 409 y no 500: la peticion esta bien y el sistema tampoco esta roto en el
            // sentido de un fallo tecnico. Lo que pasa es que el estado actual no admite
            // entregar este papel, y quien lo pide tiene que enterarse de por que.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(distinto));
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(impreso.formato().tipoDeMedio()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(impreso.nombreDeArchivo())
                                .build()
                                .toString())
                .body(impreso.contenido());
    }

    /**
     * La vista previa: el recibo con su estado y sus duplicados, en JSON.
     *
     * <p>Sin {@code formato}, la ruta devuelve el <b>contenido</b> del documento, que es lo que la
     * interfaz pinta —mismo criterio que {@code ReporteController} en catastro—. No escribe.
     */
    @GetMapping("/{nro}/duplicado")
    @RequiereAcceso(acceso = ACCESO_DUPLICADO, privilegio = Privilegio.LECTURA)
    public DuplicadoResource vistaPrevia(@PathVariable String nro) {
        return duplicados
                .consultar(numeroDe(nro))
                .map(DuplicadoResource::de)
                .orElseThrow(
                        () ->
                                new ProblemaDeNegocio(
                                        CodigoDeError.NO_ENCONTRADO,
                                        "No hay ningun recibo " + nro + " en esta municipalidad"));
    }

    /** Anula el recibo del dia y devuelve la deuda al libro (RF-083). */
    @PostMapping("/{nro}/anulacion")
    @RequiereAcceso(acceso = ACCESO_ANULACION, privilegio = Privilegio.ELIMINACION)
    public ResponseEntity<AnulacionResource> anulacion(
            @PathVariable String nro, @RequestBody PeticionDeAnulacion peticion) {

        NumeroDeRecibo numero = numeroDe(nro);
        Observacion observacion = observacionDe(peticion.observacion());

        AnularRecibo.Anulacion anulacion;
        try {
            anulacion =
                    new AnularRecibo.Anulacion(
                            numero,
                            exigir(peticion.motivo(), "motivo"),
                            vacioAnulo(peticion.autorizadoPor()),
                            vacioAnulo(peticion.nDeMemorando()));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        exigirQuePuedaAnularEsteRecibo(numero);

        try {
            AnularRecibo.Anulado anulado = anular.anular(anulacion, observacion);
            return ResponseEntity.status(HttpStatus.CREATED).body(AnulacionResource.de(anulado));
        } catch (AnularRecibo.ReciboInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (AnularRecibo.FueraDelDiaDePago fueraDePlazo) {
            // 422 y no 409: no es que el estado no admita la operacion en este instante,
            // es que la peticion pide algo que nunca va a ser admisible -ese recibo no se
            // podra anular manana tampoco-. Lo que corresponde es otra operacion.
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(fueraDePlazo));
        } catch (MovimientoDeReciboRepository.ReciboYaAnulado yaAnulado) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(yaAnulado));
        } catch (AnularRecibo.TurnoYaCerrado cerrado) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(cerrado));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    // ------------------------------------------------------------------

    /**
     * Anular el recibo de otro cajero exige ademas {@code ESPECIAL}.
     *
     * <p>Se resuelve leyendo el recibo antes de anularlo. Cuesta una consulta y compra que el
     * privilegio que {@link Privilegio} describe como «anular un recibo ajeno» sea de verdad eso y
     * no una etiqueta sin efecto.
     *
     * <p>Un recibo que no existe no se distingue aqui: se deja pasar y responde 404 el caso de uso.
     * Negar con 403 antes de saber si existe convertiria este endpoint en un detector de numeros de
     * recibo validos para quien no tiene {@code ESPECIAL}.
     */
    private void exigirQuePuedaAnularEsteRecibo(NumeroDeRecibo numero) {
        String usuario = OrigenContext.actual().usuario();
        boolean ajeno =
                duplicados
                        .consultar(numero)
                        .map(consultado -> !consultado.recibo().cajero().equals(usuario))
                        .orElse(false);
        if (ajeno
                && !comprobador.autoriza(
                        usuario, ACCESO_ANULACION, Privilegio.ESPECIAL, LocalDate.now(reloj))) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.SIN_PRIVILEGIO,
                    "No tiene el privilegio "
                            + Privilegio.ESPECIAL
                            + " sobre "
                            + ACCESO_ANULACION
                            + ": ese recibo lo cobro otro cajero, y anularlo toca el arqueo de su"
                            + " turno");
        }
    }

    /**
     * El estado pedido, o {@code null} si no se filtro por el.
     *
     * <p>Se rechaza lo que no sea una de las dos palabras del enumerado en vez de tratarlo como
     * «todos»: un filtro que no se entiende y devuelve el listado entero es exactamente la lectura
     * que quien filtra cree haber descartado (#544).
     */
    private static @Nullable EstadoDeRecibo estadoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return EstadoDeRecibo.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Estado de recibo desconocido: '" + texto + "'. Se admite EMITIDO o ANULADO");
        }
    }

    /**
     * Una fecha del rango, o {@code null} si no vino.
     *
     * <p>{@code DateTimeParseException} no extiende {@code IllegalArgumentException}, asi que sin
     * este {@code catch} el manejador global la trataria como error interno en vez de como una
     * entrada mal formada.
     */
    private static @Nullable LocalDate fechaOpcional(@Nullable String texto, String campo) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException invalida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' no es una fecha ISO valida: '" + texto + "'");
        }
    }

    private static NumeroDeRecibo numeroDe(String impreso) {
        String texto = impreso == null ? "" : impreso.strip();
        int guion = texto.lastIndexOf('-');
        if (guion <= 0 || guion == texto.length() - 1) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El numero de recibo va como esta impreso en el papel, serie-correlativo:"
                            + " '001-0000123'. Llego '"
                            + impreso
                            + "'");
        }
        try {
            return new NumeroDeRecibo(
                    texto.substring(0, guion), Long.parseLong(texto.substring(guion + 1)));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El numero de recibo va como esta impreso en el papel, serie-correlativo:"
                            + " '001-0000123'. Llego '"
                            + impreso
                            + "'");
        }
    }

    private static FormatoDeDocumento formatoDe(String formato) {
        try {
            return FormatoDeDocumento.valueOf(formato.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El formato va entre PDF, XLS y RTF: '" + formato + "'");
        }
    }

    private static Observacion observacionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Toda escritura exige la observacion del usuario: sin ella no se guarda");
        }
        try {
            return Observacion.de(texto);
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

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        return (texto == null || texto.isBlank()) ? null : texto.strip();
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }
}
