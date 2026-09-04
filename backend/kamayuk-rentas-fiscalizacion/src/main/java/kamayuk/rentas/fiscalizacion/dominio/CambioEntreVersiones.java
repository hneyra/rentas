package kamayuk.rentas.fiscalizacion.dominio;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Un cambio concreto entre dos versiones de una liquidación (#49, AC 2).
 *
 * <p>Los dos lados son texto y no el valor tipado a propósito: lo que se compara son cosas de
 * naturaleza distinta —un periodo, una condición, una superficie— y quien lee la explicación
 * necesita verlas juntas en una lista, no en seis listas por tipo.
 *
 * @param concepto qué cambió, con el nombre que la pantalla le da
 * @param antes lo que decía la versión anterior; {@code null} si la línea no existía
 * @param despues lo que dice la nueva; {@code null} si la línea desapareció
 */
public record CambioEntreVersiones(
        String concepto, @Nullable String antes, @Nullable String despues) {

    public CambioEntreVersiones {
        Objects.requireNonNull(concepto, "Un cambio dice que cambio");
        if (Objects.equals(antes, despues)) {
            throw new IllegalArgumentException(
                    "Un cambio con los dos lados iguales no es un cambio: '" + concepto + "'");
        }
    }
}
