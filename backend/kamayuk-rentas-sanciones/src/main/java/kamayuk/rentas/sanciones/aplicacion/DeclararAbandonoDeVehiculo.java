package kamayuk.rentas.sanciones.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ZonaHoraria;
import kamayuk.rentas.sanciones.dominio.EstadoDeInternamiento;
import kamayuk.rentas.sanciones.dominio.Internamiento;
import kamayuk.rentas.sanciones.dominio.InternamientoRepository;
import kamayuk.rentas.sanciones.dominio.MovimientoDeInternamiento;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import kamayuk.rentas.sanciones.dominio.TipoDeMovimientoDeInternamiento;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Declara el abandono de un vehículo internado en el depósito municipal (#454, RF-064).
 *
 * <p>Hasta #454 el estado {@link EstadoDeInternamiento#EN_ABANDONO} existía —en el enumerado, en el
 * filtro de la grilla, en el {@code CHECK} de la base y en el modelo del acta— y no había acto que
 * lo produjera: la fábrica {@link MovimientoDeInternamiento#abandono} no tenía llamador, y un
 * vehículo que llevaba meses en el depósito seguía saliendo {@code INTERNADO}. Este es ese acto,
 * con su acta, igual que la liberación: quien opera lo registra, con su observación (regla 10). Un
 * umbral de días a partir del cual se declare solo es otra decisión, y no está aquí.
 *
 * <p><b>Solo agrega</b> (regla 4): una fila de {@code internamiento_movimiento}. Un abandono no se
 * deshace editándolo; el vehículo abandonado todavía se puede liberar, y la liberación gana en la
 * derivación del estado. Dos abandonos del mismo internamiento los rechaza aquí la lectura del
 * historial, y detrás {@code internamiento_abandono_uq} (V40), como {@code
 * internamiento_liberacion_uq} con la liberación.
 */
@Service
public class DeclararAbandonoDeVehiculo {

    private static final String TABLA_AUDITADA = "internamiento_movimiento";

    private final InternamientoRepository internamientos;
    private final PapeletaRepository papeletas;
    private final EmitirDocumento documentos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public DeclararAbandonoDeVehiculo(
            InternamientoRepository internamientos,
            PapeletaRepository papeletas,
            EmitirDocumento documentos,
            Auditoria auditoria,
            Clock reloj) {
        this.internamientos = internamientos;
        this.papeletas = papeletas;
        this.documentos = documentos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Declara el abandono del vehículo de esa placa y emite el acta.
     *
     * @param placa la placa del vehículo internado
     * @param fecha el día de la declaración
     * @param formato en qué formato sale el acta
     * @param observacion por qué se declara (regla 10, RNF-052)
     * @throws LiberarVehiculoInternado.VehiculoNoInternado si la placa no tiene internamiento
     *     vigente
     * @throws YaEnAbandono si ese internamiento ya se declaró en abandono
     * @throws AbandonoAnteriorAlIngreso si la fecha es anterior a la de entrada
     */
    @Transactional
    public Declarado declarar(
            String placa, LocalDate fecha, FormatoDeDocumento formato, Observacion observacion) {
        Objects.requireNonNull(placa, "Falta la placa del vehiculo");
        Objects.requireNonNull(fecha, "Falta la fecha de la declaracion");

        Internamiento internamiento =
                internamientos
                        .vigenteDePlaca(placa)
                        .orElseThrow(() -> new LiberarVehiculoInternado.VehiculoNoInternado(placa));
        if (EstadoDeInternamiento.delHistorial(
                        internamientos.movimientosDe(internamiento.identificador()))
                == EstadoDeInternamiento.EN_ABANDONO) {
            throw new YaEnAbandono(internamiento);
        }
        // El dia del ingreso en la zona del producto, como en la liberacion (#273).
        LocalDate ingreso = ZonaHoraria.diaDe(internamiento.fechaIngreso());
        if (fecha.isBefore(ingreso)) {
            throw new AbandonoAnteriorAlIngreso(internamiento, fecha);
        }
        int dias = (int) Math.max(0, ChronoUnit.DAYS.between(ingreso, fecha));
        Papeleta papeleta = papeletaDe(internamiento);

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        TipoDeMovimientoDeInternamiento.ABANDONO.tipoDeDocumento(),
                        Ejercicio.de(fecha),
                        internamiento.placa(),
                        ModeloDelActaDeInternamiento.delMovimiento(
                                internamiento,
                                papeleta == null ? null : papeleta.numero(),
                                TipoDeMovimientoDeInternamiento.ABANDONO,
                                fecha,
                                dias,
                                null,
                                null,
                                false,
                                null),
                        formato,
                        observacion);

        MovimientoDeInternamiento guardado =
                internamientos.registrar(
                        MovimientoDeInternamiento.abandono(
                                internamiento.identificador(),
                                fecha,
                                emision.registro().numero(),
                                Objects.requireNonNull(
                                        emision.registro().id(),
                                        "Un documento recien emitido vuelve con su identificador"),
                                dias,
                                reloj.instant(),
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                TABLA_AUDITADA,
                                String.valueOf(guardado.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(
                                null,
                                "{\"placa\":\""
                                        + internamiento.placa()
                                        + "\",\"tipo\":\"ABANDONO\",\"acta\":\""
                                        + guardado.acta()
                                        + "\",\"dias\":"
                                        + dias
                                        + "}"));

        List<MovimientoDeInternamiento> historial =
                internamientos.movimientosDe(internamiento.identificador());
        return new Declarado(
                internamiento, guardado, emision, EstadoDeInternamiento.delHistorial(historial));
    }

    private @Nullable Papeleta papeletaDe(Internamiento internamiento) {
        Long papeletaId = internamiento.papeletaId();
        return papeletaId == null ? null : papeletas.porId(papeletaId).orElse(null);
    }

    /**
     * El abandono declarado, con el acta que salió.
     *
     * @param internamiento el ingreso que se declara abandonado
     * @param movimiento la fila registrada
     * @param acta los bytes del acta y su registro
     * @param estado el estado en que queda el internamiento
     */
    public record Declarado(
            Internamiento internamiento,
            MovimientoDeInternamiento movimiento,
            EmitirDocumento.Emision acta,
            EstadoDeInternamiento estado) {}

    /**
     * Ese internamiento ya está declarado en abandono: una segunda acta diría lo mismo dos veces.
     */
    public static final class YaEnAbandono extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        YaEnAbandono(Internamiento internamiento) {
            super(
                    "El vehiculo de placa "
                            + internamiento.placa()
                            + " ya esta declarado en abandono: el acta de este internamiento ya"
                            + " existe");
        }
    }

    /** La declaración es anterior al ingreso del vehículo. */
    public static final class AbandonoAnteriorAlIngreso extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        AbandonoAnteriorAlIngreso(Internamiento internamiento, LocalDate fecha) {
            super(
                    "El vehiculo de placa "
                            + internamiento.placa()
                            + " entro el "
                            + internamiento.fechaIngreso()
                            + ": no se pudo declarar abandonado el "
                            + fecha);
        }
    }
}
