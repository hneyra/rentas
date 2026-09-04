package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * El computo de la prescripcion de <b>un</b> ejercicio dentro de una solicitud (V28, {@code
 * prescripcion_ejercicio}).
 *
 * <p>Guarda el inicio original y el inicio vigente por separado porque la resolucion tiene que
 * poder explicar por que {@link #fechaPrescripcion} no es "el inicio mas el plazo": entre los dos
 * estan las interrupciones del art. 45, que reinician el computo.
 *
 * @param id nulo mientras no se ha guardado
 * @param ejercicio de que ejercicio es el computo
 * @param inicioComputo el dia 1 del art. 44
 * @param inicioVigente el dia 1 que quedo tras la ultima interrupcion aplicada
 * @param fechaPrescripcion el dia en que el plazo vence
 * @param prescrita si a la fecha de presentacion ya habia vencido
 */
public record ComputoDeEjercicio(
        @Nullable Long id,
        Ejercicio ejercicio,
        LocalDate inicioComputo,
        LocalDate inicioVigente,
        LocalDate fechaPrescripcion,
        boolean prescrita) {

    public ComputoDeEjercicio {
        Objects.requireNonNull(ejercicio, "El computo es de un ejercicio");
        Objects.requireNonNull(inicioComputo, "Falta el inicio del computo (art. 44)");
        Objects.requireNonNull(inicioVigente, "Falta el inicio vigente tras las interrupciones");
        Objects.requireNonNull(fechaPrescripcion, "Falta la fecha de prescripcion");
        if (inicioVigente.isBefore(inicioComputo)) {
            throw new IllegalArgumentException(
                    "Una interrupcion adelanta el inicio del computo, nunca lo atrasa: "
                            + inicioComputo
                            + " a "
                            + inicioVigente);
        }
    }

    /** El computo de un ejercicio todavia sin guardar. */
    public static ComputoDeEjercicio nuevo(
            Ejercicio ejercicio, ComputoDePrescripcion.Computo computo) {
        return new ComputoDeEjercicio(
                null,
                ejercicio,
                computo.inicioComputo(),
                computo.inicioVigente(),
                computo.fechaDePrescripcion(),
                computo.prescrita());
    }
}
