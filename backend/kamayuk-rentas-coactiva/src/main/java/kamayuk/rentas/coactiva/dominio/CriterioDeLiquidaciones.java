package kamayuk.rentas.coactiva.dominio;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Lo que pide la grilla «Liquidaciones encontradas» de {@code costas_procesales} (#42, RF-104).
 *
 * <p>Todos los criterios son opcionales y se combinan con Y, como en {@code CriterioDeExpedientes}.
 *
 * <p><b>El estado no esta aqui</b>, y no es un olvido: {@link EstadoDeLaLiquidacion} se deriva del
 * libro a una fecha, no de una columna, asi que filtrar por el en SQL exigiria consultar la deuda
 * de cada fila <b>antes</b> de paginar. Se filtra despues de componer la pagina, en {@code
 * ConsultaDeCostas}, y lo que la consulta publica es cuantas liquidaciones cumplen <b>este</b>
 * criterio —{@code liquidacionesDelCriterio}, no {@code totalElementos}—: el recuento que reparte
 * las paginas, no el de las filas que quedaron (#425).
 *
 * @param numero el «Nro. Liquidacion», exacto
 * @param numeroDeExpediente el «Nro. Exped. Coact.», exacto
 * @param contribuyenteId el obligado, ya resuelto por el borde HTTP
 */
public record CriterioDeLiquidaciones(
        @Nullable String numero,
        @Nullable String numeroDeExpediente,
        @Nullable Long contribuyenteId) {

    public CriterioDeLiquidaciones {
        numero = limpiar(numero);
        numeroDeExpediente = limpiar(numeroDeExpediente);
    }

    public static CriterioDeLiquidaciones todas() {
        return new CriterioDeLiquidaciones(null, null, null);
    }

    private static @Nullable String limpiar(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio.toUpperCase(Locale.ROOT);
    }
}
