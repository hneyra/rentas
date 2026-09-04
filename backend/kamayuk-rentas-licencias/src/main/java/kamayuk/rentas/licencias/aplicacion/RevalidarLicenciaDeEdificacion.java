package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.FueRepository;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacionRepository;
import kamayuk.rentas.licencias.dominio.TipoDeMovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.VigenciaDeLaLicencia;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import kamayuk.rentas.tesoreria.RecibosDeTramite;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revalida una licencia de edificacion: le agrega un tramo de vigencia (#48 AC 4, RF-113).
 *
 * <h2>Las dos vigencias quedan, y cada una dice de donde vino</h2>
 *
 * <p>Es el AC 4 entero. La revalidacion <b>no sustituye</b> la vigencia original: agrega la
 * siguiente en {@code edificacion_vigencia}, con su propio {@code orden} y apuntando al movimiento
 * que la concedio. Las dos se leen juntas y se imprimen juntas.
 *
 * <p>V4 pretendia resolverlo con dos columnas —{@code vigencia_hasta} y {@code revalidacion_hasta}—
 * y no habria bastado ni para dos: no dicen <b>que acto</b> concedio cada plazo, que es justo lo
 * que hace falta cuando alguien pregunta por que una obra sigue autorizada en 2029. V43 las retira.
 *
 * <h2>La revalidacion es su propio expediente</h2>
 *
 * <p>Llega como un FUE de tipo {@code REVALIDACION_DE_LICENCIA} que nombra la licencia original. El
 * tramo nuevo se le concede a la <b>original</b>, y el movimiento queda colgado del expediente de
 * la revalidacion: asi es visible que el plazo se prorrogo por un tramite aparte, con su propio
 * recibo y su propia resolucion.
 *
 * <h2>Se cobra en caja antes (AC 5)</h2>
 *
 * <p>El derecho de la revalidacion es su propio concepto del TUPA, y se comprueba igual que el de
 * la emision: por la API publica de {@code tesoreria}, contra el concepto que el conjunto sellado
 * nombra.
 */
@Service
public class RevalidarLicenciaDeEdificacion {

    /** El {@code tipo} con que se guarda la resolucion en {@code documento_emitido}. */
    public static final String TIPO_DE_DOCUMENTO = "RES_REVALIDACION_EDIFICACION";

    private final FueRepository expedientes;
    private final MovimientoDeEdificacionRepository movimientos;
    private final RecibosDeTramite recibos;
    private final DirectorioDeContribuyentes contribuyentes;
    private final DerechosDeTramiteParametrizados derechos;
    private final EmitirDocumento documentos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RevalidarLicenciaDeEdificacion(
            FueRepository expedientes,
            MovimientoDeEdificacionRepository movimientos,
            RecibosDeTramite recibos,
            DirectorioDeContribuyentes contribuyentes,
            DerechosDeTramiteParametrizados derechos,
            EmitirDocumento documentos,
            Auditoria auditoria,
            Clock reloj) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.recibos = recibos;
        this.contribuyentes = contribuyentes;
        this.derechos = derechos;
        this.documentos = documentos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Revalida la licencia original que nombra el expediente de revalidacion.
     *
     * @param expedienteDeRevalidacion el FUE de tipo {@code REVALIDACION_DE_LICENCIA}
     * @param fecha el dia del acto; entra como argumento (regla 6)
     * @param nuevaVigenciaHasta hasta cuando rige el tramo nuevo. Entra como dato del acto: el
     *     plazo de la prorroga lo fija la Ley 29090 con una cifra, y ninguna cifra normativa se
     *     compila (regla 5)
     * @param numeroDeRecibo el recibo de caja de tasas del derecho
     */
    @Transactional
    public Revalidacion revalidar(
            String expedienteDeRevalidacion,
            LocalDate fecha,
            LocalDate nuevaVigenciaHasta,
            String numeroDeRecibo,
            FormatoDeDocumento formato,
            Observacion observacion) {

        Objects.requireNonNull(fecha, "La fecha del acto entra como argumento (regla 6)");
        Objects.requireNonNull(nuevaVigenciaHasta, "La revalidacion dice hasta cuando prorroga");
        Objects.requireNonNull(formato, "Hay que decir en que formato sale la resolucion");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        FueDeEdificacion revalidacion =
                expedientes
                        .porExpediente(
                                expedienteDeRevalidacion == null
                                        ? ""
                                        : expedienteDeRevalidacion.strip())
                        .orElseThrow(
                                () ->
                                        new EmitirLicenciaDeEdificacion.ExpedienteInexistente(
                                                expedienteDeRevalidacion));

        if (revalidacion.tipoTramite()
                != kamayuk.rentas.licencias.dominio.TipoDeTramiteDeEdificacion
                        .REVALIDACION_DE_LICENCIA) {
            throw new NoEsUnaRevalidacion(revalidacion);
        }

        long originalId =
                Objects.requireNonNull(
                        revalidacion.licenciaOrigenId(),
                        "Una revalidacion siempre nombra su licencia original");
        FueDeEdificacion original =
                expedientes
                        .porId(originalId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "La licencia original del expediente "
                                                        + revalidacion.expediente()
                                                        + " ya no esta"));

        MovimientoDeEdificacion emisionOriginal =
                movimientos
                        .emisionDe(originalId)
                        .orElseThrow(() -> new OriginalSinLicencia(original.expediente()));
        String numeroDeLicencia =
                Objects.requireNonNull(
                        emisionOriginal.numeroLicencia(), "Una emision siempre numera la licencia");

        List<VigenciaDeLaLicencia> anteriores = movimientos.vigenciasDe(originalId);
        for (VigenciaDeLaLicencia tramo : anteriores) {
            if (!nuevaVigenciaHasta.isAfter(tramo.hasta())) {
                throw new ProrrogaQueNoProrroga(
                        numeroDeLicencia, tramo.hasta(), nuevaVigenciaHasta);
            }
        }

        ResumenDeContribuyente solicitante = solicitanteDe(revalidacion);

        String concepto = derechos.aLaFechaDe(fecha).paraLaRevalidacion();
        ReciboDeTramite recibo =
                ComprobacionDelDerecho.exigir(
                        recibos,
                        numeroDeRecibo,
                        solicitante.id(),
                        concepto,
                        "revalidacion de licencia de edificacion");

        // El tramo nuevo empieza el dia siguiente al ultimo que ya estaba: si empezara el dia del
        // acto, dos tramos se solaparian y la licencia diria estar vigente dos veces el mismo dia.
        LocalDate ultimoDia =
                anteriores.stream()
                        .map(VigenciaDeLaLicencia::hasta)
                        .max(LocalDate::compareTo)
                        .orElse(fecha);
        LocalDate desde = ultimoDia.plusDays(1);

        List<VigenciaDeLaLicencia> conLaNueva = new ArrayList<>(anteriores);
        conLaNueva.add(
                new VigenciaDeLaLicencia(
                        null, originalId, 0L, anteriores.size() + 1, desde, nuevaVigenciaHasta));

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        TIPO_DE_DOCUMENTO,
                        Ejercicio.de(fecha),
                        numeroDeLicencia,
                        ModeloDelFue.deLaRevalidacion(
                                original,
                                numeroDeLicencia,
                                solicitante.nombre(),
                                conLaNueva,
                                fecha,
                                recibo.numero()),
                        formato,
                        observacion);

        long documentoId =
                Objects.requireNonNull(
                        emision.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");

        Instant ahora = reloj.instant();
        MovimientoDeEdificacion registrado =
                movimientos.registrar(
                        MovimientoDeEdificacion.revalidacion(
                                revalidacion.identificador(),
                                fecha,
                                recibo.reciboId(),
                                documentoId,
                                emision.registro().numero(),
                                ahora,
                                observacion));

        VigenciaDeLaLicencia concedida =
                movimientos.conceder(
                        originalId,
                        registrado.identificador(),
                        new VigenciaDeLaLicencia(
                                null,
                                originalId,
                                registrado.identificador(),
                                anteriores.size() + 1,
                                desde,
                                nuevaVigenciaHasta));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fecha,
                                "edificacion_vigencia",
                                String.valueOf(concedida.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(
                                null,
                                "{\"licencia\":\""
                                        + numeroDeLicencia
                                        + "\",\"expediente\":\""
                                        + revalidacion.expediente()
                                        + "\",\"tramo\":"
                                        + concedida.orden()
                                        + ",\"hasta\":\""
                                        + concedida.hasta()
                                        + "\"}"));

        return new Revalidacion(
                original, revalidacion, numeroDeLicencia, registrado, concedida, emision);
    }

    private ResumenDeContribuyente solicitanteDe(FueDeEdificacion fue) {
        Map<Long, ResumenDeContribuyente> padron =
                contribuyentes.porIds(Set.of(fue.contribuyenteId()));
        ResumenDeContribuyente solicitante = padron.get(fue.contribuyenteId());
        if (solicitante == null) {
            throw new IllegalStateException(
                    "El expediente "
                            + fue.expediente()
                            + " es de un contribuyente que el padron ya no tiene");
        }
        return solicitante;
    }

    // ------------------------------------------------------------------

    /**
     * Lo que la revalidacion produjo.
     *
     * @param original el expediente de la licencia revalidada, <b>intacto</b>
     * @param expedienteDeRevalidacion el expediente del tramite
     * @param numeroDeLicencia el numero de la licencia; no cambia
     * @param movimiento el acto de revalidacion
     * @param vigencia el tramo nuevo
     * @param resolucion los bytes de la resolucion y su registro
     */
    public record Revalidacion(
            FueDeEdificacion original,
            FueDeEdificacion expedienteDeRevalidacion,
            String numeroDeLicencia,
            MovimientoDeEdificacion movimiento,
            VigenciaDeLaLicencia vigencia,
            EmitirDocumento.Emision resolucion) {

        public TipoDeMovimientoDeEdificacion tipo() {
            return movimiento.tipo();
        }
    }

    /** El expediente no es un tramite de revalidacion. */
    public static final class NoEsUnaRevalidacion extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        NoEsUnaRevalidacion(FueDeEdificacion fue) {
            super(
                    "El expediente "
                            + fue.expediente()
                            + " es un tramite de "
                            + fue.tipoTramite().etiqueta().toLowerCase(java.util.Locale.ROOT)
                            + ", no una revalidacion: prorrogar el plazo de una licencia con un"
                            + " expediente que pedia otra cosa dejaria la vigencia sin acto que la"
                            + " explique");
        }
    }

    /** La licencia que se pretende revalidar nunca se otorgo. */
    public static final class OriginalSinLicencia extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        OriginalSinLicencia(String expediente) {
            super(
                    "El expediente "
                            + expediente
                            + " todavia no tiene licencia otorgada, asi que no hay ningun plazo que"
                            + " prorrogar");
        }
    }

    /** La prorroga no llega mas alla de lo que ya estaba concedido. */
    public static final class ProrrogaQueNoProrroga extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ProrrogaQueNoProrroga(String numero, LocalDate yaVigenteHasta, LocalDate pedida) {
            super(
                    "La licencia "
                            + numero
                            + " ya rige hasta el "
                            + yaVigenteHasta
                            + " y la revalidacion pide hasta el "
                            + pedida
                            + ": un tramo que no pasa del anterior no prorroga nada, y cobrarle al"
                            + " administrado un derecho por el seria cobrarle por nada");
        }
    }
}
