package kamayuk.rentas.fiscalizacion.dobles;

import java.util.HashSet;
import java.util.Set;
import kamayuk.rentas.nucleo.PadronVehicular;

/** El padron vehicular, en memoria: los identificadores que se le siembran y ninguno mas (#422). */
public final class PadronVehicularDeMentira implements PadronVehicular {

    private final Set<Long> ids = new HashSet<>();

    public PadronVehicularDeMentira con(long... vehiculoIds) {
        for (long id : vehiculoIds) {
            ids.add(id);
        }
        return this;
    }

    @Override
    public boolean estaEnElPadron(long vehiculoId) {
        return ids.contains(vehiculoId);
    }
}
