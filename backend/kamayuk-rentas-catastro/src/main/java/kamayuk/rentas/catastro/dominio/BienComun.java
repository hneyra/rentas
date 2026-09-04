package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Un area comun de una edificacion en propiedad exclusiva y comun (RF-003): la escalera, el
 * ascensor, el patio, la azotea.
 *
 * <p>Se valoriza como una construccion mas —con su material, su estado y su antiguedad—, pero su
 * valor <b>no es de nadie en particular</b>: se reparte entre las unidades segun su participacion.
 * Por eso vive aqui y no en {@link Construccion}, que si cuelga de un predio con un titular.
 *
 * <p>Cuanto vale es D-02a y vive en datos versionados (regla 5). Aqui hay areas y letras.
 */
public record BienComun(
        @Nullable Long id,
        @Nullable Long fichaId,
        String descripcion,
        AreaM2 area,
        @Nullable MaterialEstructural material,
        @Nullable EstadoDeConservacion estadoConservacion,
        @Nullable Ejercicio anioConstruccion) {

    private static final int DESCRIPCION_MAXIMA = 160;

    public BienComun {
        Objects.requireNonNull(descripcion, "El bien comun necesita su descripcion");
        Objects.requireNonNull(area, "El bien comun necesita su area");

        descripcion = descripcion.strip();
        if (descripcion.isEmpty() || descripcion.length() > DESCRIPCION_MAXIMA) {
            throw new IllegalArgumentException(
                    "La descripcion va de 1 a " + DESCRIPCION_MAXIMA + " caracteres");
        }
    }

    public static BienComun de(String descripcion, AreaM2 area) {
        return new BienComun(null, null, descripcion, area, null, null, null);
    }

    /** El mismo bien colgado de otra version, al versionar. */
    public BienComun enLaFicha(long otraFichaId) {
        return new BienComun(
                null,
                otraFichaId,
                descripcion,
                area,
                material,
                estadoConservacion,
                anioConstruccion);
    }
}
