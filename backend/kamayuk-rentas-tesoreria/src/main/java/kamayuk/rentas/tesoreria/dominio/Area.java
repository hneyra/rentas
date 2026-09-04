package kamayuk.rentas.tesoreria.dominio;

import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * El area a la que se imputa lo que una tasa recauda (V3, RF-133).
 *
 * @param id nulo mientras no se haya guardado
 * @param codigo como la nombra la municipalidad
 * @param nombre el rotulo
 * @param activa un area que ya no cobra se da de baja, no se borra (RNF-051)
 */
public record Area(@Nullable Long id, String codigo, String nombre, boolean activa) {

    public Area {
        Objects.requireNonNull(codigo, "El area necesita su codigo");
        Objects.requireNonNull(nombre, "El area necesita su nombre");
        codigo = codigo.strip().toUpperCase(Locale.ROOT);
        if (codigo.isEmpty()) {
            throw new IllegalArgumentException("El codigo del area no puede estar vacio");
        }
    }
}
