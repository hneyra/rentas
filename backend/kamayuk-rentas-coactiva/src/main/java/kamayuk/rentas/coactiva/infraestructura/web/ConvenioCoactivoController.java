package kamayuk.rentas.coactiva.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.coactiva.aplicacion.CambiarEstadoDelExpediente;
import kamayuk.rentas.coactiva.aplicacion.FraccionarEnCoactiva;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.tesoreria.ConvenioCoactivo;
import kamayuk.rentas.tesoreria.FraccionamientoCoactivo;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El fraccionamiento coactivo por HTTP (RF-105).
 *
 * <h2>Una sola ruta, la que el prototipo declara</h2>
 *
 * <p>{@code POST /coactiva/convenios}, con la bandera {@code simular} para el boton «Simular» de la
 * pantalla, igual que {@code POST /tesoreria/fraccionamientos}. Con {@code simular = true} no se
 * escribe nada: ni se numera un convenio, ni se toca el libro, ni queda auditoria.
 *
 * <h2>Lo que este controlador NO publica</h2>
 *
 * <p><b>Ninguna ruta para formalizar, quebrar o anular.</b> Un convenio coactivo se pone en vigor
 * cobrando su cuota inicial en caja, y se cierra por {@code POST
 * /tesoreria/convenios/{numero}/anulacion} —que comprueba el recibo de la inicial y devuelve la
 * deuda a su fase de origen—. Duplicar aqui esas rutas seria duplicar sus guardas, y una de las dos
 * copias se quedaria atras.
 *
 * <p>Y es tambien lo que hace que <b>quebrar un convenio coactivo devuelva la deuda a COACTIVA</b>
 * sin una linea de codigo especifica: el quiebre es el de #35 y lee {@code
 * convenio_deuda.fase_origen}.
 *
 * <h2>Que devuelve 422, y por que no 500 (#562)</h2>
 *
 * <p>El cronograma no se puede armar sin el interes de fraccionamiento, sin el maximo de cuotas y
 * sin la politica con que se redondea cada cuota, y las tres salen del <b>conjunto sellado</b> del
 * ejercicio del convenio (regla 5). Que falte cualquiera de ellas <b>no es un fallo del
 * servidor</b>: es una cifra que todavia nadie ha publicado, y con D-02a y D-03c abiertas es el
 * estado <i>normal</i> del sistema. Hasta #562 ninguna de las seis estaba traducida aqui —este era
 * el peor de los cinco endpoints coactivos del censo— y salian como <b>500 {@code ERROR_INTERNO}
 * con identificador de incidencia</b>, con lo que el fraccionamiento coactivo entero era
 * inalcanzable y cada intento dejaba una incidencia de nivel ERROR en el registro.
 *
 * <p><b>Se captura una sola excepcion, {@code CondicionesSinPublicar}, y no las seis.</b> Las seis
 * viven en {@code tesoreria.aplicacion} y en {@code parametros}; las de tesoreria estan en un
 * subpaquete, asi que nombrarlas aqui seria depender de un tipo no expuesto y Spring Modulith lo
 * rechaza (#51). Se traducen en la frontera del modulo —{@code FraccionamientoCoactivoTesoreria}—
 * conservando el mensaje, que es el que nombra la llave o el ejercicio.
 *
 * <p>Un fallo de verdad del servidor sigue siendo 500 con su incidencia, y hay una prueba de
 * contraste que lo mide: una traduccion demasiado ancha es peor que el defecto que arregla.
 */
@RestController
@RequestMapping(Api.RAIZ + "/coactiva")
public class ConvenioCoactivoController {

    /** La opcion del catalogo (NEG-03) que este controlador sirve. */
    static final String ACCESO_CONVENIOS = "fraccionamiento_coactivo";

    private final FraccionarEnCoactiva fraccionar;
    private final Clock reloj;

    public ConvenioCoactivoController(FraccionarEnCoactiva fraccionar, Clock reloj) {
        this.fraccionar = fraccionar;
        this.reloj = reloj;
    }

    /**
     * Registra el preconvenio coactivo, o solo simula su cronograma (RF-105).
     *
     * <p>Responde <b>201</b> al registrar y <b>200</b> al simular: lo primero crea un recurso y lo
     * segundo no.
     *
     * <p><b>Lee {@code Idempotency-Key}</b> (#606), igual que la ruta de tesoreria: esta es un
     * {@code POST} tan reenviable como aquella, y sin la cabecera un reenvio tras un 500 abre un
     * segundo convenio coactivo sobre la misma deuda. La simulacion no la usa porque no registra
     * nada.
     */
    @PostMapping("/convenios")
    @RequiereAcceso(acceso = ACCESO_CONVENIOS, privilegio = Privilegio.REGISTRO)
    public ResponseEntity<ConvenioCoactivoResource> fraccionar(
            @RequestBody PeticionDeConvenioCoactivo peticion,
            @RequestHeader(value = "Idempotency-Key", required = false)
                    @org.jspecify.annotations.Nullable
                    String claveDeIdempotencia) {

        String numeroDeExpediente = exigir(peticion.nroExpedCoact(), "nroExpedCoact");
        FraccionarEnCoactiva.Peticion pedido = peticionDe(peticion, numeroDeExpediente);

        if (Boolean.TRUE.equals(peticion.simular())) {
            return ResponseEntity.ok(
                    ConvenioCoactivoResource.de(
                            ejecutar(() -> fraccionar.simular(pedido)), numeroDeExpediente));
        }

        Observacion observacion = observacionDe(peticion.observacion());
        ConvenioCoactivo registrado =
                ejecutar(() -> fraccionar.fraccionar(pedido, claveDeIdempotencia, observacion));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConvenioCoactivoResource.de(registrado, numeroDeExpediente));
    }

    // ------------------------------------------------------------------

    /**
     * Traduce las excepciones de negocio a codigos HTTP, una sola vez para las dos ramas.
     *
     * <p>Sin esto habria dos bloques {@code catch} identicos, y el dia que se agregara una
     * excepcion se agregaria a uno solo.
     */
    private static ConvenioCoactivo ejecutar(java.util.function.Supplier<ConvenioCoactivo> accion) {
        try {
            return accion.get();
        } catch (CambiarEstadoDelExpediente.ExpedienteInexistente noExiste) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noExiste));
        } catch (CambiarEstadoDelExpediente.ExpedienteConcluido enConflicto) {
            // 409: la peticion esta bien formada; lo que no admite la operacion es el estado del
            // expediente.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(enConflicto));
        } catch (FraccionamientoCoactivo.CondicionesSinPublicar falta) {
            // `CondicionesSinPublicar` no es un fallo del servidor: es que nadie ha publicado
            // todavia el interes, el maximo de cuotas o la politica de redondeo del ejercicio
            // (D-02a, D-03c). Ver la cabecera de la clase (#562).
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
        } catch (FraccionarEnCoactiva.DeudaAjenaAlProcedimiento
                | FraccionamientoCoactivo.SinDeudaCoactivaQueFraccionar
                | IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    private FraccionarEnCoactiva.Peticion peticionDe(
            PeticionDeConvenioCoactivo peticion, String numeroDeExpediente) {

        LocalDate fecha = fechaOpcional(peticion.fecha(), "fecha", LocalDate.now(reloj));
        LocalDate corte = fechaOpcional(peticion.fechaDeCorte(), "fechaDeCorte", fecha);
        Integer cuotas = peticion.nroDeCuotas();
        if (cuotas == null) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo 'nroDeCuotas'");
        }
        try {
            return new FraccionarEnCoactiva.Peticion(
                    numeroDeExpediente,
                    obligacionesDe(peticion.obligaciones()),
                    fecha,
                    corte,
                    cuotas,
                    porcentajeDe(peticion.cuotaInicial()),
                    fechaOpcional(peticion.primeraCuotaVence(), "primeraCuotaVence", fecha),
                    vacioAnulo(peticion.resolucion()));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    private static List<SeleccionDeObligacion> obligacionesDe(
            @Nullable List<PeticionDeConvenioCoactivo.PeticionDeObligacionAcogida> marcadas) {
        if (marcadas == null || marcadas.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Hay que marcar al menos una deuda: un convenio sin deuda acogida no fracciona"
                            + " nada");
        }
        List<SeleccionDeObligacion> seleccion = new ArrayList<>(marcadas.size());
        for (PeticionDeConvenioCoactivo.PeticionDeObligacionAcogida marcada : marcadas) {
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
     * <p>{@link Alicuota} y no {@code Porcentaje} porque el 0 % es admisible: la ordenanza puede
     * pactar un convenio sin entrada.
     */
    private static Alicuota porcentajeDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo 'cuotaInicial'");
        }
        String limpio = texto.strip().replace("%", "").strip();
        try {
            return Alicuota.de(limpio);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo 'cuotaInicial' es un porcentaje de 0 a 100: '" + texto + "'");
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
