package kamayuk.rentas.rentas.infraestructura.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.rentas.aplicacion.ConsultaDeLaHojaDeDeclaracion;
import kamayuk.rentas.rentas.aplicacion.ConsultasDeRentas;
import kamayuk.rentas.rentas.aplicacion.RegistrarDeclaracionJurada;
import kamayuk.rentas.rentas.dominio.DeclaracionJurada;
import kamayuk.rentas.rentas.dominio.TipoDeDeclaracion;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Declaracion jurada: la consulta y los cuatro actos (RF-023, #28, #365).
 *
 * <ul>
 *   <li>{@code GET /api/v1/rentas/declaraciones/{djNro}} — la DJ ya presentada.
 *   <li>{@code POST /api/v1/rentas/declaraciones} — presentarla. <b>Es el acto que concilia</b>
 *       (ADR-0015 §3): a partir de el, el predio esta en el padron afecto del ejercicio y la
 *       lectura de #344 lo dice.
 *   <li>{@code POST /api/v1/rentas/declaraciones/{djNro}/rectificacion} — la rectificatoria.
 *   <li>{@code POST /api/v1/rentas/declaraciones/{djNro}/observacion} y {@code .../anulacion} — los
 *       dos actos de la administracion, que hasta #365 eran estados que solo la siembra podia
 *       fabricar.
 * </ul>
 *
 * <p>Se busca por numero y año: aunque desde V54 {@code dj_numero_uq} es unica en la municipalidad
 * entera, el contrato de {@code djNro} lleva el año desde que se derivo del prototipo —la pantalla
 * tiene su filtro «Año»— y quitarlo seria romper la ruta que la interfaz ya llama.
 *
 * <p><b>El numero no viaja en ningun cuerpo.</b> Lo pone el sistema, con el correlativo de {@code
 * dj_correlativo} y la plantilla parametrizada de D-09: si el cliente pudiera proponerlo, dos
 * ventanillas propondrian el mismo y la unica defensa seria el indice.
 */
@RestController
@RequestMapping(Api.RAIZ + "/rentas/declaraciones")
@RequiereAcceso(acceso = "declaracion_jurada", privilegio = Privilegio.LECTURA)
public class DeclaracionJuradaController {

    private final ConsultasDeRentas consultas;
    private final RegistrarDeclaracionJurada actos;
    private final ConsultaDeLaHojaDeDeclaracion hoja;
    private final java.time.Clock reloj;

    public DeclaracionJuradaController(
            ConsultasDeRentas consultas,
            RegistrarDeclaracionJurada actos,
            ConsultaDeLaHojaDeDeclaracion hoja,
            java.time.Clock reloj) {
        this.consultas = consultas;
        this.actos = actos;
        this.hoja = hoja;
        this.reloj = reloj;
    }

    /**
     * La hoja resumen que se imprime y se firma (#563).
     *
     * <p>Es el <b>unico documento del modulo pensado para salir en papel</b>, y todo lo que
     * consignaba venia del juego de datos de la maqueta: el nombre y el DNI de otra persona, dos
     * predios que no son suyos y un «total a pagar». Una vez impresa y firmada, una hoja asi no se
     * distingue de una correcta, y a diferencia de una pantalla nadie la vuelve a mirar contra la
     * base.
     *
     * <p>Devuelve lo que el sistema <b>tiene</b> —el declarante con su domicilio vigente a la
     * fecha, sus predios con su {@code %} de propiedad, y las cifras de la ultima determinacion del
     * ejercicio— y una lista {@code faltan} con lo que no puede consignar todavia, cada cosa con su
     * motivo. Un campo nulo es un campo que no hay: publicar cero seria escribir «no debe nada» en
     * un papel que alguien firma.
     *
     * <p>Una DJ que no existe es <b>404</b>, no una hoja vacia.
     */
    @GetMapping("/{djNro}/hoja")
    @Transactional(readOnly = true)
    public HojaDeDeclaracionResource hoja(
            @PathVariable String djNro,
            @RequestParam String ano,
            @RequestParam(required = false) @Nullable String fecha) {
        return hoja.de(djNro, ejercicioDe(ano), fechaDeCorteDe(fecha))
                .map(HojaDeDeclaracionResource::de)
                .orElseThrow(
                        () ->
                                new ProblemaDeNegocio(
                                        CodigoDeError.NO_ENCONTRADO,
                                        "No hay ninguna declaracion jurada con ese numero en ese"
                                                + " año"));
    }

    /**
     * La fecha de corte de la hoja; ausente, hoy.
     *
     * <p>No es {@code fechaDe}: aquella exige el campo —es la fecha de presentacion, que sin ella
     * no hay acto— y esta admite que falte, porque «a que dia se lee» tiene una respuesta por
     * omision razonable y la otra no.
     */
    private LocalDate fechaDeCorteDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return LocalDate.now(reloj);
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "La fecha va en formato AAAA-MM-DD: '" + texto + "'");
        }
    }

    /**
     * {@code @Transactional(readOnly = true)} directo en el controlador, y no un caso de uso
     * intermedio: es un passthrough de lectura sin ninguna regla que aplicar. Sin esta anotacion la
     * consulta falla en la base por falta de contexto —{@code RepositorioJdbc} no abre transaccion
     * propia, y sin una activa no hay {@code SET LOCAL}—, el mismo defecto que la prueba de
     * regresion de {@code CuentaCorrienteController} y {@code AltasBajasController} encontro para
     * esos dos endpoints (#164).
     */
    @GetMapping("/{djNro}")
    @Transactional(readOnly = true)
    public DeclaracionJuradaResource obtener(@PathVariable String djNro, @RequestParam String ano) {
        return consultas
                .declaracionPorNumero(djNro, ejercicioDe(ano))
                .map(DeclaracionJuradaResource::de)
                .orElseThrow(
                        () ->
                                new ProblemaDeNegocio(
                                        CodigoDeError.NO_ENCONTRADO,
                                        "No hay ninguna declaracion jurada con ese numero en ese"
                                                + " año"));
    }

    /**
     * Presenta una declaracion jurada nueva (RF-023, #365).
     *
     * <p>Lo que <b>no</b> viene en el cuerpo, porque lo resuelve el sistema: el numero (correlativo
     * y plantilla), la ficha catastral vigente a la fecha de presentacion, y {@code fueraDePlazo},
     * que sale de comparar esa fecha con el plazo del conjunto sellado. Un ejercicio sellado sin
     * ese parametro responde <b>422 nombrando la llave</b> {@code PLAZO:DECLARACION_JURADA}:
     * inventar un plazo clasificaria mal cada DJ que se registre (regla 5).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = "declaracion_jurada", privilegio = Privilegio.REGISTRO)
    public DeclaracionJuradaResource presentar(@RequestBody PeticionDeDeclaracion peticion) {
        return traduciendoErrores(
                () ->
                        actos.registrar(
                                ejercicioDe(exigir(peticion.ano(), "ano")),
                                exigir(peticion.codContribuyente(), "codContribuyente"),
                                tipoDe(peticion.tipo()),
                                peticion.predioId(),
                                peticion.vehiculoId(),
                                fechaDe(peticion.fechaPresentacion()),
                                observacionDe(peticion.observacion())));
    }

    /**
     * La rectificatoria: version nueva, la anterior {@code SUSTITUIDA} sin tocarle una columna
     * (regla 4).
     *
     * <p><b>Puede cambiar de predio</b>: el que se declaro por error deja de conciliar por esta
     * cadena y el que la rectificatoria declara pasa a hacerlo (ADR-0015 §1).
     */
    @PostMapping("/{djNro}/rectificacion")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = "declaracion_jurada", privilegio = Privilegio.MODIFICACION)
    public DeclaracionJuradaResource rectificar(
            @PathVariable String djNro,
            @RequestParam String ano,
            @RequestBody PeticionDeRectificacion peticion) {
        return traduciendoErrores(
                () ->
                        actos.rectificar(
                                djNro,
                                ejercicioDe(ano),
                                peticion.predioId(),
                                peticion.vehiculoId(),
                                fechaDe(peticion.fechaPresentacion()),
                                observacionDe(peticion.observacion())));
    }

    /**
     * La administracion objeta el contenido de una declaracion presentada (#365).
     *
     * <p>El predio <b>sigue conciliando</b>: observar no retira la declaracion (ADR-0015 §1).
     */
    @PostMapping("/{djNro}/observacion")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = "declaracion_jurada", privilegio = Privilegio.MODIFICACION)
    public DeclaracionJuradaResource observar(
            @PathVariable String djNro,
            @RequestParam String ano,
            @RequestBody PeticionDeActo peticion) {
        return traduciendoErrores(
                () ->
                        actos.observar(
                                djNro, ejercicioDe(ano), observacionDe(peticion.observacion())));
    }

    /**
     * La administracion anula una declaracion (#365).
     *
     * <p>A partir de aqui el predio <b>deja de conciliar</b> por ella, y el estado es terminal: una
     * anulada no revive.
     */
    @PostMapping("/{djNro}/anulacion")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = "declaracion_jurada", privilegio = Privilegio.MODIFICACION)
    public DeclaracionJuradaResource anular(
            @PathVariable String djNro,
            @RequestParam String ano,
            @RequestBody PeticionDeActo peticion) {
        return traduciendoErrores(
                () -> actos.anular(djNro, ejercicioDe(ano), observacionDe(peticion.observacion())));
    }

    // ------------------------------------------------------------------

    /**
     * La traduccion de los fallos del dominio a codigos de la API, en un solo sitio.
     *
     * <p>Los cuatro actos fallan por las mismas cuatro razones, y escribir el {@code catch} cuatro
     * veces garantizaria que el cuarto acabara devolviendo otro codigo que los tres primeros.
     */
    private DeclaracionJuradaResource traduciendoErrores(Acto acto) {
        try {
            return DeclaracionJuradaResource.de(acto.ejecutar());
        } catch (RegistrarDeclaracionJurada.DeclaracionInexistente noEsta) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noEsta));
        } catch (RegistrarDeclaracionJurada.ContribuyenteInexistente sinPadron) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(sinPadron));
        } catch (DeclaracionJurada.TransicionIlegal ilegal) {
            // 409 y no 422: la peticion es correcta, lo que no admite el acto es el estado en que
            // esta la declaracion. La interfaz distingue las dos cosas para saber si reintentar
            // tiene sentido.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(ilegal));
        } catch (RegistrarDeclaracionJurada.PlazoSinParametrizar
                | LectorDeParametros.EjercicioSinSellar falta) {
            // Falta publicar una cifra normativa, no un campo de la peticion: el 422 sale con
            // el miembro `parametroQueFalta` (#604, #691). Sin el, la interfaz no puede decir UNA
            // de las dos cosas —«corrige el formulario» o «hay que publicar una cifra»— y acaba
            // enumerando las dos, que es peor que no decir nada.
            throw FaltaPublicar.problema(falta);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    /** Lo que hace cada uno de los cuatro verbos, para poder envolverlos igual. */
    @FunctionalInterface
    private interface Acto {
        DeclaracionJurada ejecutar();
    }

    private static Ejercicio ejercicioDe(String ano) {
        try {
            return new Ejercicio(Integer.parseInt(ano.strip()));
        } catch (NumberFormatException noEsNumero) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "El año no es un numero");
        } catch (IllegalArgumentException fueraDeRango) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(fueraDeRango));
        }
    }

    private static TipoDeDeclaracion tipoDe(@Nullable String texto) {
        String tipo = exigir(texto, "tipo").toUpperCase(Locale.ROOT);
        if (TipoDeDeclaracion.RECTIFICATORIA.name().equals(tipo)) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Una rectificatoria no se presenta como declaracion nueva: se registra sobre la"
                            + " que sustituye, con POST /rentas/declaraciones/{djNro}/rectificacion");
        }
        try {
            return TipoDeDeclaracion.valueOf(tipo);
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El tipo de declaracion no es uno de los del formulario: HR, PU, PR o"
                            + " VEHICULAR");
        }
    }

    private static Observacion observacionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Toda modificacion exige la observacion del usuario: sin ella no se guarda");
        }
        try {
            return Observacion.de(texto);
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }
    }

    private static LocalDate fechaDe(@Nullable String texto) {
        try {
            return LocalDate.parse(exigir(texto, "fechaPresentacion").strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "La fecha va en formato AAAA-MM-DD: '" + texto + "'");
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
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }

    /**
     * El cuerpo de una declaracion jurada nueva. <b>Lista blanca</b>: lo que no esta aqui no entra.
     *
     * <p>No lleva {@code numero} —lo pone el sistema—, ni {@code fichaCatastralId} —lo resuelve el
     * sistema a la fecha de presentacion—, ni {@code fueraDePlazo} —se deriva del parametro
     * sellado—. Los tres son campos que un cliente podria proponer y ninguno es suyo.
     */
    public record PeticionDeDeclaracion(
            @Nullable String observacion,
            @Nullable String ano,
            @Nullable String codContribuyente,
            @Nullable String tipo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String fechaPresentacion) {}

    /**
     * El cuerpo de una rectificatoria. El tipo no viaja: una rectificatoria es {@code
     * RECTIFICATORIA} por construccion, y el ejercicio y el contribuyente los hereda de la DJ que
     * sustituye.
     */
    public record PeticionDeRectificacion(
            @Nullable String observacion,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String fechaPresentacion) {}

    /**
     * El cuerpo de un acto de la administracion: <b>solo</b> la observacion del usuario (regla 10,
     * RNF-052). Observar y anular no reciben ningun dato mas; el que decide el efecto es el verbo.
     */
    public record PeticionDeActo(@Nullable String observacion) {}
}
