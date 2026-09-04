package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeInternamientos;
import kamayuk.rentas.sanciones.aplicacion.LiberarVehiculoInternado;
import kamayuk.rentas.sanciones.aplicacion.RegistrarDescargo;
import kamayuk.rentas.sanciones.aplicacion.RegistrarInternamiento;
import kamayuk.rentas.sanciones.dominio.CriterioDeInternamiento;
import kamayuk.rentas.sanciones.dominio.EstadoDeInternamiento;
import kamayuk.rentas.sanciones.dominio.InternamientoEnConsulta;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * El depósito municipal por HTTP: la grilla, el ingreso y la liberación (#50, RF-064).
 *
 * <h2>La grilla no publica ningún importe</h2>
 *
 * <p>El prototipo dibuja «Tasa diaria S/» y «Custodia S/». Aquí no están: la tarifa de la custodia
 * es dato de la ordenanza (D-02b, abierta) y publicar una cifra compuesta con una tarifa inventada
 * sería peor que no publicarla —el administrado pagaría lo que la pantalla diga—. Lo que sí viaja
 * son los <b>días</b> y el <b>concepto</b> del TUPA; el importe lo pone la caja al cobrar, con la
 * tarifa vigente.
 *
 * <h2>Ningún {@code PUT} ni {@code PATCH}</h2>
 *
 * <p>Liberar un vehículo no es rellenar una fecha en la fila del ingreso: es un acto con su acta.
 * El verbo lo dice, y además {@code internamiento} no admite {@code UPDATE} desde V41.
 */
@RestController
@RequestMapping(Api.RAIZ + "/transito/internamientos")
public class InternamientosController {

    /** La opción del catálogo (NEG-03) que este controlador sirve, en sus tres verbos. */
    static final String ACCESO = "internamiento";

    private static final String ORDEN_POR_OMISION = "fechaIngreso";

    private final ConsultaDeInternamientos consulta;
    private final RegistrarInternamiento registrar;
    private final LiberarVehiculoInternado liberar;
    private final Clock reloj;

    public InternamientosController(
            ConsultaDeInternamientos consulta,
            RegistrarInternamiento registrar,
            LiberarVehiculoInternado liberar,
            Clock reloj) {
        this.consulta = consulta;
        this.registrar = registrar;
        this.liberar = liberar;
        this.reloj = reloj;
    }

    /** La grilla «Vehículos en depósito». */
    @GetMapping
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.LECTURA)
    public RespuestaPaginada<InternamientoResource> listar(
            @RequestParam(required = false) @Nullable String placa,
            @RequestParam(required = false) @Nullable String deposito,
            @RequestParam(required = false) @Nullable String estado,
            @RequestParam(required = false) @Nullable String aLaFecha,
            ParametrosDePaginacion paginacion) {

        LocalDate corte = fechaOHoy(aLaFecha);
        CriterioDeInternamiento criterio =
                new CriterioDeInternamiento(
                        placa,
                        deposito == null || "Todos".equalsIgnoreCase(deposito.strip())
                                ? null
                                : deposito,
                        estadoDe(estado));

        return RespuestaPaginada.de(
                consulta.listar(criterio, corte, paginacion.aPaginacion(ORDEN_POR_OMISION)),
                InternamientoResource::de);
    }

    /** Interna un vehículo y emite su acta. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.REGISTRO)
    public IngresoResource internar(@RequestBody PeticionDeInternamiento peticion) {
        Observacion observacion = PeticionesDeSanciones.observacionDe(peticion.observacion());
        try {
            RegistrarInternamiento.Internado internado =
                    registrar.internar(
                            new RegistrarInternamiento.Peticion(
                                    PeticionesDeSanciones.exigir(peticion.placa(), "placa"),
                                    peticion.vehiculoId(),
                                    PeticionesDeSanciones.vacioEsNulo(peticion.papeleta()),
                                    PeticionesDeSanciones.exigir(peticion.deposito(), "deposito"),
                                    instanteDe(peticion.fechaDeIngreso()),
                                    PeticionesDeSanciones.exigir(
                                            peticion.tasaDeCustodia(), "tasaDeCustodia"),
                                    PeticionesDeSanciones.exigir(peticion.motivo(), "motivo")),
                            formatoDe(peticion.formato()),
                            observacion);
            return IngresoResource.de(internado);
        } catch (RegistrarDescargo.PapeletaInexistente noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO, PeticionesDeSanciones.mensajeDe(noExiste));
        } catch (RegistrarInternamiento.VehiculoYaInternado yaEstaba) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO, PeticionesDeSanciones.mensajeDe(yaEstaba));
        } catch (IllegalArgumentException invalido) {
            throw PeticionesDeSanciones.invalido(invalido);
        }
    }

    /** Entrega el vehículo y emite el acta de liberación. */
    @PostMapping("/{placa}/liberacion")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(acceso = ACCESO, privilegio = Privilegio.REGISTRO)
    public LiberacionResource liberarVehiculo(
            @PathVariable String placa, @RequestBody PeticionDeLiberacion peticion) {

        Observacion observacion = PeticionesDeSanciones.observacionDe(peticion.observacion());
        try {
            LiberarVehiculoInternado.Liberado liberado =
                    liberar.liberar(
                            new LiberarVehiculoInternado.Peticion(
                                    placa,
                                    PeticionesDeSanciones.fechaDe(
                                            peticion.fechaDeLiberacion(), "fechaDeLiberacion"),
                                    PeticionesDeSanciones.exigir(
                                            peticion.reciboDeCustodia(), "reciboDeCustodia"),
                                    PeticionesDeSanciones.exigir(
                                            peticion.personaQueRetira(), "personaQueRetira"),
                                    PeticionesDeSanciones.exigir(
                                            peticion.documentoDeQuienRetira(),
                                            "documentoDeQuienRetira"),
                                    Boolean.TRUE.equals(peticion.soatVigenteAcreditado())),
                            formatoDe(peticion.formato()),
                            observacion);
            return LiberacionResource.de(liberado);
        } catch (LiberarVehiculoInternado.VehiculoNoInternado noEsta) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO, PeticionesDeSanciones.mensajeDe(noEsta));
        } catch (LiberarVehiculoInternado.CustodiaSinPagar sinPagar) {
            // 409 y no 422: la peticion esta bien formada; lo que no se cumple es un requisito
            // del estado —la custodia sigue sin cobrarse—, y quien opera lo arregla cobrandola,
            // no corrigiendo el cuerpo.
            throw new ProblemaDeNegocio(
                    CodigoDeError.CONFLICTO, PeticionesDeSanciones.mensajeDe(sinPagar));
        } catch (LiberarVehiculoInternado.LiberacionAnteriorAlIngreso
                | IllegalArgumentException invalido) {
            throw PeticionesDeSanciones.invalido(invalido);
        }
    }

    // ------------------------------------------------------------------

    private LocalDate fechaOHoy(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return LocalDate.now(reloj);
        }
        return PeticionesDeSanciones.fechaOpcional(texto, "aLaFecha");
    }

    private static @Nullable EstadoDeInternamiento estadoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank() || "Todos".equalsIgnoreCase(texto.strip())) {
            return null;
        }
        return PeticionesDeSanciones.enumeradoDe(
                EstadoDeInternamiento.class, texto.replace(' ', '_'), "estado");
    }

    private static Instant instanteDe(@Nullable String texto) {
        String limpio = PeticionesDeSanciones.exigir(texto, "fechaDeIngreso");
        try {
            return Instant.parse(limpio);
        } catch (DateTimeParseException noEsInstante) {
            // La pantalla manda a veces solo el dia; se toma su comienzo en UTC, que es la zona
            // con la que el resto del sistema interpreta los instantes.
            return PeticionesDeSanciones.fechaOpcional(limpio, "fechaDeIngreso")
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        }
    }

    private static FormatoDeDocumento formatoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return FormatoDeDocumento.PDF;
        }
        return PeticionesDeSanciones.enumeradoDe(FormatoDeDocumento.class, texto, "formato");
    }

    /**
     * El cuerpo de un ingreso al depósito. <b>Lista blanca</b>: lo que no está aquí no entra.
     *
     * @param observacion por qué se interna (regla 10, RNF-052)
     * @param placa la placa del vehículo
     * @param vehiculoId el vehículo del padrón, si está registrado
     * @param papeleta la papeleta que dispuso la medida preventiva, si la hubo
     * @param deposito dónde queda
     * @param fechaDeIngreso cuándo entró; instante ISO o día
     * @param tasaDeCustodia el código del concepto del TUPA con que se cobra la custodia
     * @param motivo por qué se interna, para el acta
     * @param formato en qué formato sale el acta; por omisión PDF
     */
    public record PeticionDeInternamiento(
            @Nullable String observacion,
            @Nullable String placa,
            @Nullable Long vehiculoId,
            @Nullable String papeleta,
            @Nullable String deposito,
            @Nullable String fechaDeIngreso,
            @Nullable String tasaDeCustodia,
            @Nullable String motivo,
            @Nullable String formato) {}

    /**
     * El cuerpo de una liberación. <b>Lista blanca</b>: lo que no está aquí no entra.
     *
     * @param observacion por qué se libera (regla 10, RNF-052)
     * @param fechaDeLiberacion el día de la entrega
     * @param reciboDeCustodia el recibo con que se pagó la custodia, como está impreso
     * @param personaQueRetira quién retira el vehículo
     * @param documentoDeQuienRetira su documento de identidad
     * @param soatVigenteAcreditado si se acreditó el SOAT vigente
     * @param formato en qué formato sale el acta; por omisión PDF
     */
    public record PeticionDeLiberacion(
            @Nullable String observacion,
            @Nullable String fechaDeLiberacion,
            @Nullable String reciboDeCustodia,
            @Nullable String personaQueRetira,
            @Nullable String documentoDeQuienRetira,
            @Nullable Boolean soatVigenteAcreditado,
            @Nullable String formato) {}

    /**
     * Una fila de la grilla.
     *
     * @param calculadoA el día con el que se contaron los días (regla 9, RNF-075)
     */
    public record InternamientoResource(
            long id,
            String placa,
            @Nullable String papeleta,
            String deposito,
            LocalDate fechaDeIngreso,
            @Nullable LocalDate fechaDeSalida,
            int dias,
            LocalDate calculadoA,
            String estado,
            String tasaDeCustodia,
            String acta) {

        static InternamientoResource de(InternamientoEnConsulta fila) {
            return new InternamientoResource(
                    fila.id(),
                    fila.placa(),
                    fila.numeroPapeleta(),
                    fila.deposito(),
                    fila.fechaIngreso(),
                    fila.fechaSalida(),
                    fila.dias(),
                    fila.calculadoA(),
                    fila.estado().name(),
                    fila.tasaCustodia(),
                    fila.acta());
        }
    }

    /** El vehículo internado y el acta que salió. */
    public record IngresoResource(
            long id,
            String placa,
            String deposito,
            String acta,
            String tasaDeCustodia,
            String formato,
            String resumen,
            String nombreDeArchivo) {

        static IngresoResource de(RegistrarInternamiento.Internado internado) {
            return new IngresoResource(
                    internado.internamiento().identificador(),
                    internado.internamiento().placa(),
                    internado.internamiento().deposito(),
                    internado.internamiento().acta(),
                    internado.internamiento().tasaCustodia(),
                    internado.acta().registro().formato().name(),
                    internado.acta().registro().resumen(),
                    internado.acta().nombreDeArchivo());
        }
    }

    /**
     * El vehículo liberado y el acta que salió.
     *
     * @param custodiaPagada lo que la caja acreditó, con la fecha del cobro (regla 9, RNF-075)
     */
    public record LiberacionResource(
            String placa,
            LocalDate fecha,
            int dias,
            String estado,
            String acta,
            CustodiaResource custodiaPagada,
            String formato,
            String resumen,
            String nombreDeArchivo) {

        static LiberacionResource de(LiberarVehiculoInternado.Liberado liberado) {
            return new LiberacionResource(
                    liberado.internamiento().placa(),
                    liberado.movimiento().fecha(),
                    liberado.movimiento().diasCustodia() == null
                            ? 0
                            : liberado.movimiento().diasCustodia(),
                    liberado.estado().name(),
                    liberado.movimiento().acta(),
                    new CustodiaResource(
                            liberado.custodia().numeroDeRecibo(),
                            liberado.custodia().codigoDeTasa(),
                            new kamayuk.rentas.web.ImporteActualizado(
                                    liberado.custodia().importe(), liberado.custodia().fecha())),
                    liberado.acta().registro().formato().name(),
                    liberado.acta().registro().resumen(),
                    liberado.acta().nombreDeArchivo());
        }
    }

    /** El recibo con que se acreditó la custodia, con su importe y su fecha. */
    public record CustodiaResource(
            String recibo, String concepto, kamayuk.rentas.web.ImporteActualizado importe) {}
}
