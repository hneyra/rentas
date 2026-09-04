package kamayuk.rentas.catastro.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.aplicacion.ConsultaDePredios;
import kamayuk.rentas.catastro.aplicacion.InscribirFicha;
import kamayuk.rentas.catastro.aplicacion.RegistrarOcupacion;
import kamayuk.rentas.catastro.dominio.CondicionDeTitularidad;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quien es dueno del predio y quien lo ocupa: {@code POST
 * /api/v1/catastro/predios/{predioId}/titulares} y las tres rutas de {@code …/inquilinos} (#490,
 * RF-005).
 *
 * <h2>Por que el alta de titular vive aqui y la lectura en rentas</h2>
 *
 * <p>{@code GET /catastro/predios/{predioId}/titulares} lo sirve {@code rentas} (#366) porque
 * <b>cruza catastro con el padron</b> y es el unico modulo que puede depender de los dos sin cerrar
 * un ciclo; y exige el permiso del padron, porque lo que devuelve es el identificador de una
 * persona.
 *
 * <p>El alta es otra cosa: es un <b>acto del catastro</b> —la fila vive en {@code titularidad}, que
 * es de catastro— y ya no cruza nada que catastro no cruce por su cuenta, porque {@code catastro}
 * depende del padron desde #16. Por eso exige {@code actualizacion_catastro}, la opcion del manual
 * donde se mantienen los datos del predio, y no {@code contribuyentes}: quien declara de quien es
 * un predio esta actualizando el catastro, no consultando el padron.
 *
 * <h2>La suma de cuotas la vigila la base</h2>
 *
 * <p>Aqui no hay ningun {@code if} que compruebe que las cuotas vigentes no pasen del 100 %: lo
 * sostiene un <b>disparador diferido</b>, y tiene que ser diferido para que una transferencia
 * —cerrar una cuota y abrir otra en la misma transaccion— sea posible (#16). Lo que este
 * controlador hace es dejar hablar al disparador y traducir su rechazo a un {@code 409}, sin
 * nombrar ni la tabla ni la restriccion.
 *
 * <p>Toda escritura exige la observacion del usuario (regla 10, RNF-052), ninguna recibe la
 * municipalidad (regla 2) y <b>nada se borra</b> (regla 4): la ocupacion se cierra con su fecha.
 */
@RestController
@RequestMapping(Api.RAIZ + "/catastro/predios/{predioId}")
@RequiereAcceso(acceso = OcupacionDelPredioController.ACCESO, privilegio = Privilegio.LECTURA)
public class OcupacionDelPredioController {

    /** La opcion del catalogo (NEG-03) donde se mantienen los datos del predio. */
    static final String ACCESO = "actualizacion_catastro";

    private final RegistrarOcupacion ocupacion;
    private final ConsultaDePredios consulta;
    private final Clock reloj;

    public OcupacionDelPredioController(
            RegistrarOcupacion ocupacion, ConsultaDePredios consulta, Clock reloj) {
        this.ocupacion = ocupacion;
        this.consulta = consulta;
        this.reloj = reloj;
    }

    /**
     * Registra una cuota de titularidad: el primer titular, o uno mas de una copropiedad.
     *
     * <p>{@code condicion} decide si hace falta {@code porcentaje}: solo {@code PROPIETARIO_UNICO}
     * lo es por el total. Declarar una copropiedad es registrar dos o mas cuotas que sumen 100 %.
     *
     * <p>Pasarse del 100 % es {@code 409}, y lo dice la base al confirmar — no un {@code if}.
     */
    @PostMapping("/titulares")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.REGISTRO)
    public TitularidadResource registrarTitular(
            @PathVariable long predioId, @RequestBody PeticionDeTitular peticion) {

        Observacion observacion = DeclaracionDeFicha.observacionDe(peticion.observacion());
        RegistrarOcupacion.DatosDelTitular datos =
                new RegistrarOcupacion.DatosDelTitular(
                        DeclaracionDeFicha.exigir(peticion.codContribuyente(), "codContribuyente"),
                        condicionDe(peticion.condicion()),
                        porcentajeDe(peticion.porcentaje()),
                        DeclaracionDeFicha.exigir(peticion.documentoOrigen(), "documentoOrigen"));

        return conLosErroresTraducidos(
                () ->
                        TitularidadResource.de(
                                ocupacion.registrarTitular(
                                        predioId,
                                        datos,
                                        desdeDe(peticion.vigenciaDesde()),
                                        observacion)));
    }

    /** Quien ocupa el predio a una fecha. Ausente, hoy: lo vigente se resuelve a esa fecha. */
    @GetMapping("/inquilinos")
    public List<InquilinoResource> inquilinos(
            @PathVariable long predioId, @RequestParam(required = false) @Nullable String fecha) {
        LocalDate cuando =
                fecha == null || fecha.isBlank() ? LocalDate.now(reloj) : fechaDe(fecha, "fecha");
        return consulta.inquilinosDe(predioId, cuando).stream().map(InquilinoResource::de).toList();
    }

    /** Alta de un inquilino: el manual lo registra para la cobranza de arbitrios (#31). */
    @PostMapping("/inquilinos")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.REGISTRO)
    public InquilinoResource registrarInquilino(
            @PathVariable long predioId, @RequestBody PeticionDeInquilino peticion) {

        Observacion observacion = DeclaracionDeFicha.observacionDe(peticion.observacion());
        RegistrarOcupacion.DatosDelInquilino datos =
                new RegistrarOcupacion.DatosDelInquilino(
                        DeclaracionDeFicha.exigir(peticion.codContribuyente(), "codContribuyente"),
                        DeclaracionDeFicha.vacioANulo(peticion.uso()),
                        desdeDe(peticion.vigenciaDesde()),
                        DeclaracionDeFicha.exigir(peticion.documentoOrigen(), "documentoOrigen"));

        return conLosErroresTraducidos(
                () ->
                        InquilinoResource.de(
                                ocupacion.registrarInquilino(predioId, datos, observacion)));
    }

    /**
     * Termina la ocupacion en una fecha. <b>No borra</b>: una determinacion de arbitrios anterior
     * pudo apoyarse en ella.
     *
     * <p>Exige {@code ELIMINACION} y no {@code MODIFICACION}, por lo mismo que la baja del predio:
     * es una baja logica, y el privilegio que el manual reserva para las bajas «gobierna la baja
     * —desactivar—, no un {@code DELETE}». Aqui se puede declarar en la anotacion porque el acto
     * tiene ruta propia y el guardia no necesita leer el cuerpo.
     */
    @PutMapping("/inquilinos/{inquilinoId}")
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.ELIMINACION)
    public InquilinoResource finalizarInquilino(
            @PathVariable long predioId,
            @PathVariable long inquilinoId,
            @RequestBody PeticionDeFinDeOcupacion peticion) {

        Observacion observacion = DeclaracionDeFicha.observacionDe(peticion.observacion());
        LocalDate hasta =
                peticion.vigenciaHasta() == null
                        ? LocalDate.now(reloj)
                        : fechaDe(peticion.vigenciaHasta(), "vigenciaHasta");

        return conLosErroresTraducidos(
                () ->
                        InquilinoResource.de(
                                ocupacion.finalizarInquilino(
                                        predioId, inquilinoId, hasta, observacion)));
    }

    // ------------------------------------------------------------------

    /**
     * Los errores del acto, traducidos una sola vez.
     *
     * <p>El rechazo del disparador diferido llega al confirmar la transaccion —o sea al volver del
     * caso de uso, no al escribir—, asi que se caza aqui y no dentro. Lo que sale es un {@code 409}
     * con el mensaje del disparador, que habla de porcentajes y no de tablas.
     */
    private static <T> T conLosErroresTraducidos(java.util.function.Supplier<T> acto) {
        try {
            return acto.get();
        } catch (RegistrarOcupacion.PredioInexistente
                | RegistrarOcupacion.OcupacionInexistente
                | InscribirFicha.ReferenciaInexistente noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO, DeclaracionDeFicha.mensajeDe(noExiste));
        } catch (RegistrarOcupacion.PredioRetirado retirado) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO, DeclaracionDeFicha.mensajeDe(retirado));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, DeclaracionDeFicha.mensajeDe(invalido));
        } catch (org.springframework.dao.DataAccessException
                | org.springframework.transaction.TransactionException delMotor) {
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, motivoDe(delMotor));
        }
    }

    /**
     * El motivo que da PostgreSQL, sin lo que hay alrededor.
     *
     * <p>El disparador diferido de la suma de cuotas lanza un {@code RAISE} cuyo mensaje habla de
     * porcentajes y no nombra ninguna tabla, asi que se puede devolver. Cualquier otro error del
     * motor sale con un texto de reserva: el mensaje de un choque de restriccion <b>si</b> nombra
     * la tabla y la restriccion, y eso no viaja (RNF-033).
     */
    private static String motivoDe(RuntimeException delMotor) {
        Throwable causa = delMotor;
        while (causa.getCause() != null) {
            causa = causa.getCause();
        }
        String mensaje = causa.getMessage();
        if (mensaje != null && mensaje.contains("100")) {
            return mensaje.lines().findFirst().orElse(mensaje).strip();
        }
        return "La operacion deja la titularidad del predio en un estado que no se admite";
    }

    private LocalDate desdeDe(@Nullable String texto) {
        return texto == null || texto.isBlank()
                ? LocalDate.now(reloj)
                : fechaDe(texto, "vigenciaDesde");
    }

    private static LocalDate fechaDe(String texto, String campo) {
        try {
            return LocalDate.parse(texto.strip());
        } catch (java.time.format.DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' va en formato AAAA-MM-DD: '" + texto + "'");
        }
    }

    private static CondicionDeTitularidad condicionDe(@Nullable String texto) {
        try {
            return CondicionDeTitularidad.valueOf(
                    DeclaracionDeFicha.exigir(texto, "condicion").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Condicion de titularidad desconocida: '" + texto + "'");
        }
    }

    /**
     * El porcentaje llega como <b>texto</b>, no como numero: un {@code double} del JSON perderia
     * escala antes de que nadie lo mire (regla 1).
     */
    private static @Nullable Porcentaje porcentajeDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return Porcentaje.de(texto.strip());
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Porcentaje no valido: '" + texto + "'");
        }
    }

    /**
     * El cuerpo de un alta de titular. <b>Lista blanca</b>: lo que no esta aqui no entra.
     *
     * <p>No lleva {@code vigenciaHasta}: la cuota que se abre esta abierta, y cerrarla es lo que
     * hace una transferencia (#{@code 29}). Una titularidad que naciera ya cerrada no diria de
     * quien es el predio hoy.
     */
    public record PeticionDeTitular(
            @Nullable String observacion,
            @Nullable String codContribuyente,
            @Nullable String condicion,
            @Nullable String porcentaje,
            @Nullable String vigenciaDesde,
            @Nullable String documentoOrigen) {}

    /** El cuerpo de un alta de inquilino. <b>Lista blanca</b>. */
    public record PeticionDeInquilino(
            @Nullable String observacion,
            @Nullable String codContribuyente,
            @Nullable String uso,
            @Nullable String vigenciaDesde,
            @Nullable String documentoOrigen) {}

    /** El cuerpo del fin de una ocupacion: la fecha y la observacion, nada mas. */
    public record PeticionDeFinDeOcupacion(
            @Nullable String observacion, @Nullable String vigenciaHasta) {}
}
