package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.OrdenDeLosActos;
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.FueRepository;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacionRepository;
import kamayuk.rentas.licencias.dominio.TipoDeMovimientoDeEdificacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deja una licencia de edificacion sin efecto, con su resolucion y su motivo (#455).
 *
 * <h2>Por que existe</h2>
 *
 * <p>{@code ANULACION} estaba en el enumerado, en el {@code CHECK}, en el indice unico y en el
 * filtro de estado del reporte general —que admite {@code ?estado=ANULADA}—, y ninguna linea la
 * escribia: el reporte filtrado por anuladas salia siempre vacio y no habia manera de anular una
 * licencia aunque el enumerado lo anunciara. Es el patron de #214 y #243, y se arregla como #267
 * con la papeleta: escribiendo el acto.
 *
 * <h2>No se borra: se agrega</h2>
 *
 * <p>La licencia y su emision no se tocan (regla 4, RNF-051). Lo que se agrega es un movimiento
 * {@code ANULACION} con su fecha, su motivo y la resolucion que lo sustenta, y el estado se deriva
 * de el ({@code EstadoDelFue}): a una fecha anterior a la anulacion la licencia sigue vigente.
 *
 * <h2>Una sola anulacion, y lo decide la base</h2>
 *
 * <p>Se comprueba aqui para contestar un mensaje util en el caso normal, pero la garantia es {@code
 * edificacion_movimiento_anulacion_uq}: dos peticiones a la vez pasan las dos cualquier {@code if}.
 *
 * <h2>Sin recibo</h2>
 *
 * <p>Como la cancelacion de una licencia de funcionamiento: ninguna norma condiciona dejar sin
 * efecto un acto a un pago, y el constructor del movimiento lo exige asi.
 */
@Service
public class AnularLicenciaDeEdificacion {

    /** El {@code tipo} con que se guarda la resolucion en {@code documento_emitido}. */
    public static final String TIPO_DE_DOCUMENTO = "RES_ANULACION_EDIFICACION";

    private final FueRepository expedientes;
    private final MovimientoDeEdificacionRepository movimientos;
    private final DirectorioDeContribuyentes contribuyentes;
    private final EmitirDocumento documentos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public AnularLicenciaDeEdificacion(
            FueRepository expedientes,
            MovimientoDeEdificacionRepository movimientos,
            DirectorioDeContribuyentes contribuyentes,
            EmitirDocumento documentos,
            Auditoria auditoria,
            Clock reloj) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.contribuyentes = contribuyentes;
        this.documentos = documentos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Anula la licencia del expediente y emite la resolucion.
     *
     * @param expediente el numero del expediente cuya licencia se anula
     * @param fecha el dia de la anulacion; entra como argumento (regla 6)
     * @param motivo por que se anula; obligatorio
     * @param formato en que formato sale la resolucion
     * @param observacion por que se registra (regla 10, RNF-052)
     * @throws EmitirLicenciaDeEdificacion.ExpedienteInexistente si no hay ese expediente
     * @throws SinMotivo si el motivo falta
     * @throws SinLicenciaQueAnular si el expediente no tiene licencia otorgada
     * @throws YaEstabaAnulada si la licencia ya estaba anulada
     * @throws MovimientoDeEdificacionRepository.YaEstabaAnulada si otra anulacion gano la carrera
     * @throws kamayuk.rentas.dominio.ActoFueraDeOrden si la fecha es anterior al ultimo movimiento
     *     del expediente o posterior a hoy (#402)
     */
    @Transactional
    public Anulacion anular(
            String expediente,
            LocalDate fecha,
            String motivo,
            FormatoDeDocumento formato,
            Observacion observacion) {

        Objects.requireNonNull(fecha, "La fecha de la anulacion entra como argumento (regla 6)");
        Objects.requireNonNull(formato, "Hay que decir en que formato sale la resolucion");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
        String limpio = motivo == null ? "" : motivo.strip();
        if (limpio.isEmpty()) {
            throw new SinMotivo();
        }

        FueDeEdificacion fue =
                expedientes
                        .porExpediente(expediente)
                        .orElseThrow(
                                () ->
                                        new EmitirLicenciaDeEdificacion.ExpedienteInexistente(
                                                expediente));
        long fueId = fue.identificador();
        expedientes.bloquear(fueId);

        List<MovimientoDeEdificacion> historial = movimientos.deExpediente(fueId);
        String numeroDeLicencia =
                historial.stream()
                        .filter(m -> m.tipo() == TipoDeMovimientoDeEdificacion.EMISION)
                        .map(MovimientoDeEdificacion::numeroLicencia)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElseThrow(() -> new SinLicenciaQueAnular(fue.expediente()));
        if (historial.stream().anyMatch(m -> m.tipo() == TipoDeMovimientoDeEdificacion.ANULACION)) {
            throw new YaEstabaAnulada(numeroDeLicencia);
        }
        exigirEnOrden(numeroDeLicencia, historial, fecha);

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        TIPO_DE_DOCUMENTO,
                        Ejercicio.de(fecha),
                        numeroDeLicencia,
                        ModeloDelFue.deLaAnulacion(
                                fue, numeroDeLicencia, solicitanteDe(fue), fecha, limpio),
                        formato,
                        observacion);
        long documentoId =
                Objects.requireNonNull(
                        emision.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");

        MovimientoDeEdificacion registrado =
                movimientos.registrar(
                        MovimientoDeEdificacion.anulacion(
                                fueId,
                                fecha,
                                limpio,
                                documentoId,
                                emision.registro().numero(),
                                reloj.instant(),
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                "edificacion_movimiento",
                                String.valueOf(registrado.identificador()),
                                Operacion.BAJA,
                                observacion)
                        .con(
                                null,
                                "{\"licencia\":\""
                                        + numeroDeLicencia
                                        + "\",\"expediente\":\""
                                        + fue.expediente()
                                        + "\",\"estado\":\"ANULADA\",\"fecha\":\""
                                        + fecha
                                        + "\"}"));

        return new Anulacion(fue, numeroDeLicencia, registrado, emision);
    }

    /**
     * La anulacion no se fecha antes del ultimo acto del expediente —la emision o una revalidacion—
     * ni despues de hoy (#402): el estado se deriva a la fecha, y una anulacion fechada antes de su
     * propia licencia diria que la obra nunca estuvo autorizada.
     */
    private void exigirEnOrden(
            String numeroDeLicencia, List<MovimientoDeEdificacion> historial, LocalDate fecha) {
        List<OrdenDeLosActos.ActoPrevio> previos = new ArrayList<>();
        historial.stream()
                .max(Comparator.comparing(MovimientoDeEdificacion::fecha))
                .ifPresent(
                        ultimo ->
                                previos.add(
                                        new OrdenDeLosActos.ActoPrevio(
                                                "el ultimo acto de la licencia "
                                                        + numeroDeLicencia
                                                        + " ("
                                                        + ultimo.tipo().titulo()
                                                        + ")",
                                                ultimo.fecha())));
        OrdenDeLosActos.exigir(
                "la anulacion de la licencia " + numeroDeLicencia,
                fecha,
                LocalDate.now(reloj),
                previos);
    }

    private String solicitanteDe(FueDeEdificacion fue) {
        ResumenDeContribuyente titular =
                contribuyentes.porIds(Set.of(fue.contribuyenteId())).get(fue.contribuyenteId());
        return titular == null ? "(ya no esta en el padron)" : titular.nombre();
    }

    // ------------------------------------------------------------------

    /** Lo que la anulacion produjo. */
    public record Anulacion(
            FueDeEdificacion fue,
            String numeroDeLicencia,
            MovimientoDeEdificacion movimiento,
            EmitirDocumento.Emision resolucion) {}

    /** Una anulacion se motiva: sin motivo no hay resolucion que dictar. */
    public static final class SinMotivo extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinMotivo() {
            super("La anulacion de una licencia exige su motivo: es lo que la resolucion dice");
        }
    }

    /** El expediente no tiene licencia otorgada: no hay acto que dejar sin efecto. */
    public static final class SinLicenciaQueAnular extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinLicenciaQueAnular(String expediente) {
            super(
                    "El expediente "
                            + expediente
                            + " no tiene licencia otorgada: no hay ningun acto que anular");
        }
    }

    /** La licencia ya estaba anulada. */
    public static final class YaEstabaAnulada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        YaEstabaAnulada(String numeroDeLicencia) {
            super(
                    "La licencia "
                            + numeroDeLicencia
                            + " ya esta anulada: una segunda resolucion de anulacion sobre la"
                            + " misma licencia se contradice con la primera");
        }
    }
}
