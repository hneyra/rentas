package kamayuk.rentas.sanciones.aplicacion;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.dominio.EstadoDeNotificacion;
import kamayuk.rentas.sanciones.dominio.NotificacionAdministrativa;
import kamayuk.rentas.sanciones.dominio.NotificacionAdministrativaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierra una notificación administrativa por subsanación (#47 AC2): "subsanar dentro del plazo
 * cierra la notificación sin generar papeleta ni deuda" —por eso este caso de uso no llama a {@code
 * RegistrarPapeleta} ni a {@code GeneradorDeCargos}, solo cambia el estado.
 *
 * <p>La fecha de subsanación entra como argumento (regla 6): quien llama decide contra qué fecha se
 * compara el plazo, nunca el reloj del sistema leído aquí dentro. Una notificación sin {@code
 * plazoDias} se admite siempre —sin plazo no hay nada que vencer (#47 AC3)—.
 */
@Service
public class SubsanarNotificacion {

    private static final String TABLA_AUDITADA = "notificacion_administrativa";

    private final NotificacionAdministrativaRepository notificaciones;
    private final Auditoria auditoria;

    public SubsanarNotificacion(
            NotificacionAdministrativaRepository notificaciones, Auditoria auditoria) {
        this.notificaciones = notificaciones;
        this.auditoria = auditoria;
    }

    @Transactional
    public NotificacionAdministrativa subsanar(
            String numero, LocalDate fechaSubsanacion, Observacion observacion) {

        NotificacionAdministrativa notificacion =
                notificaciones
                        .porNumero(numero)
                        .orElseThrow(() -> new NotificacionInexistente(numero));

        if (notificacion.estado() != EstadoDeNotificacion.EMITIDA) {
            throw new EstadoInvalido(notificacion);
        }

        notificacion
                .vencimiento()
                .filter(fechaSubsanacion::isAfter)
                .ifPresent(
                        vencimiento -> {
                            throw new FueraDePlazo(notificacion, vencimiento, fechaSubsanacion);
                        });

        NotificacionAdministrativa subsanada =
                notificaciones.subsanar(
                        Objects.requireNonNull(
                                notificacion.id(),
                                "Una notificacion ya guardada tiene identificador"));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fechaSubsanacion,
                                TABLA_AUDITADA,
                                String.valueOf(subsanada.id()),
                                Operacion.MODIFICACION,
                                observacion)
                        .con(
                                "{\"estado\":\"" + notificacion.estado() + "\"}",
                                "{\"estado\":\"" + subsanada.estado() + "\"}"));

        return subsanada;
    }

    /** No hay ninguna notificación con ese número, o es de otra municipalidad. */
    public static final class NotificacionInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotificacionInexistente(String numero) {
            super("No hay ninguna notificacion con numero '" + numero + "' en esta municipalidad");
        }
    }

    /** Solo se subsana una notificación {@code EMITIDA}: ya está cerrada, o anulada. */
    public static final class EstadoInvalido extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        EstadoInvalido(NotificacionAdministrativa notificacion) {
            super(
                    "La notificacion "
                            + notificacion.numero()
                            + " esta "
                            + notificacion.estado()
                            + ", no EMITIDA: no se puede subsanar");
        }
    }

    /** La fecha de subsanación es posterior al vencimiento del plazo (#47 AC2). */
    public static final class FueraDePlazo extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        FueraDePlazo(
                NotificacionAdministrativa notificacion, LocalDate vencimiento, LocalDate intento) {
            super(
                    "La notificacion "
                            + notificacion.numero()
                            + " vencio el "
                            + vencimiento
                            + ": "
                            + intento
                            + " ya esta fuera de plazo");
        }
    }
}
