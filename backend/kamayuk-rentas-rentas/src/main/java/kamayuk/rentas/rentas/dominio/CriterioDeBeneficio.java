package kamayuk.rentas.rentas.dominio;

import java.time.LocalDate;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Lo que pide la consulta de beneficios (RF-029). Todos los criterios son opcionales y se combinan
 * con Y.
 *
 * @param codigoContribuyente por el codigo unico del padron, no por su identificador interno
 * @param tipo el nombre del beneficio
 * @param vigentesA si se da, solo los beneficios vigentes a esa fecha; «vigentes», no «los ultimos»
 *     (regla 9)
 */
public record CriterioDeBeneficio(
        @Nullable String codigoContribuyente,
        @Nullable String tipo,
        @Nullable LocalDate vigentesA) {

    public CriterioDeBeneficio {
        codigoContribuyente = limpiar(codigoContribuyente);
        tipo = limpiar(tipo);
    }

    public static CriterioDeBeneficio todos() {
        return new CriterioDeBeneficio(null, null, null);
    }

    private static @Nullable String limpiar(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio.toUpperCase(Locale.ROOT);
    }
}
