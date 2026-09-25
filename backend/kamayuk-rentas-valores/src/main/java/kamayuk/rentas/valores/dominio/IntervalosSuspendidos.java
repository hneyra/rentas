package kamayuk.rentas.valores.dominio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Los dias suspendidos de un tramo del plazo, cada uno <b>una sola vez</b> (#335).
 *
 * <p>Un dia esta suspendido o no lo esta. Si una reclamacion en tramite y un fraccionamiento
 * vigente coinciden —las causales a) y d) del art. 46 para exigir el pago—, los dias que comparten
 * detienen el plazo una vez, no dos. Sumar las suspensiones en bruto corria el vencimiento de mas,
 * y en contra del contribuyente: la solicitud que procedia salia {@code NO_PROCEDE}.
 *
 * <p>Por eso el computo no suma intervalos tal como se alegaron: primero los {@link #unir une} —los
 * que se solapan y los que se tocan quedan en uno—, y despues cuenta. Los solapes <b>no se
 * rechazan</b>: dos causales simultaneas son hechos legitimos, y rechazarlas obligaria a quien
 * registra a inventarse intervalos que no constan en ningun expediente.
 *
 * <p>Los intervalos que guarda estan ordenados, no se solapan y no se tocan: entre dos consecutivos
 * queda al menos un dia en que el plazo corrio.
 */
public final class IntervalosSuspendidos {

    private final List<IntervaloSuspendido> intervalos;

    private IntervalosSuspendidos(List<IntervaloSuspendido> intervalos) {
        this.intervalos = List.copyOf(intervalos);
    }

    /**
     * Une los intervalos que se solapan o se tocan.
     *
     * <p>Dos se tocan cuando uno empieza el dia siguiente al ultimo del otro: entre los dos no hay
     * dia en que el plazo corriera, asi que son un solo intervalo. Unirlos no cambia los dias que
     * suman —los dos son inclusivos—, pero deja una sola forma de escribir la misma suspension.
     *
     * @param alegados en cualquier orden; puede estar vacia
     */
    public static IntervalosSuspendidos unir(Collection<IntervaloSuspendido> alegados) {
        Objects.requireNonNull(alegados, "La lista de suspensiones puede estar vacia, no faltar");
        List<IntervaloSuspendido> ordenados = new ArrayList<>(alegados);
        ordenados.sort(
                Comparator.comparing(IntervaloSuspendido::desde)
                        .thenComparing(IntervaloSuspendido::hasta));
        List<IntervaloSuspendido> unidos = new ArrayList<>();
        for (IntervaloSuspendido siguiente : ordenados) {
            int ultimo = unidos.size() - 1;
            if (ultimo >= 0 && !siguiente.desde().isAfter(unidos.get(ultimo).hasta().plusDays(1))) {
                IntervaloSuspendido previo = unidos.get(ultimo);
                unidos.set(
                        ultimo,
                        new IntervaloSuspendido(
                                previo.desde(),
                                siguiente.hasta().isAfter(previo.hasta())
                                        ? siguiente.hasta()
                                        : previo.hasta()));
            } else {
                unidos.add(siguiente);
            }
        }
        return new IntervalosSuspendidos(unidos);
    }

    /** Los intervalos ya unidos: ordenados, sin solapes y sin contiguos. */
    public List<IntervaloSuspendido> intervalos() {
        return intervalos;
    }

    /** Cuantos dias estuvo suspendido el plazo, sin contar dos veces ninguno. */
    public long dias() {
        return intervalos.stream().mapToLong(IntervaloSuspendido::dias).sum();
    }

    @Override
    public boolean equals(Object otro) {
        return otro instanceof IntervalosSuspendidos o && intervalos.equals(o.intervalos);
    }

    @Override
    public int hashCode() {
        return intervalos.hashCode();
    }

    @Override
    public String toString() {
        return "IntervalosSuspendidos" + intervalos;
    }
}
