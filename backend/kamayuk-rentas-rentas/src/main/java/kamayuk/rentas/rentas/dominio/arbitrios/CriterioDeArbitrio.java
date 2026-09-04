package kamayuk.rentas.rentas.dominio.arbitrios;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Lo que pide el listado {@code GET /api/v1/rentas/arbitrios} (#31). {@code codigoPredial} es
 * opcional y se combina con Y.
 *
 * @param ejercicio el ejercicio que se consulta
 * @param codigoPredial por el código de referencia catastral del predio
 */
public record CriterioDeArbitrio(Ejercicio ejercicio, @Nullable String codigoPredial) {

    public CriterioDeArbitrio {
        Objects.requireNonNull(ejercicio, "El listado de arbitrios necesita su ejercicio");
        codigoPredial = limpiar(codigoPredial);
    }

    private static @Nullable String limpiar(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio.toUpperCase(Locale.ROOT);
    }
}
