package kamayuk.rentas.nucleo.dominio.alcabala;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * El resultado de {@link BaseImponibleDeAlcabala#elegir}: cuál base ganó, y por qué —el criterio de
 * aceptación de #32 exige que la elección quede registrada con su fundamento, no solo el número—.
 *
 * @param base la base imponible elegida: la mayor de las dos
 * @param origen cuál de las dos era
 * @param fundamento el texto que explica la comparación, para la auditoría (regla 10) y para quien
 *     impugne la determinación
 */
public record EleccionDeBase(Dinero base, OrigenDeLaBase origen, String fundamento) {

    public EleccionDeBase {
        Objects.requireNonNull(base, "La eleccion necesita la base elegida");
        Objects.requireNonNull(origen, "La eleccion necesita de donde salio la base");
        Objects.requireNonNull(fundamento, "La eleccion necesita su fundamento (regla 10)");
        if (fundamento.isBlank()) {
            throw new IllegalArgumentException(
                    "El fundamento de la eleccion no puede ir en blanco");
        }
    }
}
