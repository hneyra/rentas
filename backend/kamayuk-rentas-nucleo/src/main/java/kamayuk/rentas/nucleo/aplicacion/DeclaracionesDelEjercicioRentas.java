package kamayuk.rentas.nucleo.aplicacion;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.DeclaracionDelEjercicio;
import kamayuk.rentas.nucleo.DeclaracionesDelEjercicio;
import kamayuk.rentas.nucleo.dominio.DeclaracionJurada;
import kamayuk.rentas.nucleo.dominio.DeclaracionJuradaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementación de {@link DeclaracionesDelEjercicio} (#49, RF-055).
 *
 * <p>Traduce {@code DeclaracionJurada} —que vive en {@code .dominio} y no cruza la frontera del
 * módulo— a la proyección que otro contexto necesita.
 *
 * <p>{@code fueraDePlazo} se lee de {@link DeclaracionJurada#fueraDePlazo()}, que compara la fecha
 * de presentación con el plazo <b>parametrizado</b> que la declaración guardó. No se recalcula
 * aquí: el plazo puede haber cambiado desde entonces, y recalcularlo diría que declaró tarde quien
 * no lo hizo —o al revés—.
 *
 * <p>{@code @Transactional(readOnly = true)}: sin transacción no hay {@code SET LOCAL}, y sin él la
 * política RLS falla en vez de devolver filas.
 */
@Service
public class DeclaracionesDelEjercicioRentas implements DeclaracionesDelEjercicio {

    /**
     * «La mas reciente»: la de fecha de presentacion mayor y, el mismo dia, la de identificador
     * mayor —la que se registro despues— (#344, anotado en #629).
     *
     * <p>Es el mismo orden con que {@code fiscalizacion.LaDeclaracionDelEjercicio} elige en SQL la
     * declaracion del acta y de la deteccion ({@code ORDER BY d.fecha_presentacion DESC, d.id
     * DESC}). Hasta #629 aqui solo se miraba la fecha, y con dos declaraciones del mismo dia se
     * quedaba la primera que devolvia una consulta sin {@code ORDER BY}: el acta publicaba lo
     * declarado en la mas reciente y la liquidacion que sale de ella comparaba contra la otra.
     */
    private static final Comparator<DeclaracionDelEjercicio> MAS_RECIENTE =
            Comparator.comparing(DeclaracionDelEjercicio::fechaPresentacion)
                    .thenComparingLong(DeclaracionDelEjercicio::declaracionId);

    private final DeclaracionJuradaRepository declaraciones;

    public DeclaracionesDelEjercicioRentas(DeclaracionJuradaRepository declaraciones) {
        this.declaraciones = declaraciones;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, DeclaracionDelEjercicio> dePredios(
            Collection<Long> predioIds, Ejercicio ejercicio) {
        Objects.requireNonNull(predioIds, "La lista de predios es vacia, no nula");
        Objects.requireNonNull(ejercicio, "La consulta necesita su ejercicio");

        Map<Long, DeclaracionDelEjercicio> porPredio = new LinkedHashMap<>();
        for (DeclaracionJurada declaracion :
                declaraciones.vigentesDePredios(predioIds, ejercicio)) {
            Long predioId = declaracion.predioId();
            if (predioId == null) {
                continue;
            }
            // Si un predio tuviera dos declaraciones vigentes del mismo ejercicio -que la
            // rectificatoria evita, porque sustituye la anterior- se conserva la mas reciente:
            // comparar contra la vieja acusaria de subvaluacion a quien ya corrigio.
            DeclaracionDelEjercicio candidata = proyectar(declaracion);
            DeclaracionDelEjercicio previa = porPredio.get(predioId);
            if (previa == null || MAS_RECIENTE.compare(candidata, previa) > 0) {
                porPredio.put(predioId, candidata);
            }
        }
        return Map.copyOf(porPredio);
    }

    private static DeclaracionDelEjercicio proyectar(DeclaracionJurada declaracion) {
        return new DeclaracionDelEjercicio(
                Objects.requireNonNull(
                        declaracion.id(), "una declaracion leida de la base tiene id"),
                declaracion.numero(),
                declaracion.ejercicio(),
                declaracion.contribuyenteId(),
                declaracion.fechaPresentacion(),
                declaracion.fueraDePlazo(),
                declaracion.fichaCatastralId());
    }
}
