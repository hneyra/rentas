package kamayuk.rentas.coactiva.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.coactiva.aplicacion.CambiarDireccionReferencial;
import kamayuk.rentas.coactiva.aplicacion.CambiarEstadoDelExpediente;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeExpedientes;
import kamayuk.rentas.coactiva.aplicacion.ImportarValoresACoactiva;
import kamayuk.rentas.coactiva.dominio.CriterioDeExpedientes;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.InformeDeImportacion;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.PlantillaDeNumeroDeExpediente;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.ValoresEnCoactiva;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * El expediente coactivo por HTTP: consulta, importacion de valores, cambio de estado y cambio de
 * direccion referencial (RF-100, RF-106).
 *
 * <h2>Por que aqui si hay {@code PATCH}</h2>
 *
 * <p>{@code ValoresController} y {@code ConvenioController} no tienen ninguno, porque un valor y un
 * convenio no se corrigen. Este si, y no es una excepcion a la regla 4: <b>las dos rutas que lo
 * usan no actualizan ninguna fila</b>. Las dos <b>insertan</b> un movimiento en {@code
 * expediente_movimiento} —la tabla del expediente ni siquiera admite {@code UPDATE} desde V33— y lo
 * que cambia es lo que se <b>deriva</b> de ese historial. El verbo es el que el prototipo declara
 * para esas dos opciones, y describe bien lo que el cliente ve: una modificacion parcial del
 * recurso.
 *
 * <h2>El numero, en la ruta</h2>
 *
 * <p>{@code {numero}} es el numero <b>impreso</b> del expediente, tal como esta en su caratula. Ni
 * el identificador interno ni el ejercicio y el correlativo por separado.
 */
@RestController
@RequestMapping(Api.RAIZ + "/coactiva")
public class ExpedienteController {

    /** Las cuatro opciones del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_EXPEDIENTES = "coactiva_expedientes";

    static final String ACCESO_IMPORTACION = "importacion_valores";

    static final String ACCESO_HISTORIAL = "expediente_historial";

    static final String ACCESO_DIRECCION = "cambiar_direccion_ref";

    /**
     * La lectura por obligacion la pide la pantalla que fracciona (#426).
     *
     * <p>Su acceso es el de <b>esa</b> opcion y no el de {@code coactiva_consulta_deudas}: quien
     * fracciona tiene que poder ver las filas que va a acoger sin necesitar ademas el permiso de
     * otra pantalla, y quien solo consulta deudas no necesita esta granularidad: la suya la publica
     * {@code DeudaCoactivaController}, por expediente.
     */
    static final String ACCESO_FRACCIONAMIENTO = "fraccionamiento_coactivo";

    private static final String ORDEN_POR_OMISION = "numero";

    private final ImportarValoresACoactiva importar;
    private final CambiarEstadoDelExpediente cambiarEstado;
    private final CambiarDireccionReferencial cambiarDireccion;
    private final ConsultaDeExpedientes consulta;
    private final DirectorioDeContribuyentes contribuyentes;
    private final Clock reloj;

    public ExpedienteController(
            ImportarValoresACoactiva importar,
            CambiarEstadoDelExpediente cambiarEstado,
            CambiarDireccionReferencial cambiarDireccion,
            ConsultaDeExpedientes consulta,
            DirectorioDeContribuyentes contribuyentes,
            Clock reloj) {
        this.importar = importar;
        this.cambiarEstado = cambiarEstado;
        this.cambiarDireccion = cambiarDireccion;
        this.consulta = consulta;
        this.contribuyentes = contribuyentes;
        this.reloj = reloj;
    }

