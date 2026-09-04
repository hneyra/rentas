package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.Anuncio;
import kamayuk.rentas.licencias.dominio.AnuncioRepository;
import kamayuk.rentas.licencias.dominio.EstadoDelAnuncio;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncio;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncioRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prorroga una autorizacion de anuncio por otro ejercicio, con su tasa (#51, RF-114).
 *
 * <h2>Renovar es agregar, no editar</h2>
 *
 * <p>La fila de {@code anuncio} <b>no se toca</b>. Ni siquiera se podria: V45 le retira el {@code
 * UPDATE} y {@code DELETE} nunca lo tuvo. La nueva vigencia viaja en el movimiento, y {@code
 * EstadoDelAnuncio.vigenciaSegun} es quien resuelve «hasta cuando rige hoy». Es lo mismo que hace
 * {@code CancelarLicencia} con el estado, aplicado a una fecha.
 *
 * <h2>Un anuncio cesado no se renueva: ahi esta el AC 3</h2>
 *
 * <p>«El cese detiene la generacion de deuda futura y no borra la pasada». La primera mitad es esta
 * guarda: renovar exige que el estado derivado <b>a la fecha del acto</b> admita renovacion, y ni
 * CESADO ni RETIRADO lo admiten. La segunda mitad no se decide aqui —no hay ninguna reversion ni
 * ningun borrado en toda la clase—: la sostienen la inmutabilidad del libro (V2) y las tablas
 * protegidas del escaner de fuentes.
 *
 * <h2>Y una renovacion por ejercicio, decidido por la base</h2>
 *
 * <p>{@code anuncio_movimiento_cargo_uq} es un indice unico sobre {@code referencia_cargo}, que
 * lleva el ejercicio dentro. Dos renovaciones del mismo anuncio para el mismo año son dos
 * peticiones legitimamente distintas —otra fecha, otra clave de idempotencia— y el indice es lo
 * unico que las separa; diez peticiones simultaneas pasan las diez por cualquier {@code if}.
 */
@Service
public class RenovarAnuncio {

    private static final String TABLA_AUDITADA = "anuncio_movimiento";

    private final AnuncioRepository anuncios;
    private final MovimientoDeAnuncioRepository movimientos;
    private final TasaDeAnunciosParametrizada tasas;
    private final GeneradorDeCargos cargos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RenovarAnuncio(
            AnuncioRepository anuncios,
            MovimientoDeAnuncioRepository movimientos,
            TasaDeAnunciosParametrizada tasas,
            GeneradorDeCargos cargos,
            Auditoria auditoria,
            Clock reloj) {
        this.anuncios = anuncios;
        this.movimientos = movimientos;
        this.tasas = tasas;
        this.cargos = cargos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Renueva la autorizacion y genera el cargo del ejercicio que se renueva.
     *
     * @param numeroDeAutorizacion el numero impreso de la autorizacion
     * @param fecha el dia de la renovacion; entra como argumento (regla 6)
     * @param vigenciaHasta hasta cuando queda prorrogada
     * @param observacion por que se registra (regla 10, RNF-052)
     * @throws AnuncioInexistente si no hay ninguna autorizacion con ese numero
     * @throws NoSeRenueva si el anuncio esta cesado o retirado a esa fecha
     * @throws TasaDeAnunciosParametrizada.TasaSinParametrizar si la ordenanza sellada del ejercicio
     *     que se renueva no tarifa esa clase (regla 5, D-02b, #199)
     */
    @Transactional
    public Renovacion renovar(
            String numeroDeAutorizacion,
            LocalDate fecha,
            @Nullable LocalDate vigenciaHasta,
            Observacion observacion) {

        Objects.requireNonNull(fecha, "La fecha de la renovacion entra como argumento (regla 6)");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        Anuncio anuncio =
                anuncios.porNumero(numeroDeAutorizacion)
                        .orElseThrow(() -> new AnuncioInexistente(numeroDeAutorizacion));

        List<MovimientoDeAnuncio> historial = movimientos.deAnuncio(anuncio.identificador());
        EstadoDelAnuncio actual =
                EstadoDelAnuncio.derivarDe(
                        historial, EstadoDelAnuncio.vigenciaSegun(historial, fecha), fecha);
        if (!actual.admiteRenovacion()) {
            throw new NoSeRenueva(anuncio.numero(), actual, fecha);
        }
        if (fecha.isBefore(anuncio.fechaAutorizacion())) {
            throw new AnteriorALaAutorizacion(anuncio.numero(), anuncio.fechaAutorizacion(), fecha);
        }
        if (vigenciaHasta != null && vigenciaHasta.isBefore(fecha)) {
            throw new VigenciaHaciaAtras(anuncio.numero(), fecha, vigenciaHasta);
        }

        Ejercicio ejercicio = Ejercicio.de(fecha);
        Dinero tasa = tasas.aLaFechaDe(fecha).paraLaClase(anuncio.clase());
        String referencia = MovimientoDeAnuncio.referenciaDelCargo(anuncio.numero(), ejercicio);

        // Mismo orden que en el registro y por el mismo motivo: el indice unico del movimiento
        // rechaza el segundo devengo del ejercicio ANTES de que el libro reciba nada.
        Instant ahora = reloj.instant();
        MovimientoDeAnuncio renovacion =
                movimientos.registrar(
                        MovimientoDeAnuncio.renovacion(
                                anuncio.identificador(),
                                fecha,
                                ejercicio,
                                referencia,
                                tasa,
                                vigenciaHasta,
                                ahora,
                                observacion));

        cargos.generarCargo(
                ejercicio,
                anuncio.contribuyenteId(),
                RegistrarAnuncio.TRIBUTO,
                null,
                anuncio.predioId(),
                null,
                referencia,
                tasa,
                fecha,
                "RENOVACION-" + anuncio.numero(),
                observacion);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fecha,
                                TABLA_AUDITADA,
                                String.valueOf(renovacion.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(anuncio, ejercicio, tasa, referencia)));

        return new Renovacion(anuncio, renovacion);
    }

    private static String descripcion(
            Anuncio anuncio, Ejercicio ejercicio, Dinero tasa, String referencia) {
        return "{\"numero\":\""
                + anuncio.numero()
                + "\",\"ejercicio\":"
                + ejercicio.valor()
                + ",\"tasa\":"
                + tasa.valor().toPlainString()
                + ",\"referenciaDelCargo\":\""
                + referencia
                + "\"}";
    }

    // ------------------------------------------------------------------

    /** Lo que la renovacion produjo. */
    public record Renovacion(Anuncio anuncio, MovimientoDeAnuncio movimiento) {}

    /** No hay ninguna autorizacion con ese numero en esta municipalidad. */
    public static final class AnuncioInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        AnuncioInexistente(String numero) {
            super("No hay ninguna autorizacion de anuncio " + numero + " en esta municipalidad");
        }
    }

    /**
     * El anuncio esta cesado o retirado: no se renueva, y por tanto no devenga mas tasa.
     *
     * <p>Es el AC 3 de #51 dicho en una excepcion. Lo que <b>no</b> ocurre es que la deuda anterior
     * desaparezca: sigue asentada en el libro, que es inmutable.
     */
    public static final class NoSeRenueva extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        NoSeRenueva(String numero, EstadoDelAnuncio estado, LocalDate fecha) {
            super(
                    "La autorizacion "
                            + numero
                            + " esta "
                            + estado
                            + " al "
                            + fecha
                            + ": un anuncio cesado no se renueva, y por tanto no devenga mas tasa."
                            + " La ya devengada no se toca (regla 4, RNF-051)");
        }
    }

    /** La renovacion no puede ser anterior a la autorizacion que prorroga. */
    public static final class AnteriorALaAutorizacion extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        AnteriorALaAutorizacion(String numero, LocalDate autorizacion, LocalDate renovacion) {
            super(
                    "La autorizacion "
                            + numero
                            + " se emitio el "
                            + autorizacion
                            + " y no puede renovarse el "
                            + renovacion
                            + ": un acto no prorroga a otro que todavia no existia");
        }
    }

    /** La nueva vigencia termina antes de la renovacion que la fija. */
    public static final class VigenciaHaciaAtras extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        VigenciaHaciaAtras(String numero, LocalDate fecha, LocalDate vigenciaHasta) {
            super(
                    "La renovacion de "
                            + numero
                            + " del "
                            + fecha
                            + " no puede vencer el "
                            + vigenciaHasta
                            + ": una prorroga que termina antes de empezar cobra una tasa por nada");
        }
    }
}
