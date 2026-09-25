package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Locale;
import kamayuk.rentas.dominio.Placa;
import org.jspecify.annotations.Nullable;

/**
 * Lo que filtra el padrón de constancias libres de infracciones ({@code
 * transito_padron_constancias} de #53, RF-068). Todos los campos son opcionales y se combinan con
 * Y.
 *
 * @param desde fecha de emisión, límite inferior
 * @param hasta fecha de emisión, límite superior
 * @param numero el número de la constancia
 * @param usuarioQueEmitio quién la emitió
 * @param placa la placa sobre la que se acreditó
 */
public record CriterioDeConstancias(
        @Nullable LocalDate desde,
        @Nullable LocalDate hasta,
        @Nullable String numero,
        @Nullable String usuarioQueEmitio,
        @Nullable String placa) {

    public CriterioDeConstancias {
        numero = limpiar(numero);
        usuarioQueEmitio = limpiar(usuarioQueEmitio);
        placa = placaEscrita(placa);
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new IllegalArgumentException("«hasta» no puede ser anterior a «desde»");
        }
    }

    /** La placa la escribe {@link Placa}, y no una copia de aqui (#423). En blanco no filtra. */
    private static @Nullable String placaEscrita(@Nullable String valor) {
        return valor == null || valor.isBlank() ? null : Placa.formaEscrita(valor);
    }

    private static @Nullable String limpiar(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio.toUpperCase(Locale.ROOT);
    }
}
