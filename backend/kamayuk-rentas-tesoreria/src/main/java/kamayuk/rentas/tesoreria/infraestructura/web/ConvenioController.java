package kamayuk.rentas.tesoreria.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import kamayuk.rentas.tesoreria.aplicacion.CerrarConvenio;
import kamayuk.rentas.tesoreria.aplicacion.CondicionesParametrizadas;
import kamayuk.rentas.tesoreria.aplicacion.ConsultaDeConvenios;
import kamayuk.rentas.tesoreria.aplicacion.FormalizarConvenio;
import kamayuk.rentas.tesoreria.aplicacion.RegistrarPreconvenio;
import kamayuk.rentas.tesoreria.aplicacion.RegistrarPreconvenio.Peticion;
import kamayuk.rentas.tesoreria.dominio.CondicionesDelConvenio;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.ConvenioEnConsulta;
import kamayuk.rentas.tesoreria.dominio.ConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.CriterioDeConvenios;
import kamayuk.rentas.tesoreria.dominio.Cronograma;
import kamayuk.rentas.tesoreria.dominio.EstadoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.NumeroDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeGarantia;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeConvenio;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
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
 * Los convenios de fraccionamiento por HTTP: registro, consulta y cierre (RF-084, RF-085, RF-086).
 *
 * <p>Ningun {@code PUT} ni {@code PATCH}, igual que en {@link CajaController} y por lo mismo: un
 * convenio no se corrige (regla 4, V31). Lo que le pasa llega como un recurso nuevo —una anulacion,
 * un quiebre, una reformulacion—, porque eso es lo que son: actos que se agregan.
 *
 * <h2>Lo que este controlador NO publica</h2>
 *
 * <p><b>Ninguna ruta para formalizar.</b> Un convenio se pone en vigor cobrando su cuota inicial, y
 * eso pasa por la caja: {@code POST /tesoreria/caja/cobranza} con {@code tipoDePago = PRECONVENIO}.
 * Publicar aqui un «formalizar» permitiria poner un convenio en vigor sin recibo, que es
 * exactamente lo que el criterio de aceptacion de #35 prohibe.
 *
 * <h2>La cabecera {@code Idempotency-Key} (#606)</h2>
 *
 * <p>Las <b>dos</b> escrituras la leen, como {@link CajaController} desde #33. Tras un 500 o un
 * tiempo de espera agotado, quien atiende no sabe si escribio; reenviar el mismo intento devuelve
 * <b>lo de la primera vez</b> —el mismo convenio, con su mismo numero; el mismo acta de cierre— en
 * vez de abrir un segundo preconvenio sobre la misma deuda o contestar un 409 que se lee como un
 * fallo nuevo. Sin eso, la pantalla de convenios no puede ofrecer «Reintentar» sobre ellas, y no lo
 * ofrecia.
 *
 * <p>Las dos responden <b>201</b> tambien en el reenvio, como {@code POST /tesoreria/caja/cobranza}
 * desde #33: dentro de tesoreria las escrituras de ventanilla contestan igual, y que la misma
 * situacion diera 201 en la caja y 200 aqui obligaria a quien llama a distinguir dos exitos. {@code
 * AnuncioController} si los distingue (#51), y esa diferencia queda anotada a proposito.
 *
 * <p>La garantia ultima son {@code convenio_idempotencia_uq} y {@code
 * convenio_movimiento_idempotencia_uq} (V70), no las lecturas previas de los casos de uso: entre
 * leer y escribir cabe otra peticion. Las lecturas estan para poder contestar algo util.
 *
 * <p><b>La simulacion no consume la clave</b>: con {@code simular = true} no se escribe nada, asi
 * que no hay nada que devolver dos veces. Simular y despues registrar con la misma clave registra,
 * que es lo que la pantalla hace cuando quien atiende imprime la simulacion y luego acepta.
 *
 * <h2>El numero, en la ruta</h2>
 *
 * <p>{@code {numero}} es el numero <b>impreso</b>, {@code F-2026-000123}: lo que dice el papel que
 * el contribuyente trae a la ventanilla. Ni el identificador interno ni el ejercicio y el
 * correlativo por separado.
 *
 * <h2>Que devuelve 422, y por que no 500 (#547)</h2>
 *
 * <p>Un convenio no se puede armar sin el interes de fraccionamiento, sin el maximo de cuotas y sin
 * la politica con que se redondea cada cuota, y las tres salen del <b>conjunto sellado</b> del
 * ejercicio del convenio ({@link CondicionesParametrizadas}, regla 5). Que ese conjunto no exista
 * ({@code EjercicioSinSellar}), que exista y no traiga la llave ({@code CondicionSinParametrizar})
 * o que no parametrice ningun punto de redondeo ({@code SinPuntosObservados}, con sus tres hermanas
 * de {@link PoliticasDeRedondeoSelladas}) <b>no es un fallo del servidor</b>: es una cifra que
 * todavia nadie ha publicado, y con D-02a y D-03c abiertas es el estado <i>normal</i> del sistema.
 *
 * <p>Hasta #547 solo estaba traducida la segunda. Las otras cinco caian en el
 * {@code @ExceptionHandler(Exception.class)} de {@code ManejadorDeErrores} y salian como <b>500
 * {@code ERROR_INTERNO} con identificador de incidencia</b>, con dos consecuencias —las mismas que
 * #540 midio para las determinaciones de Rentas—: la interfaz no podia distinguir «falta publicar
 * un parametro» de «el servidor esta roto», y un cliente que reintenta un 500 reintenta para
 * siempre; y cada intento <b>escribia una incidencia de nivel ERROR en el registro</b>, o sea el
 * registro de errores del servidor lleno de lo que no es un error. Con eso, el destino entero de
 * convenios era inalcanzable: no se podia crear ninguno, el listado salia siempre vacio y la
 * anulacion no tenia sobre que actuar.
 *
 * <p>El codigo elegido es <b>422</b> y no 404, siguiendo a {@code LiquidacionController} y no a los
 * tres lectores de cuadro de catastro —que traducen la misma excepcion a 404 <i>a proposito</i>,
 * porque ellos <b>leen un cuadro</b> («de ese ejercicio no hay tal tabla») y aqui se <b>pide un
 * calculo</b>, que es lo que hace {@code LiquidacionController}—. El mensaje es el de la propia
 * excepcion: ya esta redactado en lenguaje del dominio y nombra el ejercicio o la llave —{@code
 * INTERES_FRACCIONAMIENTO:ORDINARIO}, {@code REDONDEO:CUOTA}—, sin tabla, sin restriccion y sin SQL
 * (RNF-033).
 *
 * <p>Lo que <b>no</b> cambia: un fallo de verdad del servidor sigue siendo 500 con su incidencia.
 * Una traduccion demasiado ancha —convertirlo todo en 422— es peor que el defecto que arregla, y
 * hay una prueba de contraste que lo mide.
 *
 * <p>La traduccion vive aqui y no en {@code ManejadorDeErrores} porque {@code kamayuk.rentas.web}
 * esta en {@code kamayuk-rentas-plataforma}, que no depende —ni debe— de {@code
 * kamayuk-rentas-parametros}, que es un contexto acotado; y porque la eleccion de codigo no es
 * uniforme en el sistema, asi que decidirla en un sitio unico decidiria tambien por catastro.
 */
@RestController
@RequestMapping(Api.RAIZ + "/tesoreria")
public class ConvenioController {

    /** Las tres opciones del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_FRACCIONAMIENTO = "fraccionamiento";

    static final String ACCESO_CONSULTA = "consulta_convenios";

    static final String ACCESO_ANULACION = "anulacion_convenio";

    private static final String ORDEN_POR_OMISION = "fecha";

    /** Los tres actos que la pantalla ofrece; ninguno mas entra por aqui. */
    private static final Set<TipoDeMovimientoDeConvenio> CIERRES =
            Set.of(
                    TipoDeMovimientoDeConvenio.ANULACION,
                    TipoDeMovimientoDeConvenio.QUIEBRE,
                    TipoDeMovimientoDeConvenio.REFORMULACION);

    /**
     * Los rotulos del desplegable «Estado» de la pantalla, traducidos al estado del dominio.
     *
     * <p>El prototipo ofrece «CUMPLIDO» y «EN RIESGO», que <b>no</b> son estados del convenio sino
     * situaciones derivadas de sus cuotas —y su definicion depende de cuantas impagas seguidas
     * producen la perdida del beneficio, que es una cifra de ordenanza local (D-02b, #191)—. Se
     * rechazan explicitamente en vez de traducirse a algo parecido: filtrar por «EN RIESGO» con una
     * definicion inventada devolveria una lista que nadie puede auditar.
     */
    private static final Map<String, EstadoDeConvenio> ESTADOS =
            Map.of(
                    "PRECONVENIO", EstadoDeConvenio.PRECONVENIO,
                    "VIGENTE", EstadoDeConvenio.VIGENTE,
                    "ANULADO", EstadoDeConvenio.ANULADO,
                    "QUEBRADO", EstadoDeConvenio.QUEBRADO,
                    "REFORMULADO", EstadoDeConvenio.REFORMULADO);

    private final RegistrarPreconvenio registrar;
    private final CerrarConvenio cerrar;
    private final ConsultaDeConvenios consulta;
    private final DirectorioDeContribuyentes contribuyentes;
    private final Clock reloj;

    public ConvenioController(
            RegistrarPreconvenio registrar,
            CerrarConvenio cerrar,
            ConsultaDeConvenios consulta,
            DirectorioDeContribuyentes contribuyentes,
            Clock reloj) {
        this.registrar = registrar;
        this.cerrar = cerrar;
        this.consulta = consulta;
        this.contribuyentes = contribuyentes;
        this.reloj = reloj;
    }

    /**
     * Registra el preconvenio, o solo simula su cronograma (RF-084).
     *
     * <p>Con {@code simular = true} no escribe nada: ni numera un convenio, ni toca el libro, ni
     * deja auditoria. Es el boton «Imprimir simulacion» de la pantalla, y va por la misma ruta
     * porque el prototipo declara una sola.
     *
     * <p>Lo que sale de aqui es <b>siempre</b> un preconvenio: no acoge deuda, y hasta que su cuota
     * inicial se cobre en caja el libro no se entera de que existe.
     */
    @PostMapping("/fraccionamientos")
    @RequiereAcceso(acceso = ACCESO_FRACCIONAMIENTO, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<Object> fraccionar(
            @RequestBody PeticionDeFraccionamiento peticion,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String clave) {
        ResumenDeContribuyente contribuyente = contribuyenteDe(peticion.codContribuyente());
        RegistrarPreconvenio.Peticion pedido = peticionDe(peticion, contribuyente.id());

        if (Boolean.TRUE.equals(peticion.simular())) {
            try {
                return ResponseEntity.ok(
                        ConvenioResource.SimulacionResource.de(registrar.simular(pedido)));
            } catch (CondicionesParametrizadas.CondicionSinParametrizar
                    | LectorDeParametros.EjercicioSinSellar
                    | PoliticasDeRedondeoSelladas.SinPuntosObservados
                    | PoliticasDeRedondeoSelladas.PuntoSinObservar
                    | PoliticasDeRedondeoSelladas.MediaPolitica
                    | PoliticasDeRedondeoSelladas.EscalaNoEntera
                    | PoliticasDeRedondeoSelladas.ModoDesconocido falta) {
                throw FaltaPublicar.problema(falta);
            } catch (RegistrarPreconvenio.SinDeudaQueFraccionar
                    | CondicionesDelConvenio.DemasiadasCuotas
                    | Cronograma.NadaQueFraccionar
                    | IllegalArgumentException invalido) {
                throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
            }
        }

        Observacion observacion = observacionDe(peticion.observacion());
        try {
            Convenio guardado = registrar.registrar(pedido, vacioAnulo(clave), observacion);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(
                            ConvenioResource.de(
                                    guardado,
                                    contribuyente.codigo(),
                                    EstadoDeConvenio.PRECONVENIO.name()));
        } catch (ConvenioRepository.ClaveRepetida
                | RegistrarPreconvenio.ClaveDeOtraPeticion claveEnConflicto) {
            // 409: la peticion esta bien formada. `ClaveRepetida` es la carrera —dos envios del
            // mismo intento a la vez, y la segunda no puede devolver lo que la primera aun no ha
            // confirmado—; `ClaveDeOtraPeticion` es una clave vieja reusada para otro sujeto.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(claveEnConflicto));
        } catch (ConvenioRepository.CronogramaDuplicado yaTeniaCronograma) {
            // 409: la peticion esta bien formada; lo que no admite la operacion es el
            // estado actual del convenio.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(yaTeniaCronograma));
        } catch (CondicionesParametrizadas.CondicionSinParametrizar
                | LectorDeParametros.EjercicioSinSellar
                | PoliticasDeRedondeoSelladas.SinPuntosObservados
                | PoliticasDeRedondeoSelladas.PuntoSinObservar
                | PoliticasDeRedondeoSelladas.MediaPolitica
                | PoliticasDeRedondeoSelladas.EscalaNoEntera
                | PoliticasDeRedondeoSelladas.ModoDesconocido falta) {
            throw FaltaPublicar.problema(falta);
        } catch (RegistrarPreconvenio.SinDeudaQueFraccionar
                | CondicionesDelConvenio.DemasiadasCuotas
                | Cronograma.NadaQueFraccionar
                | IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    /**
     * El seguimiento de los convenios suscritos, paginado (RF-084).
     *
     * <h2>El detalle se carga solo cuando la consulta apunta a uno</h2>
     *
     * <p>Con {@code nroDeConvenio}, la fila trae ademas su cronograma, la deuda original que acogio
     * y sus movimientos: es la ficha que la pantalla dibuja al abrir un convenio. Sin el, la fila
     * es la que la grilla pinta y nada mas —una pagina de veinte no puede costar veinte lecturas de
     * detalle—.
     *
     * <p>Una ruta y no dos porque el prototipo declara una: {@code GET /tesoreria/convenios}. Un
     * {@code GET /tesoreria/convenios/{numero}} seria una ruta que ninguna pantalla llama, y el
     * contrato la rechazaria ({@code ContratoDeApiTest}).
     */
    @GetMapping("/convenios")
    @RequiereAcceso(acceso = ACCESO_CONSULTA, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<ConvenioResource.FilaResource> listar(
            @RequestParam(required = false) @Nullable String nroDeConvenio,
            @RequestParam(required = false) @Nullable String codContribuyente,
            @RequestParam(required = false) @Nullable String estado,
            @RequestParam(required = false) @Nullable String desde,
            @RequestParam(required = false) @Nullable String hasta,
            ParametrosDePaginacion paginacion) {

        CriterioDeConvenios criterio;
        try {
            criterio =
                    new CriterioDeConvenios(
                            vacioAnulo(nroDeConvenio),
                            vacioAnulo(codContribuyente),
                            estadoDe(estado),
                            fechaOpcional(desde, "desde"),
                            fechaOpcional(hasta, "hasta"),
                            LocalDate.now(reloj));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        Pagina<ConvenioEnConsulta> pagina =
                consulta.listar(criterio, paginacion.aPaginacion(ORDEN_POR_OMISION));

        if (criterio.numero() == null || pagina.contenido().size() != 1) {
            return RespuestaPaginada.de(pagina, ConvenioResource.FilaResource::de);
        }
        ConvenioEnConsulta unica = pagina.contenido().get(0);
        return RespuestaPaginada.de(
                pagina,
                fila ->
                        consulta.ficha(unica.numero())
                                .map(ficha -> ConvenioResource.FilaResource.de(fila, ficha))
                                .orElseGet(() -> ConvenioResource.FilaResource.de(fila)));
    }

    /**
     * Anula, quiebra o reformula el convenio, devolviendo su deuda a la fase de origen (RF-085,
     * RF-086).
     *
     * <p>Las tres acciones por la misma ruta porque el prototipo declara una sola —«Anulacion de
     * convenio», con los botones «Anular», «Reformar» y «Quebrar»— y porque en el libro son el
     * mismo acto: lo pendiente vuelve a la fase de la que salio.
     */
    @PostMapping("/convenios/{numero}/anulacion")
    @RequiereAcceso(acceso = ACCESO_ANULACION, privilegio = Privilegio.ELIMINACION)
    public ResponseEntity<ConvenioResource> cerrar(
            @PathVariable String numero,
            @RequestBody PeticionDeCierreDeConvenio peticion,
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String clave) {

        NumeroDeConvenio delConvenio = numeroDe(numero);
        Observacion observacion = observacionDe(peticion.observacion());
        TipoDeMovimientoDeConvenio accion = accionDe(peticion.accion());

        @Nullable Peticion reformulacion = null;
        if (accion == TipoDeMovimientoDeConvenio.REFORMULACION) {
            PeticionDeFraccionamiento cuerpo = peticion.reformulacion();
            if (cuerpo == null) {
                throw new ProblemaDeNegocio(
                        CodigoDeError.VALIDACION,
                        "Una reformulacion trae el convenio nuevo que la sustituye: sin el, el"
                                + " saldo pendiente se quedaria sin convenio");
            }
            reformulacion = peticionDe(cuerpo, contribuyenteDe(cuerpo.codContribuyente()).id());
        }

        CerrarConvenio.Cierre cierre;
        try {
            cierre =
                    new CerrarConvenio.Cierre(
                            delConvenio,
                            accion,
                            fechaDe(peticion.fechaAnul(), "fechaAnul"),
                            exigir(peticion.motivo(), "motivo"),
                            vacioAnulo(peticion.responsableAnul()),
                            vacioAnulo(peticion.nDeMemorando()),
                            reformulacion);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        try {
            CerrarConvenio.Cerrado cerrado = cerrar.cerrar(cierre, vacioAnulo(clave), observacion);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(
                            ConvenioResource.de(
                                    cerrado.convenio(),
                                    codigoDe(cerrado.convenio().contribuyenteId()),
                                    // Del acta y no de la accion pedida: en un reenvio las dos
                                    // coinciden, y si no coincidieran mandaria lo que se registro.
                                    estadoTras(cerrado.cierre().tipo()).name()));
        } catch (MovimientoDeConvenioRepository.ClaveRepetida
                | CerrarConvenio.ClaveDeOtroActo claveEnConflicto) {
            // 409, por lo mismo que en /fraccionamientos: la carrera de dos envios simultaneos y
            // la clave vieja reusada para otro convenio no son reenvios que se puedan atender.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(claveEnConflicto));
        } catch (FormalizarConvenio.ConvenioInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (MovimientoDeConvenioRepository.ConvenioYaCerrado
                | MovimientoDeConvenioRepository.ConvenioYaFormalizado
                | CerrarConvenio.ReciboDeLaInicialVigente
                | ConvenioRepository.CronogramaDuplicado enConflicto) {
            // 409: la peticion esta bien formada; lo que no admite la operacion es el
            // estado actual del convenio.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(enConflicto));
        } catch (CondicionesParametrizadas.CondicionSinParametrizar
                | LectorDeParametros.EjercicioSinSellar
                | PoliticasDeRedondeoSelladas.SinPuntosObservados
                | PoliticasDeRedondeoSelladas.PuntoSinObservar
                | PoliticasDeRedondeoSelladas.MediaPolitica
                | PoliticasDeRedondeoSelladas.EscalaNoEntera
                | PoliticasDeRedondeoSelladas.ModoDesconocido falta) {
            // La reformulacion registra un preconvenio nuevo, y ese pide sus condiciones al
            // conjunto sellado del ejercicio en que se firma: lo mismo que /fraccionamientos,
            // y por eso se traduce igual —con su discriminador incluido (#604)—.
            throw FaltaPublicar.problema(falta);
        } catch (RegistrarPreconvenio.SinDeudaQueFraccionar
                | CerrarConvenio.ConvenioSinFormalizar
                | CondicionesDelConvenio.DemasiadasCuotas
                | Cronograma.NadaQueFraccionar
                | IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    // ------------------------------------------------------------------

    private static EstadoDeConvenio estadoTras(TipoDeMovimientoDeConvenio accion) {
        return switch (accion) {
            case ANULACION -> EstadoDeConvenio.ANULADO;
            case QUIEBRE -> EstadoDeConvenio.QUEBRADO;
            case REFORMULACION -> EstadoDeConvenio.REFORMULADO;
            case FORMALIZACION -> EstadoDeConvenio.VIGENTE;
        };
    }

    private RegistrarPreconvenio.Peticion peticionDe(
            PeticionDeFraccionamiento peticion, long contribuyenteId) {
        LocalDate fecha = fechaDe(peticion.fecha(), "fecha");
        LocalDate corte =
                peticion.fechaDeCorte() == null || peticion.fechaDeCorte().isBlank()
                        ? fecha
                        : fechaDe(peticion.fechaDeCorte(), "fechaDeCorte");
        Integer cuotas = peticion.nroDeCuotas();
        if (cuotas == null) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo 'nroDeCuotas'");
        }
        try {
            return new RegistrarPreconvenio.Peticion(
                    contribuyenteId,
                    obligacionesDe(peticion.obligaciones()),
                    tipoDe(peticion.tipo()),
                    fecha,
                    corte,
                    cuotas,
                    porcentajeDe(peticion.cuotaInicial()),
                    peticion.primeraCuotaVence() == null || peticion.primeraCuotaVence().isBlank()
                            ? fecha
                            : fechaDe(peticion.primeraCuotaVence(), "primeraCuotaVence"),
                    garantiaDe(peticion.tipoDeGarantia()),
                    vacioAnulo(peticion.detalleDelOfrecimiento()),
                    vacioAnulo(peticion.resolucion()),
                    null);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
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

    private String codigoDe(long contribuyenteId) {
        return Optional.ofNullable(
                        contribuyentes.porIds(Set.of(contribuyenteId)).get(contribuyenteId))
                .map(ResumenDeContribuyente::codigo)
                .orElse(String.valueOf(contribuyenteId));
    }

    private static List<SeleccionDeObligacion> obligacionesDe(
            @Nullable List<PeticionDeFraccionamiento.PeticionDeObligacionAcogida> marcadas) {
        if (marcadas == null || marcadas.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que marcar al menos una deuda: un convenio sin deuda acogida no fracciona"
                            + " nada");
        }
        List<SeleccionDeObligacion> seleccion = new ArrayList<>(marcadas.size());
        for (PeticionDeFraccionamiento.PeticionDeObligacionAcogida marcada : marcadas) {
            Integer ejercicio = marcada.ejercicio();
            if (ejercicio == null) {
                throw new ProblemaDeNegocio(
                        CodigoDeError.VALIDACION, "Falta el campo 'obligaciones[].ejercicio'");
            }
            try {
                seleccion.add(
                        new SeleccionDeObligacion(
                                exigir(marcada.tributo(), "obligaciones[].tributo"),
                                new Ejercicio(ejercicio),
                                marcada.predioId(),
                                marcada.vehiculoId()));
            } catch (IllegalArgumentException invalido) {
                throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
            }
        }
        return seleccion;
    }

    /**
     * El porcentaje de cuota inicial, admitiendo el rotulo de la pantalla («20 %»).
     *
     * <p>Es un {@code Alicuota} y no un {@code Porcentaje} porque el 0 % es admisible: la ordenanza
     * puede pactar un convenio sin entrada, y {@code Porcentaje} exige mayor que cero.
     */
    private static Alicuota porcentajeDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo 'cuotaInicial'");
        }
        String limpio = texto.strip().replace("%", "").strip();
        try {
            // NumberFormatException es una IllegalArgumentException, igual que el rechazo
            // de rango de Alicuota: las dos dicen lo mismo a quien teclea el porcentaje.
            return Alicuota.de(limpio);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo 'cuotaInicial' es un porcentaje de 0 a 100: '" + texto + "'");
        }
    }

    private static TipoDeConvenio tipoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return TipoDeConvenio.ORDINARIO;
        }
        try {
            return TipoDeConvenio.porNombre(texto);
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Tipo de convenio desconocido: '"
                            + texto
                            + "'. Se admite ORDINARIO o"
                            + " COACTIVO");
        }
    }

    private static @Nullable TipoDeGarantia garantiaDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return TipoDeGarantia.porNombre(texto);
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Tipo de garantia desconocido: '"
                            + texto
                            + "'. Se admite NO REQUIERE, CARTA FIANZA, HIPOTECA, AVAL o PRENDA");
        }
    }

    private static TipoDeMovimientoDeConvenio accionDe(@Nullable String texto) {
        String valor = exigir(texto, "accion");
        TipoDeMovimientoDeConvenio accion;
        try {
            accion = TipoDeMovimientoDeConvenio.porNombre(valor);
        } catch (IllegalArgumentException desconocida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Accion desconocida: '"
                            + texto
                            + "'. Se admite ANULACION, QUIEBRE o REFORMULACION");
        }
        if (!CIERRES.contains(accion)) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Un convenio se formaliza cobrando su cuota inicial en caja, no por esta ruta"
                            + " (#35, RF-084)");
        }
        return accion;
    }

    private static @Nullable EstadoDeConvenio estadoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank() || "TODOS".equalsIgnoreCase(texto.strip())) {
            return null;
        }
        EstadoDeConvenio estado = ESTADOS.get(texto.strip().toUpperCase(Locale.ROOT));
        if (estado == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Estado desconocido: '"
                            + texto
                            + "'. Se admite "
                            + new java.util.TreeSet<>(ESTADOS.keySet())
                            + " o Todos. «CUMPLIDO» y «EN RIESGO» no son estados del convenio sino"
                            + " situaciones de sus cuotas, y cuantas impagas producen la perdida"
                            + " del beneficio es una cifra de ordenanza local (D-02b)");
        }
        return estado;
    }

    private static NumeroDeConvenio numeroDe(String impreso) {
        try {
            return NumeroDeConvenio.de(impreso);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    /**
     * La fecha del acto. Si no viene, hoy.
     *
     * <p>Admitirla explicita no es un descuido de seguridad sino lo que exige registrar un convenio
     * firmado ayer: quien puede hacerlo tiene el privilegio sobre la opcion, y todo queda en la
     * auditoria con su observacion (mismo criterio que {@code CajaController}).
     */
    private LocalDate fechaDe(@Nullable String texto, String campo) {
        if (texto == null || texto.isBlank()) {
            return LocalDate.now(reloj);
        }
        return exigirFecha(texto, campo);
    }

    private static @Nullable LocalDate fechaOpcional(@Nullable String texto, String campo) {
        return texto == null || texto.isBlank() ? null : exigirFecha(texto, campo);
    }

    private static LocalDate exigirFecha(String texto, String campo) {
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException invalida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' no es una fecha ISO valida: '" + texto + "'");
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
