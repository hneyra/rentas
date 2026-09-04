package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Lo que piden los tres catálogos de #43: {@code codigos_transito}, {@code codigos_cuis} y {@code
 * adm_codigos_reporte}. Cada endpoint fija {@code familia} según cuál de los dos catálogos
 * consulta; el resto de los criterios son opcionales y se combinan con Y.
 *
 * @param familia tránsito o administrativa
 * @param codigo por el código exacto del catálogo
 * @param texto busca dentro de la descripción de la infracción
 * @param vigenteA si se da, solo la versión vigente a esa fecha; «vigente», no «la última» (regla
 *     9) — una papeleta se explica con el código vigente el día de la infracción
 */
public record CriterioDeCodigoInfraccion(
        Familia familia,
        @Nullable String codigo,
        @Nullable String texto,
        @Nullable LocalDate vigenteA) {

    public CriterioDeCodigoInfraccion {
        Objects.requireNonNull(familia, "Todo catálogo de infracciones es de una familia");
        codigo = limpiar(codigo);
        if (codigo != null) {
            codigo = codigo.toUpperCase(Locale.ROOT);
        }
        texto = limpiar(texto);
    }

    private static @Nullable String limpiar(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
