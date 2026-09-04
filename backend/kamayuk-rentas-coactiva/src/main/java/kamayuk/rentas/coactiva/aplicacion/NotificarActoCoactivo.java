package kamayuk.rentas.coactiva.aplicacion;

import java.time.LocalDate;
import java.util.Locale;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.coactiva.dominio.ActoCoactivo;
import kamayuk.rentas.coactiva.dominio.ActoCoactivoRepository;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.NotificacionCoactiva;
import kamayuk.rentas.coactiva.dominio.NotificacionCoactivaRepository;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;
import kamayuk.rentas.dominio.CalendarioHabil;
import kamayuk.rentas.dominio.Exigibilidad;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra la diligencia de notificacion de un acto coactivo, con su acuse (#41, RF-103).
 *
 * <h2>De aqui sale el derecho a la medida cautelar</h2>
 *
 * <p>El art. 14.1 de la Ley 26979 concede al obligado siete dias habiles <b>desde que se le
 * notifica la REC-1</b>; solo despues se pueden dictar las medidas cautelares. Esa cuenta se
 * resuelve aqui, una vez, y su resultado queda escrito en {@code notificacion.exigible_desde} junto
 * con el conjunto sellado del que salio el plazo. La REC-2 lo <b>copia</b> de ahi; no lo recalcula.
 *
 * <p>El plazo entra por {@link PlazosCoactivosParametrizados}, nunca como constante: un «7»
 * compilado obligaria a desplegar para seguir a la norma, y recalcularia con la cifra de hoy los
 * expedientes de ayer (regla 5).
 *
 * <h2>Un intento no hallado no se corrige: se vuelve a diligenciar</h2>
 *
 * <p>Cada diligencia es una fila con su numero de intento, y registrar la segunda <b>no toca la
 * primera</b>: no hay {@code UPDATE} en este camino ni privilegio para hacerlo (V28). Es el
 * precedente exacto de #39, y la garantia no esta en este codigo sino en {@code
 * notificacion_intento_uq}: reintentar «el intento 2» dos veces choca contra el indice en vez de
 * sobrescribir la traza sin que se note.
 *
 * <p>Y solo {@link ResultadoDeNotificacion#NO_UBICADO} se reintenta. Que la negativa a recibir
 * <b>si</b> surta efecto no es un descuido: el art. 104 a) del TUO del Codigo Tributario admite la
 * certificacion de la negativa como notificacion valida, y si no lo hiciera bastaria con cerrar la
 * puerta para que ninguna REC llegara a producir efecto nunca.
 *
 * <h2>Notificar la REC-1 mueve el expediente</h2>
 *
 * <p>Cuando la diligencia surte efecto sobre la REC-1, el expediente pasa a {@link
 * EstadoDelExpediente#REC1_NOTIFICADA} —codigo {@code 012} del manual— con un movimiento en su
 * historial. Es el mismo camino que usa {@link RegistrarActoCoactivo}: el estado no se escribe, se
 * agrega el movimiento del que se deriva.
 */
@Service
public class NotificarActoCoactivo {

    private final ActoCoactivoRepository actos;
    private final NotificacionCoactivaRepository notificaciones;
    private final ExpedienteRepository expedientes;
    private final MovimientoDelExpedienteRepository movimientos;
    private final PlazosCoactivosParametrizados plazos;
    private final Auditoria auditoria;
    private final java.time.Clock reloj;

    public NotificarActoCoactivo(
            ActoCoactivoRepository actos,
            NotificacionCoactivaRepository notificaciones,
            ExpedienteRepository expedientes,
            MovimientoDelExpedienteRepository movimientos,
            PlazosCoactivosParametrizados plazos,
            Auditoria auditoria,
            java.time.Clock reloj) {
        this.actos = actos;
        this.notificaciones = notificaciones;
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.plazos = plazos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Registra una diligencia sobre el acto identificado por el numero de su documento.
     *
     * @param numeroDelActo el numero impreso del acto notificado
     * @param fechaDeLaDiligencia cuando se diligencio; es la fecha del hecho, y de ella sale que
     *     conjunto de parametros rige
     * @param modalidad como se diligencio (art. 104)
     * @param resultado con que resultado termino
     * @param notificador quien la llevo
     * @param direccion donde se diligencio; si es {@code null}, la direccion referencial vigente
     *     del expediente
     * @param receptor quien recibio, si alguien recibio
     * @param documentoReceptor su documento
     * @param vinculo su vinculo con el obligado
     * @param acuse la constancia del cargo
     * @param observacion por que se registra (regla 10)
     * @throws ActoInexistente si no hay ningun acto con ese numero
     * @throws DiligenciaAnteriorAlActo si la diligencia es anterior al acto que notifica
     */
    @Transactional
    public Diligencia registrar(
            String numeroDelActo,
            LocalDate fechaDeLaDiligencia,
            ModalidadDeNotificacion modalidad,
            ResultadoDeNotificacion resultado,
            String notificador,
            @Nullable String direccion,
            @Nullable String receptor,
            @Nullable String documentoReceptor,
            @Nullable String vinculo,
            @Nullable String acuse,
            Observacion observacion) {

        ActoCoactivo acto =
                actos.porNumero(numeroDelActo.strip().toUpperCase(Locale.ROOT))
                        .orElseThrow(() -> new ActoInexistente(numeroDelActo));
        if (fechaDeLaDiligencia.isBefore(acto.fecha())) {
            throw new DiligenciaAnteriorAlActo(acto, fechaDeLaDiligencia);
        }

        ExpedienteCoactivo expediente =
                expedientes
                        .porId(acto.expedienteId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "El acto "
                                                        + acto.numero()
                                                        + " apunta a un expediente que no"
                                                        + " existe"));

        int intento = notificaciones.intentosDe(acto.identificador()) + 1;

        LocalDate exigibleDesde = null;
        Long conjuntoId = null;
        if (resultado.surteEfecto()) {
            PlazosCoactivosParametrizados.Vigentes vigentes =
                    plazos.aLaFechaDe(fechaDeLaDiligencia);
            Plazo plazo = vigentes.paraCumplirLaRec1();
            CalendarioHabil calendario = vigentes.calendario();
            exigibleDesde =
                    Exigibilidad.derivarDe(fechaDeLaDiligencia, plazo, calendario).exigibleDesde();
            conjuntoId = vigentes.conjuntoId();
        }

        NotificacionCoactiva guardada =
                notificaciones.insertar(
                        new NotificacionCoactiva(
                                null,
                                acto.identificador(),
                                acto.numero() + "/" + intento,
                                intento,
                                fechaDeLaDiligencia,
                                modalidad,
                                resultado,
                                notificador,
                                direccionDe(expediente, direccion, acto),
                                receptor,
                                documentoReceptor,
                                vinculo,
                                acuse,
                                exigibleDesde,
                                conjuntoId,
                                null,
                                observacion));

        EstadoDelExpediente estado =
                avanzar(expediente, acto, guardada, fechaDeLaDiligencia, observacion);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fechaDeLaDiligencia,
                                "notificacion",
                                String.valueOf(guardada.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(acto, guardada)));

        return new Diligencia(guardada, acto, estado);
    }

    // ------------------------------------------------------------------

    /**
     * Deja el expediente en {@link EstadoDelExpediente#REC1_NOTIFICADA} si lo que se acaba de
     * notificar con efecto es su REC-1.
     */
    private EstadoDelExpediente avanzar(
            ExpedienteCoactivo expediente,
            ActoCoactivo acto,
            NotificacionCoactiva diligencia,
            LocalDate fecha,
            Observacion observacion) {

        EstadoDelExpediente actual =
                EstadoDelExpediente.delHistorial(movimientos.deExpediente(acto.expedienteId()));
        boolean laRec1SurtioEfecto =
                acto.tipo() == TipoDeActoCoactivo.REC1 && diligencia.surtioEfecto();
        if (!laRec1SurtioEfecto
                || actual == EstadoDelExpediente.REC1_NOTIFICADA
                || actual.estaConcluido()) {
            return actual;
        }
        movimientos.registrar(
                MovimientoDelExpediente.cambioDeEstado(
                        expediente.identificador(),
                        EstadoDelExpediente.REC1_NOTIFICADA,
                        fecha,
                        "Notificacion de la " + acto.numero() + " (art. 14.1, Ley 26979)",
                        acto.fecha(),
                        acto.numero(),
                        reloj.instant(),
                        observacion));
        return EstadoDelExpediente.REC1_NOTIFICADA;
    }

    /**
     * Donde se diligencio.
     *
     * <p>Por omision, la direccion referencial <b>vigente</b> del expediente: es donde el ejecutor
     * notifica, y cambiarla es un acto con su propio motivo (RF-106, #40). Quien registra puede dar
     * otra —una diligencia se practica a veces en un domicilio procesal—, y entonces se guarda la
     * que dio.
     */
    private String direccionDe(
            ExpedienteCoactivo expediente, @Nullable String dada, ActoCoactivo acto) {
        if (dada != null && !dada.isBlank()) {
            return dada.strip();
        }
        String vigente =
                movimientos
                        .ultimoCambioDeDireccion(expediente.identificador())
                        .map(MovimientoDelExpediente::direccionNueva)
                        .orElseGet(expediente::direccionReferencial);
        if (vigente == null || vigente.isBlank()) {
            throw new SinDireccion(expediente.numero(), acto.numero());
        }
        return vigente;
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(ActoCoactivo acto, NotificacionCoactiva diligencia) {
        return "{\"acto\":\""
                + acto.numero()
                + "\",\"intento\":"
                + diligencia.intento()
                + ",\"modalidad\":\""
                + diligencia.modalidad()
                + "\",\"resultado\":\""
                + diligencia.resultado()
                + "\",\"exigibleDesde\":"
                + (diligencia.exigibleDesde() == null
                        ? "null"
                        : "\"" + diligencia.exigibleDesde() + "\"")
                + "}";
    }

    /**
     * La diligencia registrada, con el acto que notifico y el estado en que queda el expediente.
     *
     * @param notificacion la fila guardada
     * @param acto el acto notificado
     * @param estado el estado del expediente despues de la diligencia
     */
    public record Diligencia(
            NotificacionCoactiva notificacion, ActoCoactivo acto, EstadoDelExpediente estado) {}

    /** No hay ningun acto coactivo con ese numero. */
    public static final class ActoInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ActoInexistente(String numero) {
            super("No hay ningun acto coactivo con el numero '" + numero + "'");
        }
    }

    /** Se diligencio antes de dictar el acto: no puede ser. */
    public static final class DiligenciaAnteriorAlActo extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        DiligenciaAnteriorAlActo(ActoCoactivo acto, LocalDate fecha) {
            super(
                    "El acto "
                            + acto.numero()
                            + " se dicto el "
                            + acto.fecha()
                            + ": no se pudo notificar el "
                            + fecha);
        }
    }

    /** Ni el expediente ni la peticion dicen donde notificar. */
    public static final class SinDireccion extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinDireccion(String expediente, String acto) {
            super(
                    "El expediente "
                            + expediente
                            + " no tiene direccion referencial y no se dio una: no hay donde"
                            + " notificar el acto "
                            + acto);
        }
    }
}
