package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Los filtros de {@code consulta_valores} (RF-041, #25), ya resueltos.
 *
 * <h2>Por que no reusa {@link CriterioDeValor}</h2>
 *
 * <p>Porque no filtran por lo mismo. {@link CriterioDeValor} es el de {@code valores_busqueda}
 * (RF-092): filtra por columnas de la cabecera y nada mas. Este ademas filtra por {@link
 * SituacionDelValor}, que <b>no es una columna</b> —depende de la notificacion que surtio efecto y
 * del pase a coactiva— y que solo tiene sentido <b>a una fecha</b>. Meter los dos en un mismo
 * record obligaria a que la busqueda de #37 arrastrara una fecha que no usa, y a que quien lea
 * {@code buscar} tenga que averiguar cual de los seis campos aplica a su consulta.
 *
 * <p>{@code contribuyenteId} llega como identificador, no como {@code codContribuyente}: quien arma
 * el criterio ya lo resolvio contra {@code DirectorioDeContribuyentes} (ARQ-01 §4 regla 2).
 *
 * @param numero el numero exacto del valor, si se busca uno
 * @param contribuyenteId a quien se le emitio
 * @param tipo OP, RD o RM
 * @param ejercicio el ejercicio de emision de la cabecera
 * @param situacion en que punto de la cobranza; nulo es «todos»
 * @param fecha desde que dia se mira la situacion (regla 9): sin ella, «exigible» no significa nada
 */
public record CriterioDeConsultaDeValores(
        @Nullable String numero,
        @Nullable Long contribuyenteId,
        @Nullable TipoValor tipo,
        @Nullable Integer ejercicio,
        @Nullable SituacionDelValor situacion,
        LocalDate fecha) {

    public CriterioDeConsultaDeValores {
        numero = limpio(numero);
        Objects.requireNonNull(
                fecha,
                "La situacion de un valor se mira a una fecha, nunca «ahora mismo» (regla 9)");
    }

    /** Sin ningun filtro: todos los valores de la municipalidad, mirados a esa fecha. */
    public static CriterioDeConsultaDeValores a(LocalDate fecha) {
        return new CriterioDeConsultaDeValores(null, null, null, null, null, fecha);
    }

    private static @Nullable String limpio(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String recortado = texto.strip();
        return recortado.isEmpty() ? null : recortado;
    }
}
