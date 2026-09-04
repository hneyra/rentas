package kamayuk.rentas.tesoreria.dominio;

import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Una ventanilla de cobro (V3, V29).
 *
 * @param id nulo mientras no se haya guardado
 * @param codigo como la nombra la municipalidad
 * @param nombre el rotulo
 * @param serie la serie de sus recibos, unica en la municipalidad
 * @param areaId el area a la que se imputa lo que recauda; nulo en la caja tributaria general
 * @param activa una caja que ya no se usa se da de baja, no se borra (RNF-051)
 */
public record Caja(
        @Nullable Long id,
        String codigo,
        String nombre,
        String serie,
        @Nullable Long areaId,
        boolean activa) {

    public Caja {
        Objects.requireNonNull(codigo, "La caja necesita su codigo");
        Objects.requireNonNull(nombre, "La caja necesita su nombre");
        Objects.requireNonNull(serie, "La caja necesita su serie de recibos (V29)");
        codigo = codigo.strip().toUpperCase(Locale.ROOT);
        serie = serie.strip().toUpperCase(Locale.ROOT);
        if (codigo.isEmpty()) {
            throw new IllegalArgumentException("El codigo de la caja no puede estar vacio");
        }
        if (serie.isEmpty()) {
            throw new IllegalArgumentException("La serie de la caja no puede estar vacia");
        }
    }

    /** El numero que le corresponde a un correlativo de su serie. */
    public NumeroDeRecibo numero(long correlativo) {
        return new NumeroDeRecibo(serie, correlativo);
    }
}