    /**
     * La grilla de expedientes coactivos, paginada, con la deuda de cada uno actualizada a hoy
     * (RF-100).
     *
     * <p>Con {@code nroDeExpediente}, la fila trae ademas sus valores y su historial completo: es
     * la ficha que la pantalla dibuja al abrir un expediente. Sin el, la fila es la que la grilla
     * pinta y nada mas —una pagina de veinte no puede costar veinte lecturas de detalle—.
     */
    @GetMapping("/expedientes")
    @RequiereAcceso(acceso = ACCESO_EXPEDIENTES, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<ExpedienteResource> listar(
            @RequestParam(required = false) @Nullable String nroDeExpediente,
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String ejecutor,
            @RequestParam(required = false) @Nullable String estado,
            ParametrosDePaginacion paginacion) {

        LocalDate hoy = LocalDate.now(reloj);
        CriterioDeExpedientes criterio =
                new CriterioDeExpedientes(
                        vacioAnulo(nroDeExpediente),
                        contribuyenteOpcional(codContribuyente),
                        vacioAnulo(ejecutor),
                        estadoOpcional(estado),
                        null);

        Pagina<ConsultaDeExpedientes.ExpedienteConDeuda> pagina =
                consulta.buscar(criterio, hoy, paginacion.aPaginacion(ORDEN_POR_OMISION));

        Map<Long, ResumenDeContribuyente> padron = padronDe(pagina);

        if (criterio.numero() != null && pagina.contenido().size() == 1) {
            ExpedienteCoactivo unico = pagina.contenido().get(0).fila().expediente();
            Optional<ConsultaDeExpedientes.FichaDelExpediente> ficha =
                    consulta.porNumero(unico.numero(), hoy);
            if (ficha.isPresent()) {
                return RespuestaPaginada.de(
                        pagina,
                        fila ->
                                ExpedienteResource.de(
                                        ficha.get(), codigoDe(padron, unico.contribuyenteId())));
            }
        }

        return RespuestaPaginada.de(
                pagina,
                fila ->
                        ExpedienteResource.de(
                                fila,
                                codigoDe(padron, fila.fila().expediente().contribuyenteId())));
    }

    /**
     * La deuda del expediente <b>obligación por obligación</b>, a la fecha que se pida (#426).
     *
     * <p><b>El hueco que cierra.</b> {@code POST /coactiva/convenios} exige {@code obligaciones[]}
     * con {@code tributo}, {@code ejercicio} y {@code predioId}/{@code vehiculoId} por fila, y este
     * módulo no publicaba ninguna lectura con esa granularidad: {@code DeudaCoactivaResource} es
     * por expediente y ni siquiera desglosa insoluto de interés. Sin esto, la columna de selección
     * de {@code fraccionamiento_coactivo} no tenía sobre qué actuar — exactamente como estaba
     * {@code baja_deuda} antes de #332.
     *
     * <p>{@code fechaDeCalculo} decide a qué día se actualizan <b>todas</b> las cifras, y viaja de
     * vuelta en la respuesta: sin él, quien la lea mañana leería otra cosa bajo la misma etiqueta
     * (regla 9). Es el mismo parámetro que el fraccionamiento llama {@code fechaDeCorte}, y por eso
     * la pantalla puede pedir la grilla a la misma fecha con la que va a acoger.
     */
    @GetMapping("/expedientes/{numero}/deuda")
    @RequiereAcceso(acceso = ACCESO_FRACCIONAMIENTO, privilegio = Privilegio.LECTURA)
    public DeudaPorObligacionResource deudaDelExpediente(
            @PathVariable String numero,
            @RequestParam(required = false) @Nullable String fechaDeCalculo) {

        LocalDate aLaFecha = fechaOpcional(fechaDeCalculo, "fechaDeCalculo", LocalDate.now(reloj));
        ConsultaDeExpedientes.DeudaPorObligacion deuda =
                consulta.obligacionesDe(numero, aLaFecha)
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No hay ningun expediente coactivo con el numero '"
                                                        + numero
                                                        + "'"));

