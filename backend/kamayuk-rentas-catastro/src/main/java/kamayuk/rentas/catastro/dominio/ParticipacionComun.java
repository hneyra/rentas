package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;

/**
 * Cuanto de lo comun le toca a una unidad de la edificacion (RF-003).
 *
 * <p>Es lo que convierte una lista de areas comunes en un reparto. Sin esto no hay forma de decir
 * cuanto del ascensor entra en el autovaluo del departamento 302, ni de distribuirle los arbitrios
 * del edificio.
 *
 * <p>La suma de las participaciones de una ficha <b>no puede pasar de 100</b>, y lo sostiene un
 * disparador diferido de la base —no una comprobacion en Java—: si suman 120, el valor de lo comun
 * se reparte por mas de lo que hay y todas las unidades del edificio pagan de mas.
 */
public record ParticipacionComun(
        @Nullable Long id, @Nullable Long fichaId, long predioId, Porcentaje porcentaje) {

    public ParticipacionComun {
        Objects.requireNonNull(porcentaje, "La participacion necesita su porcentaje");
    }

    public static ParticipacionComun de(long predioId, Porcentaje porcentaje) {
        return new ParticipacionComun(null, null, predioId, porcentaje);
    }

    /** La misma participacion colgada de otra version, al versionar. */
    public ParticipacionComun enLaFicha(long otraFichaId) {
        return new ParticipacionComun(null, otraFichaId, predioId, porcentaje);
    }
}
