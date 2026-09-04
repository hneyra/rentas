package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Una manzana dentro de un sector. Su codigo es otro tramo del codigo de referencia catastral.
 *
 * <p>El codigo es unico <b>dentro de su sector</b>, no en toda la municipalidad: la manzana 001 del
 * sector 01 y la 001 del sector 02 son manzanas distintas.
 */
public record Manzana(@Nullable Long id, long sectorId, String codigo) {

    private static final int CODIGO_MAXIMO = 10;

    public Manzana {
        Objects.requireNonNull(codigo, "La manzana necesita su codigo");
        codigo = codigo.strip();
        if (codigo.isEmpty() || codigo.length() > CODIGO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El codigo de manzana va de 1 a " + CODIGO_MAXIMO + " caracteres");
        }
    }

    public static Manzana nueva(long sectorId, String codigo) {
        return new Manzana(null, sectorId, codigo);
    }

    public boolean esNueva() {
        return id == null;
    }
}
