package kamayuk.rentas.coactiva.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.coactiva.aplicacion.CambiarEstadoDelExpediente;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDelProcesoCoactivo;
import kamayuk.rentas.coactiva.aplicacion.NotificarActoCoactivo;
import kamayuk.rentas.coactiva.aplicacion.PlazosCoactivosParametrizados;
import kamayuk.rentas.coactiva.aplicacion.RegistrarActoCoactivo;
import kamayuk.rentas.coactiva.aplicacion.ReimprimirActoCoactivo;
import kamayuk.rentas.coactiva.dominio.ActoCoactivoRepository;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;
import kamayuk.rentas.coactiva.dominio.TipoDeMedidaCautelar;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.FiltroDeLaConsulta;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los actos del procedimiento coactivo por HTTP: la REC, el seguimiento, los actos y sus
 * notificaciones (RF-101, RF-102, RF-103).
 *
 * <h2>Cuatro opciones del catalogo, cuatro accesos</h2>
 *
 * <p>Cada endpoint declara el suyo: {@code rec_impresion} y {@code actos_coactivos} escriben
 * —privilegio de registro—, {@code notificaciones_coactivas} tambien, y {@code proceso_coactivo}
 * solo lee. Sin {@code @RequiereAcceso} el guardia <b>niega</b>, y la regla de arquitectura rompe
 * el build; las dos cosas juntas hacen que el olvido no se pueda convertir en una puerta abierta.
 *
 * <h2>Ningun {@code PUT} ni {@code PATCH}</h2>
 *
 * <p>Un acto coactivo se notifica al obligado, que se lleva el papel. No se corrige: se deja sin
 * efecto con otro acto. El verbo lo dice, y ademas {@code acto_coactivo} no admite {@code UPDATE}
 * desde V34.
 *
 * <h2>Los bytes no viajan en el JSON</h2>
 *
 * <p>El contrato declara {@code application/json} para las cuatro opciones. Lo que la respuesta
 * lleva del documento es su numero, su formato, su resumen SHA-256 y su tamanio; la descarga es
 * otra peticion. Meter un PDF en base64 dentro de un JSON lo hincha un tercio, y una corrida de REC
 * puede ser de todo un padron.
 *
 * <h2>Que devuelve 422, y por que no 500 (#562)</h2>
 *
 * <p>El plazo de cumplimiento de la REC-1 sale del <b>conjunto sellado</b> que rige a la fecha del
 * acto o de la diligencia ({@link PlazosCoactivosParametrizados}, regla 5). Que el conjunto exista
 * y no traiga la llave ({@code PlazoSinParametrizar}) ya estaba traducido desde #41; que <b>no
 * exista ningun conjunto sellado</b> ({@code EjercicioSinSellar}) no lo estaba, y con D-02a abierta
 * ese es el estado <i>normal</i> de todas las municipalidades: caia en el
 * {@code @ExceptionHandler(Exception.class)} de {@code ManejadorDeErrores} y salia como <b>500
 * {@code ERROR_INTERNO} con identificador de incidencia</b>. Con eso, dictar una REC-1 o
 * notificarla era inalcanzable, y cada intento dejaba una incidencia de nivel ERROR en el registro
 * del servidor: el registro de errores lleno de lo que no es un error.
 *
 * <p><b>En {@link #emitirRec} el 422 no sale como codigo de la respuesta, y es a proposito</b>: esa
 * ruta emite un lote, y su contrato ya dice que lo que el dominio sabe explicar se devuelve como
 * expediente <i>rechazado</i> con su motivo. Con la traduccion puesta, el informe nombra el
 * ejercicio sin sellar expediente por expediente; sin ella, la corrida entera moria en un 500. Lo
 * que el issue exige —que deje de ser un fallo del servidor y deje de dejar incidencia— se cumple
 * igual, y ademas se dice cual de los veinte fallo.
 *
 * <p>Un fallo de verdad del servidor sigue siendo 500 con su incidencia: la lista de arriba nombra
 * las excepciones una a una y no captura {@code RuntimeException}. El razonamiento completo, con
 * por que la traduccion no vive en {@code ManejadorDeErrores}, esta en {@code
 * tesoreria.ConvenioController} (#547).
 */
@RestController
@RequestMapping(Api.RAIZ + "/coactiva")
public class ActoCoactivoController {

    /** Las cuatro opciones del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_REC = "rec_impresion";

    static final String ACCESO_PROCESO = "proceso_coactivo";

    static final String ACCESO_ACTOS = "actos_coactivos";

    static final String ACCESO_NOTIFICACIONES = "notificaciones_coactivas";

    private final RegistrarActoCoactivo registrar;
    private final NotificarActoCoactivo notificar;
    private final ReimprimirActoCoactivo reimprimir;
    private final ConsultaDelProcesoCoactivo proceso;
    private final DirectorioDeContribuyentes contribuyentes;
    private final Clock reloj;

    public ActoCoactivoController(
            RegistrarActoCoactivo registrar,
            NotificarActoCoactivo notificar,
            ReimprimirActoCoactivo reimprimir,
            ConsultaDelProcesoCoactivo proceso,
            DirectorioDeContribuyentes contribuyentes,
            Clock reloj) {
        this.registrar = registrar;
        this.notificar = notificar;
        this.reimprimir = reimprimir;
        this.proceso = proceso;
        this.contribuyentes = contribuyentes;
        this.reloj = reloj;
    }

    /**
     * Emite —o reimprime— la REC de los expedientes marcados (RF-101).
     *
     * <p>Responde <b>201</b> cuando alguna salio y <b>200</b> cuando ninguna: la peticion estaba
     * bien formada y el informe explica, expediente por expediente, por que no. Un 422 sin detalle
     * dejaria a quien opera adivinando cual de los veinte fallo.
     *
     * <p><b>{@code proyectarInteresAl} tambien viaja por la consulta</b> (#425). Es el filtro
     * «Proyectar interes al» de la pantalla, el contrato lo declara {@code in: query}, y decide la
     * cifra que se imprime en el papel que el obligado se lleva (regla 9): leerlo solo del cuerpo
     * dejaba a la pantalla emitiendo la REC con la deuda de hoy en vez de la del dia elegido. Se
     * sigue aceptando en el cuerpo, y ahi gana: ver {@link FiltroDeLaConsulta}.
     *
     * @param proyectarInteresAl a que dia se proyecta la deuda que se imprime; si falta, la fecha
     *     del acto
     */
    @PostMapping("/rec/impresion")
    @RequiereAcceso(acceso = ACCESO_REC, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ImpresionDeRecResource> emitirRec(
            @RequestParam(required = false) @Nullable String proyectarInteresAl,
            @RequestBody PeticionDeRec peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        FormatoDeDocumento formato = formatoDe(peticion.formato());
        TipoDeActoCoactivo tipo = recDe(peticion.rec());
        LocalDate fecha = fechaOpcional(peticion.fecha(), "fecha", LocalDate.now(reloj));
        LocalDate proyeccion =
                fechaOpcional(
                        FiltroDeLaConsulta.primeroNoVacio(
                                peticion.proyectarInteresAl(), proyectarInteresAl),
                        "proyectarInteresAl",
                        fecha);
        List<String> expedientes =
                peticion.expedientes() == null ? List.of() : peticion.expedientes();
        if (expedientes.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "No se marco ningun expediente: la pantalla lista los pendientes de pago y"
                            + " quien opera elige cuales imprimir");
        }
        boolean soloReimprimir = Boolean.TRUE.equals(peticion.reimprimir());
        TipoDeMedidaCautelar medida = medidaOpcional(peticion.medida());
        String glosa = vacioAnulo(peticion.glosa());

        List<ImpresionDeRecResource.RecEmitidaResource> emitidas = new ArrayList<>();
        List<ImpresionDeRecResource.RecRechazadaResource> rechazadas = new ArrayList<>();

        for (String numero : expedientes) {
            String limpio = numero == null ? "" : numero.strip();
            if (limpio.isEmpty()) {
                continue;
            }
            try {
                emitidas.add(
                        conErroresTraducidos(
                                () ->
                                        soloReimprimir
                                                ? reimpresionDe(limpio, tipo, formato, observacion)
                                                : emisionDe(
                                                        limpio,
                                                        tipo,
                                                        medida,
                                                        glosa,
                                                        fecha,
                                                        proyeccion,
                                                        formato,
                                                        observacion)));
            } catch (ProblemaDeNegocio explicado) {
                // Solo se convierte en «rechazada» lo que el dominio sabe explicar. Un fallo que
                // el traductor no reconoce sube tal cual, para que el manejador central lo
                // convierta en 500 sin filtrar el esquema: un mensaje de PostgreSQL copiado en un
                // informe de la pantalla diria la tabla y la restriccion.
                rechazadas.add(
                        new ImpresionDeRecResource.RecRechazadaResource(
                                limpio, motivoDe(explicado)));
            }
        }

        ImpresionDeRecResource informe = new ImpresionDeRecResource(emitidas, rechazadas);
        return informe.emitioAlguna()
                ? ResponseEntity.status(HttpStatus.CREATED).body(informe)
                : ResponseEntity.ok(informe);
    }

    /**
     * El seguimiento de un expediente: sus datos, sus actuaciones y su deuda a la fecha (RF-101).
     *
     * @param proyectarInteresAl a que dia se actualiza la deuda (regla 9); si falta, hoy
     */
    @GetMapping("/expedientes/{numero}/proceso")
    @RequiereAcceso(acceso = ACCESO_PROCESO, privilegio = Privilegio.LECTURA)
    public ProcesoResource verProceso(
            @PathVariable String numero,
            @RequestParam(required = false) @Nullable String proyectarInteresAl) {

        LocalDate aLaFecha =
                fechaOpcional(proyectarInteresAl, "proyectarInteresAl", LocalDate.now(reloj));
        ConsultaDelProcesoCoactivo.ProcesoCoactivo encontrado =
                proceso.porNumero(numero, aLaFecha)
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No hay ningun expediente coactivo con el numero '"
                                                        + numero
                                                        + "'"));
        return ProcesoResource.de(
                encontrado, codigoDe(encontrado.ficha().expediente().contribuyenteId()));
    }

    /** Dicta un acto sobre el expediente y emite su documento (RF-102). */
    @PostMapping("/expedientes/{numero}/actos")
    @RequiereAcceso(acceso = ACCESO_ACTOS, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ActoDictadoResource> registrarActo(
            @PathVariable String numero, @RequestBody PeticionDeActoCoactivo peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        FormatoDeDocumento formato = formatoDe(peticion.formato());
        TipoDeActoCoactivo tipo = tipoDe(peticion.tipo());
        LocalDate fecha = fechaOpcional(peticion.fecha(), "fecha", LocalDate.now(reloj));

        String glosa = exigir(peticion.glosa(), "glosa");
        TipoDeMedidaCautelar medida = medidaOpcional(peticion.medida());
        RegistrarActoCoactivo.ActoDictado dictado =
                conErroresTraducidos(
                        () ->
                                registrar.dictar(
                                        new RegistrarActoCoactivo.Peticion(
                                                numero, tipo, fecha, glosa, medida, null),
                                        formato,
                                        observacion));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        new ActoDictadoResource(
                                numero,
                                ActoResource.de(dictado.acto()),
                                DocumentoDelActoResource.de(dictado.emision(), formato),
                                dictado.estado().etiqueta(),
                                dictado.deuda().total().valor().toPlainString(),
                                dictado.deuda().actualizadaA()));
    }

    /** Registra la diligencia de notificacion de un acto coactivo (RF-103). */
    @PostMapping("/notificaciones")
    @RequiereAcceso(acceso = ACCESO_NOTIFICACIONES, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ActoResource> notificarActo(
            @RequestBody PeticionDeNotificacionCoactiva peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        LocalDate fecha = fechaOpcional(peticion.fecha(), "fecha", LocalDate.now(reloj));
        String acto = exigir(peticion.acto(), "acto");

        ModalidadDeNotificacion modalidad = modalidadDe(peticion.modalidad());
        ResultadoDeNotificacion resultado = resultadoDe(peticion.resultado());
        String notificador = exigir(peticion.notificador(), "notificador");
        NotificarActoCoactivo.Diligencia diligencia =
                conErroresTraducidos(
                        () ->
                                notificar.registrar(
                                        acto,
                                        fecha,
                                        modalidad,
                                        resultado,
                                        notificador,
                                        vacioAnulo(peticion.domicilio()),
                                        vacioAnulo(peticion.receptor()),
                                        vacioAnulo(peticion.documentoReceptor()),
                                        vacioAnulo(peticion.vinculo()),
                                        vacioAnulo(peticion.acuse()),
                                        observacion));

        // Se devuelve el acto con TODAS sus diligencias, no solo la que se acaba de registrar: que
        // se intento antes y no se hallo al obligado es parte del expediente, y es lo que sostiene
        // una notificacion por cedulon.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(
                        ActoResource.de(
                                proceso.actuacionesDe(diligencia.acto().expedienteId()).stream()
                                        .filter(
                                                actuacion ->
                                                        actuacion
                                                                .acto()
                                                                .numero()
                                                                .equals(diligencia.acto().numero()))
                                        .findFirst()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "El acto recien notificado no"
                                                                        + " aparece en su"
                                                                        + " expediente"))));
    }

    // ------------------------------------------------------------------

    private ImpresionDeRecResource.RecEmitidaResource emisionDe(
            String expediente,
            TipoDeActoCoactivo tipo,
            @Nullable TipoDeMedidaCautelar medida,
            @Nullable String glosa,
            LocalDate fecha,
            LocalDate proyeccion,
            FormatoDeDocumento formato,
            Observacion observacion) {

        RegistrarActoCoactivo.ActoDictado dictado =
                registrar.dictar(
                        new RegistrarActoCoactivo.Peticion(
                                expediente,
                                tipo,
                                fecha,
                                glosa == null ? tipo.titulo() : glosa,
                                medida,
                                proyeccion),
                        formato,
                        observacion);
        return new ImpresionDeRecResource.RecEmitidaResource(
                expediente,
                ActoResource.de(dictado.acto()),
                DocumentoDelActoResource.de(dictado.emision(), formato),
                dictado.estado().etiqueta());
    }

    private ImpresionDeRecResource.RecEmitidaResource reimpresionDe(
            String expediente,
            TipoDeActoCoactivo tipo,
            FormatoDeDocumento formato,
            Observacion observacion) {

        ReimprimirActoCoactivo.Reimpresion reimpresion =
                reimprimir.delExpediente(expediente, tipo, formato, observacion);
        return new ImpresionDeRecResource.RecEmitidaResource(
                expediente,
                ActoResource.de(reimpresion.acto()),
                DocumentoDelActoResource.de(reimpresion.emision(), formato),
                null);
    }

    /**
     * Ejecuta la operacion traduciendo las reglas del dominio a codigos HTTP.
     *
     * <p>La division no es cosmetica: <b>422</b> es «la peticion no cumple una regla de
     * validacion», <b>409</b> es «la peticion esta bien formada y lo que no la admite es el estado
     * actual del procedimiento», y <b>404</b> es «eso no existe». Quien opera hace cosas distintas
     * con cada una: corregir el formulario, esperar o buscar bien.
     *
     * <p><b>Las excepciones se nombran una a una</b>, y no se captura {@code RuntimeException}.
     * Capturarla convertiria en un mensaje bonito cualquier fallo inesperado —incluido uno de
     * PostgreSQL, con su tabla y su restriccion dentro—; lo que no esta en estas listas sube al
     * manejador central, que responde 500 sin detalle.
     */
    private static <T> T conErroresTraducidos(Supplier<T> operacion) {
        try {
            return operacion.get();
        } catch (ProblemaDeNegocio yaTraducido) {
            throw yaTraducido;
        } catch (CambiarEstadoDelExpediente.ExpedienteInexistente
                | NotificarActoCoactivo.ActoInexistente
                | ReimprimirActoCoactivo.ActoSinDictar
                | EmitirDocumento.DocumentoNoEmitido noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, motivoDe(noExiste));
        } catch (CambiarEstadoDelExpediente.ExpedienteConcluido
                | RegistrarActoCoactivo.DeudaExtinguida
                | RegistrarActoCoactivo.Rec1SinDictar
                | RegistrarActoCoactivo.Rec1SinNotificar
                | RegistrarActoCoactivo.PlazoDeLaRec1EnCurso
                | ActoCoactivoRepository.Rec1Duplicada
                | NotificarActoCoactivo.DiligenciaAnteriorAlActo
                | EmitirDocumento.LaReimpresionNoCoincide enConflicto) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, motivoDe(enConflicto));
        } catch (PlazosCoactivosParametrizados.PlazoSinParametrizar
                | LectorDeParametros.EjercicioSinSellar falta) {
            // `EjercicioSinSellar` no es un fallo del servidor: es que nadie ha sellado todavia el
            // conjunto del ejercicio del acto (D-02a). Ver la cabecera de la clase (#562).
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
        } catch (NotificarActoCoactivo.SinDireccion | IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, motivoDe(invalido));
        }
    }

    private String codigoDe(long contribuyenteId) {
        return Optional.ofNullable(
                        contribuyentes.porIds(Set.of(contribuyenteId)).get(contribuyenteId))
                .map(ResumenDeContribuyente::codigo)
                .orElse(String.valueOf(contribuyenteId));
    }

    private static TipoDeActoCoactivo recDe(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        if (valor == null) {
            return TipoDeActoCoactivo.REC1;
        }
        // «REC 1», «REC-1» y «REC 2» son como la pantalla nombra sus botones.
        String normalizado = valor.toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "");
        if ("REC1".equals(normalizado) || "CARATULA".equals(normalizado)) {
            return TipoDeActoCoactivo.REC1;
        }
        if ("REC2".equals(normalizado)) {
            return TipoDeActoCoactivo.REC2;
        }
        throw new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                "La pantalla de impresion emite la REC 1 o la REC 2; llego '" + texto + "'");
    }

    private static TipoDeActoCoactivo tipoDe(@Nullable String texto) {
        String valor = exigir(texto, "tipo");
        try {
            return TipoDeActoCoactivo.porNombre(valor);
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, motivoDe(desconocido));
        }
    }

    private static @Nullable TipoDeMedidaCautelar medidaOpcional(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        if (valor == null) {
            return null;
        }
        try {
            return TipoDeMedidaCautelar.porNombre(valor);
        } catch (IllegalArgumentException desconocida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, motivoDe(desconocida));
        }
    }

    private static ModalidadDeNotificacion modalidadDe(@Nullable String texto) {
        String valor = exigir(texto, "modalidad");
        try {
            return ModalidadDeNotificacion.valueOf(valor.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Modalidad de notificacion desconocida: '"
                            + texto
                            + "'. Se admite PERSONAL, CEDULON, PUBLICACION, CORREO o NEGATIVA"
                            + " (art. 104 del TUO del Codigo Tributario)");
        }
    }

    private static ResultadoDeNotificacion resultadoDe(@Nullable String texto) {
        String valor = exigir(texto, "resultado");
        try {
            return ResultadoDeNotificacion.valueOf(valor.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Resultado de notificacion desconocido: '"
                            + texto
                            + "'. Se admite NOTIFICADO, NO_UBICADO o RECHAZADO; un acuse"
                            + " pendiente no es una diligencia, es una diligencia que todavia no"
                            + " ocurrio");
        }
    }

    private static FormatoDeDocumento formatoDe(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        if (valor == null) {
            return FormatoDeDocumento.PDF;
        }
        try {
            return FormatoDeDocumento.valueOf(valor.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Formato desconocido: '" + texto + "'. Se admite PDF, XLS o RTF");
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
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, motivoDe(invalida));
        }
    }

    private static String exigir(@Nullable String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return valor.strip();
    }

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    private static String motivoDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "La operacion no se pudo completar" : mensaje;
    }

    /**
     * El acto recien dictado, con su papel y con la deuda que lo motivo.
     *
     * @param expediente el numero del expediente
     * @param acto el acto registrado
     * @param documento el papel emitido
     * @param estadoDelExpediente en que estado queda el expediente despues del acto
     * @param deudaTotal cuanto se debia
     * @param deudaAlDia a que dia esta {@code deudaTotal} (regla 9, RNF-075)
     */
    public record ActoDictadoResource(
            String expediente,
            ActoResource acto,
            DocumentoDelActoResource documento,
            String estadoDelExpediente,
            String deudaTotal,
            LocalDate deudaAlDia) {}
}
