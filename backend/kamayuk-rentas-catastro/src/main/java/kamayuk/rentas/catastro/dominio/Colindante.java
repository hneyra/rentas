package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Con quien linda un predio rustico por una orientacion (RF-004). */
public record Colindante(
        @Nullable Long id, @Nullable Long fichaId, Orientacion orientacion, String descripcion) {

    private static final int DESCRIPCION_MAXIMA = 200;

    public Colindante {
        Objects.requireNonNull(orientacion, "El colindante necesita su orientacion");
        Objects.requireNonNull(descripcion, "El colindante necesita con quien linda");

        descripcion = descripcion.strip();
        if (descripcion.isEmpty() || descripcion.length() > DESCRIPCION_MAXIMA) {
            throw new IllegalArgumentException(
                    "La descripcion va de 1 a " + DESCRIPCION_MAXIMA + " caracteres");
        }
    }

    public static Colindante por(Orientacion orientacion, String descripcion) {
        return new Colindante(null, null, orientacion, descripcion);
    }

    /** El mismo colindante colgado de otra version, al versionar. */
    public Colindante enLaFicha(long otraFichaId) {
        return new Colindante(null, otraFichaId, orientacion, descripcion);
    }
}
