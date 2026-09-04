package kamayuk.rentas.coactiva.dobles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.coactiva.dominio.NotificacionCoactiva;
import kamayuk.rentas.coactiva.dominio.NotificacionCoactivaRepository;

/**
 * Un {@link NotificacionCoactivaRepository} en memoria.
 *
 * <p>Solo agrega, igual que la tabla: una diligencia no se corrige, se vuelve a diligenciar. Imita
 * {@code notificacion_intento_uq} —dos veces el mismo intento chocan—, que en la base es quien lo
 * garantiza de verdad ({@code ActosCoactivosJdbcTest}).
 */
public final class DiligenciasEnMemoria implements NotificacionCoactivaRepository {

    private final List<NotificacionCoactiva> guardadas = new ArrayList<>();
    private long siguienteId = 1;

    @Override
    public NotificacionCoactiva insertar(NotificacionCoactiva notificacion) {
        boolean repetida =
                deActo(notificacion.actoId()).stream()
                        .anyMatch(d -> d.intento() == notificacion.intento());
        if (repetida) {
            throw new IllegalStateException(
                    "Ya hay un intento "
                            + notificacion.intento()
                            + " del acto "
                            + notificacion.actoId()
                            + ": indice unico imitado (notificacion_intento_uq)");
        }
        NotificacionCoactiva conId =
                new NotificacionCoactiva(
                        siguienteId++,
                        notificacion.actoId(),
                        notificacion.numero(),
                        notificacion.intento(),
                        notificacion.fechaDeLaDiligencia(),
                        notificacion.modalidad(),
                        notificacion.resultado(),
                        notificacion.notificador(),
                        notificacion.direccion(),
                        notificacion.receptor(),
                        notificacion.documentoReceptor(),
                        notificacion.vinculo(),
                        notificacion.acuse(),
                        notificacion.exigibleDesde(),
                        notificacion.conjuntoId(),
                        "prueba",
                        notificacion.observacion());
        guardadas.add(conId);
        return conId;
    }

    @Override
    public List<NotificacionCoactiva> deActo(long actoId) {
        return guardadas.stream()
                .filter(d -> d.actoId() == actoId)
                .sorted(Comparator.comparingInt(NotificacionCoactiva::intento))
                .toList();
    }

    @Override
    public Optional<NotificacionCoactiva> queSurtioEfecto(long actoId) {
        return deActo(actoId).stream().filter(NotificacionCoactiva::surtioEfecto).findFirst();
    }

    @Override
    public int intentosDe(long actoId) {
        return deActo(actoId).stream().mapToInt(NotificacionCoactiva::intento).max().orElse(0);
    }
}
