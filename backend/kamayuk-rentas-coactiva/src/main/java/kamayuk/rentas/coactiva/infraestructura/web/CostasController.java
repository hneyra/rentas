package kamayuk.rentas.coactiva.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.coactiva.aplicacion.ArancelDeCostasParametrizado;
import kamayuk.rentas.coactiva.aplicacion.CambiarEstadoDelExpediente;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeCostas;
import kamayuk.rentas.coactiva.aplicacion.LiquidarCostas;
import kamayuk.rentas.coactiva.dominio.CriterioDeLiquidaciones;
import kamayuk.rentas.coactiva.dominio.EstadoDeLaLiquidacion;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostas;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostasRepository;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.FiltroDeLaConsulta;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * La liquidacion de costas procesales por HTTP (RF-104).
 *
 * <h2>Ningun {@code PUT} ni {@code PATCH}</h2>
 *
 * <p>Igual que en {@code ActoCoactivoController} y {@code ConvenioController}, y por lo mismo: una
 * liquidacion se notifica y su cargo ya esta en el libro. No se corrige. {@code costa_procesal} no
 * admite {@code UPDATE} desde V35, y {@code liquidacion_costas} nunca lo tuvo.
 *
 * <h2>Por que hay un {@code GET} que el prototipo no declaraba</h2>
 *
 * <p>La pantalla {@code costas_procesales} tiene una grilla —«Liquidaciones encontradas»— con
 * cuatro filtros, y el contrato, derivado mecanicamente del {@code endpoint} que declara cada
 * opcion, solo recogio la accion principal. El {@code GET} se agrega al contrato con los
 * <b>mismos</b> filtros que la opcion ya declaraba, mas la paginacion comun. Es un diff aditivo:
 * ninguna operacion cambia de forma.
 *
 * <p>La alternativa —hacer que el {@code POST} devolviera tambien la grilla— convertiria una
 * consulta en una escritura, y una pantalla que lista al abrirse consumiria un correlativo cada
 * vez.
 *
 * <h2>Que devuelve 422, y por que no 500 (#562)</h2>
 *
 * <p>El arancel de cada costa sale del <b>conjunto sellado</b> que rige a la fecha de la
 * liquidacion ({@link ArancelDeCostasParametrizado}, regla 5). Que el conjunto exista y no traiga
 * la llave ({@code ArancelSinParametrizar}) ya estaba traducido desde #42; que <b>no exista ningun
 * conjunto sellado</b> ({@code EjercicioSinSellar}) no lo estaba, y con D-02a abierta ese es el
 * estado <i>normal</i> de todas las municipalidades: salia como <b>500 {@code ERROR_INTERNO} con
 * identificador de incidencia</b>, y cada intento ensuciaba el registro de errores del servidor.
 *
 * <p>El mensaje es el de la propia excepcion: nombra la llave —{@code ARANCEL_COSTA:REC1}— o, si lo
 * que falta es el conjunto entero y no hay llave que nombrar, el <b>ejercicio</b>. Un fallo de
 * verdad del servidor sigue siendo 500 con su incidencia.
 *
 * <p><b>Y ese {@code catch} de {@code ArancelSinParametrizar} no sobra</b> (#634). #562 lo dio por
 * inalcanzable, y lo era solo por la rama «todo el expediente»: pidiendo actos por su identificador
 * siempre ha llegado hasta aqui, asi que retirarlo convertiria un 422 correcto en un 500 con
 * incidencia. Lo que #634 corrige es la otra rama —liquidar el expediente entero cuando
 * <b>nadie</b> publico el arancel—, que contestaba «este expediente no tiene ningun acto pendiente
 * de liquidar»: el mismo 422, con un mensaje que se lee como «no hay nada que cobrar» en vez de
 * «falta publicar {@code ARANCEL_COSTA:REC1}». Que la ordenanza <b>no tarife</b> un acto sigue
 * siendo {@code SinActosQueLiquidar}, porque eso si es una decision de la ordenanza; la diferencia
 * la establece {@code LiquidarCostas.candidatosDe}.
 */
@RestController
@RequestMapping(Api.RAIZ + "/coactiva")
public class CostasController {

    /** La opcion del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_COSTAS = "costas_procesales";

    private static final String ORDEN_POR_OMISION = "fecha";

    private final LiquidarCostas liquidar;
    private final ConsultaDeCostas consulta;
    private final DirectorioDeContribuyentes contribuyentes;
    private final Clock reloj;

    public CostasController(
            LiquidarCostas liquidar,
            ConsultaDeCostas consulta,
            DirectorioDeContribuyentes contribuyentes,
            Clock reloj) {
        this.liquidar = liquidar;
        this.consulta = consulta;
        this.contribuyentes = contribuyentes;
        this.reloj = reloj;
    }

    /**
     * Liquida las costas del expediente y asienta su cargo (RF-104).
     *
     * <p>Responde <b>201</b> con la liquidacion. Con D-02c abierta (#193) responde <b>422</b>
     * nombrando la llave del arancel que falta —{@code ARANCEL_COSTA:REC1}—, que es exactamente lo
     * que tiene que pasar mientras la ordenanza no este cargada: no hay cifra con la que liquidar.
     * Por las <b>dos</b> rutas desde #634: pidiendo actos concretos y liquidando el expediente
     * entero.
     *
     * <p><b>{@code nroExpedCoact} tambien viaja por la consulta</b> (#425). Es el filtro «Nro.
     * Exped. Coact.» que la pantalla dibuja y el contrato lo declara {@code in: query}; leerlo solo
     * del cuerpo dejaba esta operacion publicada y sin ninguna pantalla que pudiera llamarla. Se
     * sigue aceptando en el cuerpo, y ahi gana: ver {@link FiltroDeLaConsulta}.
     */
    @PostMapping("/liquidaciones-costas")
    @RequiereAcceso(acceso = ACCESO_COSTAS, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<LiquidacionResource> liquidar(
            @RequestParam(required = false) @Nullable String nroExpedCoact,
            @RequestBody PeticionDeLiquidacionDeCostas peticion) {

        Observacion observacion = observacionDe(peticion.observacion());
        String numeroDeExpediente =
                exigir(
                        FiltroDeLaConsulta.primeroNoVacio(peticion.nroExpedCoact(), nroExpedCoact),
                        "nroExpedCoact");
        LocalDate fecha = fechaOpcional(peticion.fecha(), "fecha", LocalDate.now(reloj));

        Set<Long> actos =
                peticion.actos() == null ? Set.of() : new LinkedHashSet<>(peticion.actos());

        LiquidacionDeCostas liquidacion;
        try {
            liquidacion =
                    liquidar.liquidar(
                            new LiquidarCostas.Peticion(numeroDeExpediente, fecha, actos),
                            observacion);
        } catch (CambiarEstadoDelExpediente.ExpedienteInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (CambiarEstadoDelExpediente.ExpedienteConcluido
                | LiquidacionDeCostasRepository.ActoYaLiquidado
                | LiquidacionDeCostasRepository.ObligacionDeOtroExpediente enConflicto) {
            // 409: la peticion esta bien formada; lo que no admite la operacion es el estado
            // actual del expediente o de sus actos.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(enConflicto));
        } catch (ArancelDeCostasParametrizado.ArancelSinParametrizar
                | LectorDeParametros.EjercicioSinSellar falta) {
            // `EjercicioSinSellar` no es un fallo del servidor: es que nadie ha sellado todavia el
            // conjunto del ejercicio de la liquidacion (D-02a). Ver la cabecera de la clase (#562).
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
        } catch (LiquidarCostas.SinActosQueLiquidar
                | LiquidarCostas.ActoAjeno
                | IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(LiquidacionResource.de(liquidacion, numeroDeExpediente));
    }

    /**
     * La grilla «Liquidaciones encontradas», con el pendiente y el estado de cada una a hoy
     * (RF-104).
     */
    @GetMapping("/liquidaciones-costas")
    @RequiereAcceso(acceso = ACCESO_COSTAS, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<LiquidacionResource> listar(
            @RequestParam(required = false) @Nullable String nroLiquidacion,
            @RequestParam(required = false) @Nullable String nroExpedCoact,
            @RequestParam(required = false) @Nullable String contribuyente,
            @RequestParam(required = false) @Nullable String estado,
            ParametrosDePaginacion paginacion) {

        CriterioDeLiquidaciones criterio =
                new CriterioDeLiquidaciones(
                        vacioAnulo(nroLiquidacion),
                        vacioAnulo(nroExpedCoact),
                        contribuyenteOpcional(contribuyente));

        Pagina<ConsultaDeCostas.LiquidacionEnConsulta> pagina =
                consulta.buscar(
                        criterio,
                        LocalDate.now(reloj),
                        estadoOpcional(estado),
                        paginacion.aPaginacion(ORDEN_POR_OMISION));

        return RespuestaPaginada.de(pagina, LiquidacionResource::de);
    }

    // ------------------------------------------------------------------

    /**
     * El contribuyente del filtro, ya resuelto a identificador.
     *
     * <p>Un codigo que no existe deja el filtro sin candidatos, y eso es una lista vacia, no un
     * 404: la grilla admite que se teclee cualquier cosa en su caja de filtro.
     */
    private @Nullable Long contribuyenteOpcional(@Nullable String codigo) {
        String valor = vacioAnulo(codigo);
        if (valor == null) {
            return null;
        }
        return contribuyentes.porCodigo(valor).map(ResumenDeContribuyente::id).orElse(-1L);
    }

    private static @Nullable EstadoDeLaLiquidacion estadoOpcional(@Nullable String texto) {
        String valor = vacioAnulo(texto);
        if (valor == null || "TODOS".equalsIgnoreCase(valor)) {
            return null;
        }
        try {
            return EstadoDeLaLiquidacion.porNombre(valor);
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(desconocido));
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
