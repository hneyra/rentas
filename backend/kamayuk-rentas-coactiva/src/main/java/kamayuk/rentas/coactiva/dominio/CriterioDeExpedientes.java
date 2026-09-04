package kamayuk.rentas.coactiva.dominio;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Los filtros de la grilla {@code coactiva_expedientes} (RF-100), ya resueltos.
 *
 * <p>{@code contribuyenteId} llega como identificador y no como «Cod. Contribuyente»: quien arma el
 * criterio ya lo resolvio contra {@code DirectorioDeContribuyentes} (ARQ-01 §4 regla 2).
 *
 * <p>{@code estado} <b>no es una columna</b> —se deriva del historial—, y aun asi se filtra por el.
 * Se resuelve en SQL con la misma regla que {@link EstadoDelExpediente#delHistorial}: el ultimo
 * movimiento que lleve estado. Filtrar en Java las veinte filas que la base devolvio daria un total
 * equivocado —«1 de 47» sobre 47 sin filtrar— y paginas con menos de veinte lineas sin motivo
 * visible, que es exactamente lo que #25 ya aprendio con {@code consulta_valores}.
 *
 * @param numero el numero impreso del expediente, si se busca uno
 * @param contribuyenteId el obligado
 * @param ejecutor el ejecutor coactivo, exacto
 * @param estado en que punto esta el procedimiento; nulo es «todos»
 * @param ejercicio el ejercicio del expediente
 */
public record CriterioDeExpedientes(
        @Nullable String numero,
        @Nullable Long contribuyenteId,
        @Nullable String ejecutor,
        @Nullable EstadoDelExpediente estado,
        @Nullable Integer ejercicio) {

    public CriterioDeExpedientes {
        numero = mayusculas(numero);
        ejecutor = mayusculas(ejecutor);
    }

    /** Sin ningun filtro: todos los expedientes de la municipalidad. */
    public static CriterioDeExpedientes todos() {
        return new CriterioDeExpedientes(null, null, null, null, null);
    }

    private static @Nullable String mayusculas(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip().toUpperCase(Locale.ROOT);
        return limpio.isEmpty() ? null : limpio;
    }
}
