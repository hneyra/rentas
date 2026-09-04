package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Un sector del catastro: la division territorial con la que se agrupan manzanas y predios.
 *
 * <p>Su codigo es uno de los tramos del codigo de referencia catastral, asi que no es una etiqueta
 * decorativa: cambiarlo desalinea el codigo de todos los predios del sector.
 */
public record Sector(
        @Nullable Long id, String codigo, String nombre, @Nullable String zona, boolean activo) {

    private static final int CODIGO_MAXIMO = 10;
    private static final int NOMBRE_MAXIMO = 160;
    private static final int ZONA_MAXIMA = 80;

    public Sector {
        Objects.requireNonNull(codigo, "El sector necesita su codigo");
        Objects.requireNonNull(nombre, "El sector necesita su nombre");
        codigo = codigo.strip();
        nombre = nombre.strip();
        if (codigo.isEmpty() || codigo.length() > CODIGO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El codigo de sector va de 1 a " + CODIGO_MAXIMO + " caracteres");
        }
        if (nombre.isEmpty() || nombre.length() > NOMBRE_MAXIMO) {
            throw new IllegalArgumentException(
                    "El nombre de sector va de 1 a " + NOMBRE_MAXIMO + " caracteres");
        }
        if (zona != null && zona.strip().length() > ZONA_MAXIMA) {
            throw new IllegalArgumentException("La zona excede " + ZONA_MAXIMA + " caracteres");
        }
    }

    public static Sector nuevo(String codigo, String nombre) {
        return new Sector(null, codigo, nombre, null, true);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** Se da de baja, no se borra: su codigo esta dentro del codigo de predios ya emitidos. */
    public Sector dadoDeBaja() {
        return new Sector(id, codigo, nombre, zona, false);
    }
}
