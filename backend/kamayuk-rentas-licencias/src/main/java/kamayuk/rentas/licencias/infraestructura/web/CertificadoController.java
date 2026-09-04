package kamayuk.rentas.licencias.infraestructura.web;

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
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.aplicacion.ComprobacionDelDerecho;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeCertificados;
import kamayuk.rentas.licencias.aplicacion.DerechosDeTramiteParametrizados;
import kamayuk.rentas.licencias.aplicacion.EmitirCertificado;
import kamayuk.rentas.licencias.dominio.CertificadoRepository;
import kamayuk.rentas.licencias.dominio.CriterioDeCertificados;
import kamayuk.rentas.licencias.dominio.ParametrosUrbanisticos;
import kamayuk.rentas.licencias.dominio.TipoDeCertificado;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los certificados de numeracion y zonificacion por HTTP (#54, RF-115, RF-132).
 *
 * <h2>Una opcion del catalogo, tres verbos</h2>
 *
 * <p>{@code certificados} lee la grilla, emite y vuelve a imprimir. La pantalla declara <b>un</b>
 * endpoint —el {@code POST} que emite—, y las otras dos rutas entran por {@code
 * OPERACIONES_ADICIONALES} del generador del contrato, igual que {@code emitir_licencia} (#44) y
 * los cuatro actos de anuncio (#51): una pantalla que lista al abrirse no puede hacerlo con el
 * verbo que consume un correlativo.
 *
 * <h2>Ningun {@code PUT} ni {@code PATCH}</h2>
 *
 * <p>Un certificado no se corrige: se entrega al administrado, que se lo lleva. {@code certificado}
 * no admite {@code UPDATE} desde V51, y uno equivocado se sustituye emitiendo otro.
 *
 * <h2>La cabecera {@code Idempotency-Key}, aqui si se lee</h2>
 *
 * <p>Por el mismo motivo que en la caja (#33) y en los anuncios (#51): esta emision <b>consume un
 * correlativo y entrega un papel</b>. Reenviar la misma peticion devuelve {@code 200} con el
 * certificado de la primera vez en lugar de {@code 201} con otro numero por el mismo derecho
 * pagado. La garantia no es esta lectura, es {@code certificado_idempotencia_uq} (V51).
 *
 * <h2>Que devuelve 422, y por que no 500 (#562)</h2>
 *
 * <p>El concepto del TUPA y los meses de vigencia del certificado salen del <b>conjunto sellado</b>
 * que rige a la fecha de la emision ({@link DerechosDeTramiteParametrizados}, regla 5). La llave
 * que falta dentro del conjunto ya estaba traducida desde #54; que <b>no exista ningun conjunto
 * sellado</b> ({@code EjercicioSinSellar}) salia como <b>500 {@code ERROR_INTERNO} con
 * identificador de incidencia</b>, y con D-02a abierta ese es el estado <i>normal</i> de todas las
 * municipalidades. El razonamiento completo esta en la cabecera de {@link LicenciaController}.
 *
 * <p><b>La reimpresion no lo necesita</b>: {@code EmitirCertificado.reimprimir} no vuelve a pedir
 * el derecho, asi que por esa ruta no se alcanza ninguna de las dos.
 */
@RestController
@RequestMapping(Api.RAIZ + "/licencias/certificados")
public class CertificadoController {

    /** La opcion del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_CERTIFICADOS = "certificados";

    private static final String ORDEN_POR_OMISION = "numero";

    private final ConsultaDeCertificados consulta;
    private final EmitirCertificado emitir;
    private final Clock reloj;

    public CertificadoController(
            ConsultaDeCertificados consulta, EmitirCertificado emitir, Clock reloj) {
        this.consulta = consulta;
        this.emitir = emitir;
        this.reloj = reloj;
    }

    /**
     * La grilla «Certificados emitidos», paginada, con el estado de cada fila derivado a hoy.
     *
     * <p>Con {@code nDeCertificado} devuelve esa sola fila: es la ficha que la pantalla dibuja al
     * abrir un certificado.
     */
    @GetMapping
    @RequiereAcceso(acceso = ACCESO_CERTIFICADOS, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<CertificadoResource> listar(
            @RequestParam(required = false) @Nullable String nDeCertificado,
            @RequestParam(required = false) @Nullable String tipo,
            @RequestParam(required = false) @Nullable String predio,
            @RequestParam(required = false) @Nullable String solicitante,
            ParametrosDePaginacion paginacion) {

        LocalDate hoy = LocalDate.now(reloj);

        if (nDeCertificado != null && !nDeCertificado.isBlank()) {
            return consulta.porNumero(nDeCertificado.strip(), hoy)
                    .map(
                            ficha ->
                                    RespuestaPaginada.de(
                                            Pagina.de(
                                                    List.of(CertificadoResource.de(ficha)),
                                                    paginacion.aPaginacion(ORDEN_POR_OMISION),
                                                    1)))
                    .orElseGet(
                            () ->
                                    RespuestaPaginada.de(
                                            Pagina.vacia(
                                                    paginacion.aPaginacion(ORDEN_POR_OMISION))));
        }

        CriterioDeCertificados criterio =
                new CriterioDeCertificados(
                        null, tipoOpcional(tipo), vacioAnulo(predio), null, null, null);

        return RespuestaPaginada.de(
                consulta.buscar(
                        criterio, solicitante, hoy, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                CertificadoResource::de);
    }

    /**
     * Emite un certificado (RF-115).
     *
     * <p>Responde <b>201</b> con el numero del certificado y el de su papel; <b>200</b> si la
     * cabecera {@code Idempotency-Key} corresponde a uno ya emitido. El {@code 422} de un recibo
     * que no respalda el derecho lleva el motivo exacto: cual de las cuatro condiciones fallo.
     */
    @PostMapping
    @RequiereAcceso(acceso = ACCESO_CERTIFICADOS, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ActoDeCertificadoResource> emitir(
            @RequestBody PeticionDeCertificado peticion,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String clave) {

        Observacion observacion = observacionDe(peticion.observacion());
        FormatoDeDocumento formato = formatoDe(peticion.formato());

        EmitirCertificado.Solicitud solicitud =
                new EmitirCertificado.Solicitud(
                        tipoDe(peticion.tipoDeCertificado()),
                        exigido(peticion.solicitante(), "solicitante"),
                        exigido(peticion.codigoPredial(), "codigoPredial"),
                        vacioAnulo(peticion.nDeExpediente()),
                        fechaOhoy(peticion.fechaDeEmision(), "fechaDeEmision"),
                        exigido(peticion.nDeRecibo(), "nDeRecibo"),
                        new ParametrosUrbanisticos(
                                peticion.zonificacion(),
                                peticion.alturaMaximaPermitida(),
                                peticion.areaLibreMinima(),
                                peticion.retiroMunicipal(),
                                peticion.coeficienteDeEdificacion()));

        EmitirCertificado.Emision emision;
        try {
            emision = emitir.emitir(solicitud, clave, formato, observacion);
        } catch (EmitirCertificado.SolicitanteDesconocido noEsta) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noEsta));
        } catch (EmitirCertificado.PredioAjeno ajeno) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(ajeno));
        } catch (ComprobacionDelDerecho.DerechoNoPagado sinPagar) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(sinPagar));
        } catch (DerechosDeTramiteParametrizados.DerechoSinParametrizar
                | LectorDeParametros.EjercicioSinSellar sinParametro) {
            // 422 y no 500: la peticion esta bien y el sistema tampoco esta roto. Lo que falta es
            // un dato de configuracion —el concepto del TUPA o los meses de vigencia—, y quien
            // opera tiene que enterarse de cual para poder pedirlo. `EjercicioSinSellar` —que no
            // haya NINGUN conjunto sellado— es el mismo caso y hasta #562 salia como 500.
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(sinParametro);
        } catch (CertificadoRepository.ClaveRepetida carrera) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(carrera));
        } catch (CertificadoRepository.NumeroDuplicado repetido) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(repetido));
        }

        return ResponseEntity.status(emision.yaExistia() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ActoDeCertificadoResource.de(emision));
    }

    /**
     * Vuelve a sacar un certificado ya emitido, con su numero original (AC 2 de #54, RF-132).
     *
     * <p>Devuelve <b>el archivo</b>, en el formato que se pida. El contenido sale de los datos
     * guardados el dia de la emision, no de lo que hoy digan el padron o el TUPA, y {@code
     * EmitirDocumento} comprueba el SHA-256 antes de entregarlo: si dibujar esos datos ya no da los
     * mismos bytes, la reimpresion <b>falla</b> en lugar de entregar un papel distinto al original
     * con el mismo numero.
     */
    @PostMapping("/{numero}/impresion")
    @RequiereAcceso(acceso = ACCESO_CERTIFICADOS, privilegio = Privilegio.IMPRESION)
    public ResponseEntity<byte[]> impresion(
            @PathVariable String numero, @RequestBody PeticionDeImpresion peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        FormatoDeDocumento formato = formatoDe(peticion.formato());

        EmitirCertificado.Emision emision;
        try {
            emision = emitir.reimprimir(numero, formato, observacion);
        } catch (EmitirCertificado.CertificadoInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (EmitirDocumento.DocumentoNoEmitido sinPapel) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(sinPapel));
        } catch (EmitirDocumento.LaReimpresionNoCoincide distinto) {
            // 409 y no 500: la peticion esta bien y el sistema tampoco esta roto en el sentido de
            // un fallo tecnico. Lo que pasa es que el estado actual no admite entregar este papel.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(distinto));
        }

        EmitirDocumento.Emision papel =
                java.util.Objects.requireNonNull(
                        emision.documento(), "Una reimpresion siempre trae su papel");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(formato.tipoDeMedio()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(papel.nombreDeArchivo())
                                .build()
                                .toString())
                .body(papel.contenido());
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

    private static TipoDeCertificado tipoDe(@Nullable String tipo) {
        String texto = tipo == null ? "" : tipo.strip();
        if (texto.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que decir que se certifica: NUMERACION, ZONIFICACION_VIAS,"
                            + " PARAMETROS_URBANISTICOS o JURISDICCION");
        }
        try {
            return TipoDeCertificado.porNombre(texto);
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El tipo de certificado va entre NUMERACION, ZONIFICACION_VIAS,"
                            + " PARAMETROS_URBANISTICOS y JURISDICCION: '"
                            + tipo
                            + "'");
        }
    }

    private static @Nullable TipoDeCertificado tipoOpcional(@Nullable String tipo) {
        String texto = tipo == null ? "" : tipo.strip();
        // «Todos» es la opcion del desplegable que significa «sin filtro».
        if (texto.isEmpty() || "TODOS".equalsIgnoreCase(texto)) {
            return null;
        }
        return tipoDe(texto);
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

    /**
     * La fecha del acto, o la de hoy.
     *
     * <p>El reloj es el <b>inyectado</b>, no {@code LocalDate.now()} suelto: una prueba que no
     * pueda congelar el dia no puede comprobar nada que dependa de el, y una fila de auditoria
     * fechada con el reloj de la maquina cae en la particion que no es.
     */
    private LocalDate fechaOhoy(@Nullable String texto, String campo) {
        if (texto == null || texto.isBlank()) {
            return LocalDate.now(reloj);
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
