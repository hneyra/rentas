package kamayuk.rentas.fiscalizacion.dobles;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.DeclaracionDelEjercicio;
import kamayuk.rentas.nucleo.DeclaracionesDelEjercicio;

/**
 * Las declaraciones juradas de mentira, por predio y ejercicio.
 *
 * <p><b>Solo lee.</b> Igual que {@link PadronDeMentira}: el puerto que {@code fiscalizacion} usa
 * contra {@code rentas} no tiene ninguna escritura, y eso es lo que sostiene el AC 4 de #49.
 */
public final class DeclaracionesDeMentira implements DeclaracionesDelEjercicio {

    private final Map<String, DeclaracionDelEjercicio> porPredioYEjercicio = new LinkedHashMap<>();

    public DeclaracionesDeMentira con(long predioId, DeclaracionDelEjercicio declaracion) {
        porPredioYEjercicio.put(clave(predioId, declaracion.ejercicio()), declaracion);
        return this;
    }

    @Override
    public Map<Long, DeclaracionDelEjercicio> dePredios(
            Collection<Long> predioIds, Ejercicio ejercicio) {
        Map<Long, DeclaracionDelEjercicio> encontradas = new LinkedHashMap<>();
        for (Long predioId : predioIds) {
            DeclaracionDelEjercicio declaracion =
                    porPredioYEjercicio.get(clave(predioId, ejercicio));
            if (declaracion != null) {
                encontradas.put(predioId, declaracion);
            }
        }
        return Map.copyOf(encontradas);
    }

    private static String clave(long predioId, Ejercicio ejercicio) {
        return predioId + "|" + ejercicio.valor();
    }
}
