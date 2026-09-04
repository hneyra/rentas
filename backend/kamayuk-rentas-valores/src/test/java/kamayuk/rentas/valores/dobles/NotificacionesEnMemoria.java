package kamayuk.rentas.valores.dobles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.valores.dominio.Notificacion;
import kamayuk.rentas.valores.dominio.NotificacionRepository;

/** Un {@link NotificacionRepository} en memoria, para las pruebas de los casos de uso de #39. */
public final class NotificacionesEnMemoria implements NotificacionRepository {

    private final List<Notificacion> guardadas = new ArrayList<>();
    private long siguienteId = 1;

    @Override
    public Notificacion insertar(Notificacion notificacion) {
        Notificacion conId =
                new Notificacion(
                        siguienteId++,
                        notificacion.valorId(),
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
    public List<Notificacion> deValor(long valorId) {
        return guardadas.stream()
                .filter(n -> n.valorId() == valorId)
                .sorted(Comparator.comparingInt(Notificacion::intento))
                .toList();
    }

    @Override
    public Optional<Notificacion> queSurtioEfecto(long valorId) {
        return deValor(valorId).stream().filter(Notificacion::surtioEfecto).findFirst();
    }

    @Override
    public int intentosDe(long valorId) {
        return deValor(valorId).stream().mapToInt(Notificacion::intento).max().orElse(0);
    }

    /** Todo lo guardado, para que la prueba compruebe que un reintento no borro nada. */
    public List<Notificacion> todas() {
        return List.copyOf(guardadas);
    }
}
