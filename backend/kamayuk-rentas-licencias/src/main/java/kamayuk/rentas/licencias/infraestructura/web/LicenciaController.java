package kamayuk.rentas.licencias.infraestructura.web;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.aplicacion.CancelarLicencia;
import kamayuk.rentas.licencias.aplicacion.ComprobacionDelDerecho;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeLicencias;
import kamayuk.rentas.licencias.aplicacion.DerechosDeTramiteParametrizados;
import kamayuk.rentas.licencias.aplicacion.DuplicarLicencia;
import kamayuk.rentas.licencias.aplicacion.EmitirLicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.aplicacion.ModeloDeLosReportesDeLicencias;
import kamayuk.rentas.licencias.aplicacion.ResumenAnualDeLicencias;
import kamayuk.rentas.licencias.dominio.CriterioDeLicencias;
import kamayuk.rentas.licencias.dominio.DuplicadoDeLicenciaRepository;
import kamayuk.rentas.licencias.dominio.EstadoDeLicencia;
import kamayuk.rentas.licencias.dominio.LicenciaRepository;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicenciaRepository;
import kamayuk.rentas.licencias.dominio.TipoDeLicencia;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
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
 * La licencia de funcionamiento por HTTP: consulta, emision, cancelacion y duplicado (RF-110,
 * RF-111).
 *
 * <h2>Tres opciones del catalogo, tres accesos</h2>
 *
 * <p>Cada endpoint declara el suyo: {@code licencia_funcionamiento} lee y registra, {@code
 * licencia_resolucion_cancelacion} y {@code licencia_resolucion_duplicado} escriben. Sin
 * {@code @RequiereAcceso} el guardia <b>niega</b>, y la regla de arquitectura rompe el build; las
 * dos cosas juntas hacen que el olvido no se pueda convertir en una puerta abierta.
 *
 * <h2>Ningun {@code PUT} ni {@code PATCH}</h2>
 *
 * <p>Una licencia no se corrige: es un acto administrativo que el titular cuelga en la pared, y
 * {@code licencia_funcionamiento} no admite {@code UPDATE} desde V37. Lo que le pasa llega como un
 * recurso nuevo —{@code /cancelacion}, {@code /duplicado}—, que es ademas lo que el prototipo
 * declara.
 *
 * <h2>El numero, en la ruta</h2>
 *
 * <p>{@code {id}} es el numero <b>impreso</b> de la licencia, tal como esta en el papel del
 * establecimiento. Ni el identificador interno de la fila —que ninguna pantalla conoce— ni el
 * ejercicio y el correlativo por separado.
 *
 * <h2>Que devuelve 422, y por que no 500 (#562)</h2>
 *
 * <p>El concepto del TUPA con que se comprueba el derecho de tramite sale del <b>conjunto
 * sellado</b> que rige a la fecha del acto ({@link DerechosDeTramiteParametrizados}, regla 5). Que
 * el conjunto exista y no traiga la llave ({@code DerechoSinParametrizar}) ya estaba traducido
 * desde #44; que <b>no exista ningun conjunto sellado</b> ({@code EjercicioSinSellar}) no lo
 * estaba, y con D-02a abierta ese es el estado <i>normal</i> de todas las municipalidades: caia en
 * el {@code @ExceptionHandler(Exception.class)} de {@code ManejadorDeErrores} y salia como <b>500
 * {@code ERROR_INTERNO} con identificador de incidencia</b>. Dos consecuencias, las mismas que #540
 * midio en Rentas y #547 en Tesoreria: la interfaz no puede distinguir «falta publicar una cifra»
 * de «el servidor esta roto», y un cliente que reintenta un 500 reintenta para siempre; y cada
 * intento escribia una incidencia de nivel ERROR en el registro del servidor.
 *
 * <p>El mensaje es el de la propia excepcion: nombra la llave —{@code
 * TUPA:DERECHO_LICENCIA_FUNCIONAMIENTO}— o, cuando lo que falta es el conjunto entero y no hay
 * llave que nombrar, el <b>ejercicio</b>. Un fallo de verdad del servidor sigue siendo 500 con su
 * incidencia: la lista nombra las excepciones una a una y no captura {@code RuntimeException}.
 *
 * <p>Los dos endpoints de resumen anual <b>no</b> necesitan la traduccion, y no por descuido:
 * {@link kamayuk.rentas.licencias.aplicacion.ResumenAnualDeLicencias} ya captura las dos dentro y
 * devuelve la fila del ano con sus conteos y el motivo en lugar de la cifra (#54).
 */
@RestController
@RequestMapping(Api.RAIZ + "/licencias")
public class LicenciaController {

    /** Las tres opciones del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_LICENCIA = "licencia_funcionamiento";

    static final String ACCESO_CANCELACION = "licencia_resolucion_cancelacion";

    static final String ACCESO_DUPLICADO = "licencia_resolucion_duplicado";

    /** Y las dos que #54 conecta: el padron y el resumen anual. */
    static final String ACCESO_PADRON = "licencia_padron";

    static final String ACCESO_RESUMEN = "licencia_resumen_anual";

    private static final String ORDEN_POR_OMISION = "numero";

    private final ConsultaDeLicencias consulta;
    private final EmitirLicenciaDeFuncionamiento emitir;
    private final CancelarLicencia cancelar;
    private final DuplicarLicencia duplicar;
    private final ResumenAnualDeLicencias resumen;
    private final GeneradorDeDocumentos documentos;
    private final Clock reloj;

    public LicenciaController(
            ConsultaDeLicencias consulta,
            EmitirLicenciaDeFuncionamiento emitir,
            CancelarLicencia cancelar,
            DuplicarLicencia duplicar,
            ResumenAnualDeLicencias resumen,
            GeneradorDeDocumentos documentos,
            Clock reloj) {
        this.consulta = consulta;
        this.emitir = emitir;
        this.cancelar = cancelar;
        this.duplicar = duplicar;
        this.resumen = resumen;
        this.documentos = documentos;
        this.reloj = reloj;
    }

    /**
     * La grilla de licencias, paginada, con el estado de cada una derivado a hoy (RF-110).
     *
     * <p>Con {@code nroLicencia}, la fila trae ademas su historial y sus duplicados: es la ficha
     * que la pantalla dibuja al abrir una licencia. Sin el, la fila es la que la grilla pinta y
     * nada mas —una pagina de veinte no puede costar veinte lecturas de detalle—.
     */
    @GetMapping("/funcionamiento")
    @RequiereAcceso(acceso = ACCESO_LICENCIA, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<LicenciaResource> listar(
            @RequestParam(required = false) @Nullable String nroLicencia,
            @RequestParam(required = false) @Nullable String nExpediente,
            @RequestParam(required = false) @Nullable String nombreDelContribuyente,
            @RequestParam(required = false) @Nullable String denominacionComercial,
            @RequestParam(required = false) @Nullable String direccion,
            ParametrosDePaginacion paginacion) {

        LocalDate hoy = LocalDate.now(reloj);

        if (nroLicencia != null && !nroLicencia.isBlank()) {
            return consulta.porNumero(nroLicencia.strip(), hoy)
                    .map(
                            ficha ->
                                    RespuestaPaginada.de(
                                            Pagina.de(
                                                    List.of(LicenciaResource.de(ficha)),
                                                    paginacion.aPaginacion(ORDEN_POR_OMISION),
                                                    1)))
                    .orElseGet(
                            () ->
                                    RespuestaPaginada.de(
                                            Pagina.vacia(
                                                    paginacion.aPaginacion(ORDEN_POR_OMISION))));
        }

        CriterioDeLicencias criterio =
                new CriterioDeLicencias(
                        null,
                        nExpediente,
                        denominacionComercial,
                        direccion,
                        null,
                        null,
                        null,
                        null,
                        null);

        return RespuestaPaginada.de(
                consulta.buscar(
                        criterio,
                        nombreDelContribuyente,
                        hoy,
                        paginacion.aPaginacion(ORDEN_POR_OMISION)),
                LicenciaResource::de);
    }

    /**
     * Emite una licencia de funcionamiento (RF-110).
     *
     * <p>Responde <b>201</b> con el numero de la licencia y el de su papel. El {@code 422} de un
     * recibo que no respalda el derecho lleva el motivo exacto: cual de las cuatro condiciones
     * fallo.
     */
    @PostMapping("/funcionamiento")
    @RequiereAcceso(acceso = ACCESO_LICENCIA, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ActoDeLicenciaResource> emitir(@RequestBody PeticionDeLicencia peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        FormatoDeDocumento formato = formatoDe(peticion.formato());
        LocalDate emisionEl = fechaOhoy(peticion.fechaDeEmision(), "fechaDeEmision");

        EmitirLicenciaDeFuncionamiento.Solicitud solicitud =
                new EmitirLicenciaDeFuncionamiento.Solicitud(
                        exigido(peticion.codContribuyente(), "codContribuyente"),
                        peticion.predioId(),
                        exigido(peticion.denominacionComercial(), "denominacionComercial"),
                        exigido(peticion.direccion(), "direccion"),
                        areaDe(peticion.areaDelEstablecimiento()),
                        tipoDe(peticion.tipoDeLicencia()),
                        vacioAnulo(peticion.zonificacion()),
                        peticion.aforo(),
                        emisionEl,
                        fechaOpcional(peticion.fechaDeVencimiento(), "fechaDeVencimiento"),
                        exigido(peticion.nDeRecibo(), "nDeRecibo"),
                        peticion.giros() == null ? List.of() : peticion.giros(),
                        exigido(peticion.giroPrincipal(), "giroPrincipal"),
                        vacioAnulo(peticion.nExpediente()),
                        fechaOpcional(peticion.fechaDeExpediente(), "fechaDeExpediente"));

        EmitirLicenciaDeFuncionamiento.LicenciaEmitida emitida;
        try {
            emitida = emitir.emitir(solicitud, formato, observacion);
        } catch (EmitirLicenciaDeFuncionamiento.TitularDesconocido noEsta) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noEsta));
        } catch (EmitirLicenciaDeFuncionamiento.GiroDesconocido noEstaElGiro) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(noEstaElGiro));
        } catch (ComprobacionDelDerecho.DerechoNoPagado sinPagar) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(sinPagar));
        } catch (DerechosDeTramiteParametrizados.DerechoSinParametrizar
                | LectorDeParametros.EjercicioSinSellar sinParametro) {
            // 422 y no 500: la peticion esta bien y el sistema tampoco esta roto. Lo que falta es
            // un dato de configuracion, y quien opera tiene que enterarse de cual para poder
            // pedirlo, en vez de recibir «error interno» y un identificador de incidencia.
            // `EjercicioSinSellar` —que no haya NINGUN conjunto sellado— es el mismo caso y hasta
            // #562 seguia saliendo como 500 con incidencia. Ver la cabecera de la clase.
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(sinParametro);
        } catch (LicenciaRepository.NumeroDuplicado repetido) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(repetido));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(ActoDeLicenciaResource.de(emitida));
    }

    /** Cancela una licencia y emite su resolucion (RF-111). */
    @PostMapping("/funcionamiento/{id}/cancelacion")
    @RequiereAcceso(acceso = ACCESO_CANCELACION, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ActoDeLicenciaResource> cancelacion(
            @PathVariable String id, @RequestBody PeticionDeCancelacion peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        FormatoDeDocumento formato = formatoDe(peticion.formato());
        LocalDate fecha = fechaOhoy(peticion.fecha(), "fecha");

        CancelarLicencia.Cancelacion cancelacion;
        try {
            cancelacion =
                    cancelar.cancelar(
                            id,
                            fecha,
                            peticion.motivo() == null ? "" : peticion.motivo(),
                            formato,
                            observacion);
        } catch (CancelarLicencia.LicenciaInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (CancelarLicencia.YaEstabaCancelada yaEstaba) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(yaEstaba));
        } catch (MovimientoDeLicenciaRepository.LicenciaYaCancelada carrera) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(carrera));
        } catch (CancelarLicencia.SinMotivo | CancelarLicencia.AnteriorALaEmision invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ActoDeLicenciaResource.de(cancelacion));
    }

    /** Autoriza un duplicado y vuelve a sacar la licencia, marcada (RF-111). */
    @PostMapping("/funcionamiento/{id}/duplicado")
    @RequiereAcceso(acceso = ACCESO_DUPLICADO, privilegio = Privilegio.IMPRESION)
    public ResponseEntity<ActoDeLicenciaResource> duplicado(
            @PathVariable String id, @RequestBody PeticionDeDuplicado peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        FormatoDeDocumento formato = formatoDe(peticion.formato());
        LocalDate fecha = fechaOhoy(peticion.fecha(), "fecha");

        DuplicarLicencia.Duplicado duplicado;
        try {
            duplicado =
                    duplicar.duplicar(
                            id,
                            fecha,
                            exigido(peticion.motivo(), "motivo"),
                            exigido(peticion.nDeRecibo(), "nDeRecibo"),
                            formato,
                            observacion);
        } catch (CancelarLicencia.LicenciaInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (DuplicarLicencia.LicenciaCancelada cancelada) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(cancelada));
        } catch (EmitirDocumento.LaReimpresionNoCoincide distinto) {
            // 409 y no 500: la peticion esta bien y el sistema tampoco esta roto en el sentido de
            // un fallo tecnico. Lo que pasa es que el estado actual no admite entregar este papel.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(distinto));
        } catch (EmitirDocumento.DocumentoNoEmitido sinPapel) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(sinPapel));
        } catch (ComprobacionDelDerecho.DerechoNoPagado sinPagar) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(sinPagar));
        } catch (DerechosDeTramiteParametrizados.DerechoSinParametrizar
                | LectorDeParametros.EjercicioSinSellar sinParametro) {
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(sinParametro);
        } catch (DuplicadoDeLicenciaRepository.DuplicadoDuplicado carrera) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(carrera));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(ActoDeLicenciaResource.de(duplicado));
    }

    /**
     * El padron de licencias, con su fecha de corte y su resumen (RF-115).
     *
     * <p><b>La fecha de corte entra en el cuerpo</b> y no sale del reloj (AC 1 de #54, regla 9): el
     * estado de cada fila depende del dia, asi que reimprimir el padron de marzo con su misma fecha
     * tiene que dar el mismo papel. Solo cuando la peticion no la trae se usa la de hoy, y entonces
     * la respuesta la dice.
     */
    @PostMapping("/funcionamiento/reportes/padron")
    @RequiereAcceso(acceso = ACCESO_PADRON, privilegio = Privilegio.IMPRESION)
    public ResponseEntity<PadronDeLicenciasResource> padron(
            @RequestBody PeticionDeReporteDeLicencias peticion) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PadronDeLicenciasResource.de(componerPadron(peticion)));
    }

    /**
     * El mismo padron como documento: hoja de calculo, texto enriquecido o PDF (RF-132).
     *
     * <p>Es la misma ruta y el mismo cuerpo, con {@code ?formato=}. Mismo reparto que {@code
     * ReporteController} hace con la ficha del contribuyente: publicar una ruta aparte para el
     * documento la dejaria sin ninguna pantalla que la llame, porque el prototipo declara una sola
     * por opcion.
     */
    @PostMapping(value = "/funcionamiento/reportes/padron", params = "formato")
    @RequiereAcceso(acceso = ACCESO_PADRON, privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> padronComoDocumento(
            @RequestBody PeticionDeReporteDeLicencias peticion, @RequestParam String formato) {

        FormatoDeDocumento elegido = formatoDe(formato);
        return archivo(
                documentos.generar(
                        ModeloDeLosReportesDeLicencias.delPadron(componerPadron(peticion)),
                        elegido),
                elegido,
                "padron-licencias");
    }

    /** El resumen de licencias por año, con la recaudacion por derecho de tramite (RF-115). */
    @GetMapping("/funcionamiento/reportes/resumen-anual")
    @RequiereAcceso(acceso = ACCESO_RESUMEN, privilegio = Privilegio.IMPRESION)
    public ResumenAnualResource resumenAnual(
            @RequestParam(required = false) @Nullable String desdeElAno,
            @RequestParam(required = false) @Nullable String hastaElAno,
            @RequestParam(required = false) @Nullable String tipoDeLicencia,
            @RequestParam(required = false) @Nullable String aLaFecha) {
        return ResumenAnualResource.de(
                componerResumen(desdeElAno, hastaElAno, tipoDeLicencia, aLaFecha));
    }

    /** El mismo resumen como documento (RF-132). */
    @GetMapping(value = "/funcionamiento/reportes/resumen-anual", params = "formato")
    @RequiereAcceso(acceso = ACCESO_RESUMEN, privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> resumenAnualComoDocumento(
            @RequestParam(required = false) @Nullable String desdeElAno,
            @RequestParam(required = false) @Nullable String hastaElAno,
            @RequestParam(required = false) @Nullable String tipoDeLicencia,
            @RequestParam(required = false) @Nullable String aLaFecha,
            @RequestParam String formato) {

        FormatoDeDocumento elegido = formatoDe(formato);
        return archivo(
                documentos.generar(
                        ModeloDeLosReportesDeLicencias.delResumen(
                                componerResumen(desdeElAno, hastaElAno, tipoDeLicencia, aLaFecha)),
                        elegido),
                elegido,
                "resumen-licencias");
    }

    // ------------------------------------------------------------------

    private ConsultaDeLicencias.Padron componerPadron(PeticionDeReporteDeLicencias peticion) {
        LocalDate corte = fechaOhoy(peticion.aLaFecha(), "aLaFecha");

        CriterioDeLicencias criterio;
        try {
            criterio =
                    new CriterioDeLicencias(
                            numeroDeLicencia(peticion),
                            null,
                            null,
                            vacioAnulo(peticion.direccion()),
                            tipoOpcional(peticion.tipoLic()),
                            vacioAnulo(peticion.ciiu()),
                            fechaOpcional(peticion.fecLicDesde(), "fecLicDesde"),
                            fechaOpcional(peticion.fecLicHasta(), "fecLicHasta"),
                            null);
        } catch (IllegalArgumentException intervalo) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(intervalo));
        }

        ParametrosDePaginacion paginacion =
                new ParametrosDePaginacion(peticion.pagina(), peticion.tamano(), null, null);

        return consulta.padron(
                criterio,
                peticion.nombreDelContribuyente(),
                estadoOpcional(peticion.estado()),
                corte,
                paginacion.aPaginacion(ORDEN_POR_OMISION));
    }

    private ResumenAnualDeLicencias.Resumen componerResumen(
            @Nullable String desdeElAno,
            @Nullable String hastaElAno,
            @Nullable String tipoDeLicencia,
            @Nullable String aLaFecha) {

        LocalDate corte = fechaOhoy(aLaFecha, "aLaFecha");
        Ejercicio hasta = ejercicioOpcional(hastaElAno, "hastaElAno", Ejercicio.de(corte));
        Ejercicio desde = ejercicioOpcional(desdeElAno, "desdeElAno", hasta);

        try {
            return resumen.entre(desde, hasta, tipoOpcional(tipoDeLicencia), corte);
        } catch (ResumenAnualDeLicencias.IntervaloInvalido invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    /**
     * Las dos mitades del numero de licencia que la pantalla teclea, unidas.
     *
     * <p>{@code licencia_funcionamiento} guarda el numero entero, con el formato que compone {@code
     * PlantillaDeNumeroDeLicencia}. Partirlo en la base obligaria a decidir donde esta la frontera,
     * y esa decision es D-09.
     */
    private static @Nullable String numeroDeLicencia(PeticionDeReporteDeLicencias peticion) {
        String serie =
                vacioAnulo(peticion.nLicenciaSerie()) == null ? "" : peticion.nLicenciaSerie();
        String numero =
                vacioAnulo(peticion.nLicenciaNumero()) == null ? "" : peticion.nLicenciaNumero();
        String unido =
                (serie == null ? "" : serie.strip())
                        + (numero == null || numero.isBlank() ? "" : "-" + numero.strip());
        return unido.isBlank() ? null : unido;
    }

    private static @Nullable EstadoDeLicencia estadoOpcional(@Nullable String estado) {
        String texto = estado == null ? "" : estado.strip();
        // «TODAS» es la opcion del desplegable que significa «sin filtro», y llega como texto igual
        // que las otras cuatro. Traducirla a null aqui es lo que evita un quinto estado
        // inexistente.
        if (texto.isEmpty() || "TODAS".equalsIgnoreCase(texto)) {
            return null;
        }
        try {
            return EstadoDeLicencia.valueOf(texto.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El estado va entre VIGENTE, VENCIDA, CANCELADA y TODAS: '" + estado + "'");
        }
    }

    private static @Nullable TipoDeLicencia tipoOpcional(@Nullable String tipo) {
        String texto = tipo == null ? "" : tipo.strip();
        if (texto.isEmpty() || "(TODOS)".equalsIgnoreCase(texto)) {
            return null;
        }
        try {
            return TipoDeLicencia.porNombre(texto);
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El tipo de licencia va entre DEFINITIVA, TEMPORAL y CESIONARIA: '"
                            + tipo
                            + "'");
        }
    }

    private static Ejercicio ejercicioOpcional(
            @Nullable String ano, String campo, Ejercicio porOmision) {
        String texto = ano == null ? "" : ano.strip();
        if (texto.isEmpty()) {
            return porOmision;
        }
        try {
            return new Ejercicio(Integer.parseInt(texto));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' es un año de cuatro cifras: '" + ano + "'");
        }
    }

    private static ResponseEntity<byte[]> archivo(
            byte[] contenido, FormatoDeDocumento formato, String base) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(formato.tipoDeMedio()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(formato.nombreDeArchivo(base))
                                .build()
                                .toString())
                .body(contenido);
    }

    // ------------------------------------------------------------------

    private static Observacion observacionDe(@Nullable String texto) {
        try {
            return Observacion.de(texto == null ? "" : texto);
        } catch (IllegalArgumentException sinObservacion) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Toda modificacion de datos exige la observacion del usuario (regla 10,"
                            + " RNF-052): "
                            + mensajeDe(sinObservacion));
        }
    }

    private static FormatoDeDocumento formatoDe(@Nullable String formato) {
        if (formato == null || formato.isBlank()) {
            return FormatoDeDocumento.PDF;
        }
        try {
            return FormatoDeDocumento.valueOf(formato.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El formato va entre PDF, XLS y RTF: '" + formato + "'");
        }
    }

    private static TipoDeLicencia tipoDe(@Nullable String tipo) {
        String texto = tipo == null ? "" : tipo.strip();
        if (texto.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que decir que tipo de licencia es: DEFINITIVA, TEMPORAL o CESIONARIA");
        }
        try {
            return TipoDeLicencia.porNombre(texto);
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El tipo de licencia va entre DEFINITIVA, TEMPORAL y CESIONARIA: '"
                            + tipo
                            + "'");
        }
    }

    private static AreaM2 areaDe(@Nullable String area) {
        String texto = area == null ? "" : area.strip();
        if (texto.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El area del establecimiento es obligatoria: de ella depende la tasa y el"
                            + " aforo");
        }
        try {
            return new AreaM2(new BigDecimal(texto));
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El area del establecimiento va en metros cuadrados: '" + area + "'");
        }
    }

    private static String exigido(@Nullable String valor, String campo) {
        String texto = valor == null ? "" : valor.strip();
        if (texto.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Falta el campo obligatorio '" + campo + "'");
        }
        return texto;
    }

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    private static @Nullable LocalDate fechaOpcional(@Nullable String texto, String campo) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' va en formato ISO (2026-03-15): '" + texto + "'");
        }
    }

    /**
     * La fecha del acto, o la de hoy.
     *
     * <p>El reloj es el <b>inyectado</b>, no {@code LocalDate.now()} suelto: una prueba que no
     * pueda congelar el dia no puede comprobar nada que dependa de el, y una fila de auditoria
     * fechada con el reloj de la maquina cae en la particion que no es.
     */
    private LocalDate fechaOhoy(@Nullable String texto, String campo) {
        LocalDate fecha = fechaOpcional(texto, campo);
        return fecha == null ? LocalDate.now(reloj) : fecha;
    }

    /**
     * El texto de una excepcion, sin poder ser nulo.
     *
     * <p>{@code getMessage()} es {@code @Nullable} y NullAway lo exige: una respuesta de error con
     * el cuerpo en blanco es peor que una con un texto generico, porque no dice ni siquiera que
     * clase de problema hubo.
     */
    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "La peticion no se pudo completar" : mensaje;
    }
}
