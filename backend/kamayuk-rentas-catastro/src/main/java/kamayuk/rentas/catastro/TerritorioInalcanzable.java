package kamayuk.rentas.catastro;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * No se pudo preguntar. <b>No es «no hay»</b> (#43, AC-4).
 *
 * <p>Publico por lo mismo que {@link HechoDelTerritorioQueNoConsta}: quien autoriza una licencia
 * tiene que poder separar «el territorio dice que no hay riesgo» de «no se pudo saber», y leer la
 * segunda como la primera es lo que abre un local sobre un suelo del que no se sabe nada. Un
 * consumidor no puede depender del tipo del transporte para hacer esa distincion.
 */
public abstract class TerritorioInalcanzable extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    protected TerritorioInalcanzable(String mensaje, @Nullable Throwable causa) {
        super(mensaje, causa);
    }
}