        long contribuyenteId = deuda.expediente().contribuyenteId();
        ResumenDeContribuyente delPadron =
                contribuyentes.porIds(Set.of(contribuyenteId)).get(contribuyenteId);
        return DeudaPorObligacionResource.de(
                deuda,
                delPadron == null ? String.valueOf(contribuyenteId) : delPadron.codigo(),
                delPadron == null ? "" : delPadron.nombre());
    }

    /**
     * Importa a coactiva los valores exigibles del contribuyente y abre su expediente (RF-100).
     *
     * <p>Responde <b>201</b> con el expediente cuando algo entro, y <b>200</b> con el informe
     * cuando no entro nada: la peticion estaba bien formada y el informe explica valor por valor
     * por que se rechazo cada uno. Un 422 sin detalle dejaria a quien opera adivinando.
     */
    @PostMapping("/expedientes/importacion")
    @RequiereAcceso(acceso = ACCESO_IMPORTACION, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ImportacionResource> importarValores(
            @RequestBody PeticionDeImportacion peticion) {

        ResumenDeContribuyente contribuyente = contribuyenteDe(peticion.codContribuyente());
        Observacion observacion = observacionDe(peticion.observacion());
        LocalDate fecha = fechaOpcional(peticion.fecha(), "fecha", LocalDate.now(reloj));

        ImportarValoresACoactiva.Peticion pedido;
        try {
            pedido =
                    new ImportarValoresACoactiva.Peticion(
                            contribuyente.id(),
                            peticion.valores() == null ? List.of() : peticion.valores(),
                            exigir(peticion.ejecutor(), "ejecutor"),
                            vacioAnulo(peticion.auxiliar()),
                            vacioAnulo(peticion.asunto()),
                            vacioAnulo(peticion.direccionReferencialDelContribuyente()));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        InformeDeImportacion informe;
        try {
            informe =
                    importar.importar(
                            pedido, fecha, PlantillaDeNumeroDeExpediente.POR_OMISION, observacion);
        } catch (ExpedienteRepository.ValorYaEnUnExpediente
                | MovimientoDelExpedienteRepository.AperturaDuplicada enConflicto) {
            // 409: la peticion esta bien formada; lo que no admite la operacion es el estado
            // actual del valor. Pasa cuando dos importaciones simultaneas piden el mismo valor:
            // la que pierde se deshace entera, y reintentar da el rechazo explicado.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(enConflicto));
        } catch (ImportarValoresACoactiva.SinValoresPedidos
                | ValoresEnCoactiva.SinPaseACoactiva
                | IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        if (!informe.abrioExpediente()) {
            return ResponseEntity.ok(ImportacionResource.de(informe, null));
        }

        ExpedienteResource abierto =
                ExpedienteResource.de(
                        consulta.porNumero(informe.expedienteAbierto().numero(), fecha)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "El expediente recien abierto no se"
                                                                + " encuentra")),
                        contribuyente.codigo());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ImportacionResource.de(informe, abierto));
    }

    /**
     * Cambia el estado del expediente conservando su historial (RF-100).
     *
     * <p>No actualiza ninguna fila: <b>agrega</b> un movimiento. El estado que devuelve es el que
     * se deriva del historial ya con el movimiento nuevo dentro.
     */
    @PatchMapping("/expedientes/{numero}/estados")
    @RequiereAcceso(acceso = ACCESO_HISTORIAL, privilegio = Privilegio.MODIFICACION)
    public ExpedienteResource cambiarEstado(
            @PathVariable String numero, @RequestBody PeticionDeEstadoDelExpediente peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        LocalDate hoy = LocalDate.now(reloj);
        EstadoDelExpediente nuevo = estadoDe(peticion.nuevoEstado());

        try {
            cambiarEstado.cambiar(
                    numero,
                    nuevo,
                    fechaOpcional(peticion.fecha(), "fecha", hoy),
                    exigir(peticion.motivo(), "motivo"),
                    fechaNula(peticion.documentoDeRespaldoFecha(), "documentoDeRespaldoFecha"),
                    vacioAnulo(peticion.documentoDeRespaldoNumero()),
                    observacion);
        } catch (CambiarEstadoDelExpediente.ExpedienteInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (CambiarEstadoDelExpediente.ExpedienteConcluido
                | CambiarEstadoDelExpediente.SinCambio enConflicto) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(enConflicto));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        return fichaDe(numero, hoy);
    }

    /**
     * Reemplaza la direccion referencial del expediente, dejando traza (RF-106).
     *
     * <p>Tampoco actualiza ninguna fila: agrega un movimiento con su motivo y su observacion. La
     * direccion con la que se abrio el expediente se conserva, porque es la que explica a donde
     * fueron sus primeras notificaciones.
     */
    @PatchMapping("/expedientes/{numero}/direccion-referencial")
    @RequiereAcceso(acceso = ACCESO_DIRECCION, privilegio = Privilegio.MODIFICACION)
    public ExpedienteResource cambiarDireccion(
            @PathVariable String numero, @RequestBody PeticionDeDireccionReferencial peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        LocalDate hoy = LocalDate.now(reloj);

        try {
            cambiarDireccion.cambiar(
                    numero,
                    exigir(peticion.nuevaDireccionReferencial(), "nuevaDireccionReferencial"),
                    fechaOpcional(peticion.fecha(), "fecha", hoy),
                    exigir(peticion.motivo(), "motivo"),
                    observacion);
        } catch (CambiarEstadoDelExpediente.ExpedienteInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (CambiarDireccionReferencial.MismaDireccion enConflicto) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(enConflicto));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        return fichaDe(numero, hoy);
    }

    // ------------------------------------------------------------------

    private ExpedienteResource fichaDe(String numero, LocalDate aLaFecha) {
        ConsultaDeExpedientes.FichaDelExpediente ficha =
                consulta.porNumero(numero, aLaFecha)
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No hay ningun expediente coactivo con el numero '"
                                                        + numero
                                                        + "'"));
        return ExpedienteResource.de(ficha, codigoDe(ficha.expediente().contribuyenteId()));
    }

    private Map<Long, ResumenDeContribuyente> padronDe(
            Pagina<ConsultaDeExpedientes.ExpedienteConDeuda> pagina) {
        Set<Long> ids = new HashSet<>();
        for (ConsultaDeExpedientes.ExpedienteConDeuda fila : pagina.contenido()) {
            ids.add(fila.fila().expediente().contribuyenteId());
        }
        return ids.isEmpty() ? Map.of() : contribuyentes.porIds(ids);
    }

    private static String codigoDe(Map<Long, ResumenDeContribuyente> padron, long contribuyenteId) {
        ResumenDeContribuyente enElMapa = padron.get(contribuyenteId);
        // Sin nombre en el padron se cae al identificador en vez de ocultar la fila: un
        // expediente cuyo obligado se dio de baja es justamente el que hay que revisar.
        return enElMapa == null ? String.valueOf(contribuyenteId) : enElMapa.codigo();
    }

    private String codigoDe(long contribuyenteId) {
        return Optional.ofNullable(
                        contribuyentes.porIds(Set.of(contribuyenteId)).get(contribuyenteId))
                .map(ResumenDeContribuyente::codigo)
                .orElse(String.valueOf(contribuyenteId));
    }

    private ResumenDeContribuyente contribuyenteDe(@Nullable String codigo) {
        String valor = exigir(codigo, "codContribuyente");
        return contribuyentes
                .porCodigo(valor)
                .orElseThrow(
                        () ->
                                new ProblemaDeNegocio(
                                        CodigoDeError.NO_ENCONTRADO,
                                        "No hay ningun contribuyente con el codigo '"
                                                + valor
                                                + "'"));
    }

    private @Nullable Long contribuyenteOpcional(@Nullable String codigo) {
        String valor = vacioAnulo(codigo);
        if (valor == null) {
            return null;
        }
        // Un codigo que no existe deja el filtro sin candidatos, y eso es una lista vacia, no un
        // 404: la grilla admite que se teclee cualquier cosa en su caja de filtro.
        return contribuyentes.porCodigo(valor).map(ResumenDeContribuyente::id).orElse(-1L);
    }

    private static @Nullable EstadoDelExpediente estadoOpcional(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        if (valor == null || "TODOS".equalsIgnoreCase(valor)) {
            return null;
        }
        try {
            return EstadoDelExpediente.porNombre(valor);
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(desconocido));
        }
    }

    private static EstadoDelExpediente estadoDe(@Nullable String texto) {
        String valor = exigir(texto, "nuevoEstado");
        try {
            return EstadoDelExpediente.porNombre(valor);
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(desconocido));
        }
    }

    private static LocalDate fechaOpcional(
            @Nullable String texto, String campo, LocalDate porOmision) {
        LocalDate leida = fechaNula(texto, campo);
        return leida == null ? porOmision : leida;
    }

    private static @Nullable LocalDate fechaNula(@Nullable String texto, String campo) {
        if (texto == null || texto.isBlank()) {
            return null;
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

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "La operacion no se pudo completar" : mensaje;
    }
}
