package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Lo que filtra el padrón de notificaciones administrativas ({@code adm_padron_notificaciones} de
 * #53, RF-074).
 *
 * <h2>Por qué no es {@link CriterioDeNotificacion}</h2>
 *
 * <p>Aquel resuelve <b>otra pregunta</b>: «cuáles vencieron sin subsanarse», y por eso exige la
 * fecha de corte y compara siempre {@code fecha + plazo_dias} contra ella. Este es la relación de
 * lo <b>emitido</b> en un intervalo, con o sin plazo vencido y con o sin papeleta: reutilizar aquel
 * obligaría a colar una fecha de corte que este padrón no tiene y que cambiaría lo que devuelve.
 *
 * @param desde fecha de la notificación, límite inferior
 * @param hasta fecha de la notificación, límite superior
 * @param numero el número de la notificación
 * @param estado en qué punto está
 * @param conPapeleta si ya se le generó papeleta; sin fijar, no filtra
 */
public record CriterioDelPadronDeNotificaciones(
        LocalDate desde,
        LocalDate hasta,
        @Nullable String numero,
        @Nullable EstadoDeNotificacion estado,
        @Nullable Boolean conPapeleta) {

    public CriterioDelPadronDeNotificaciones {
        Objects.requireNonNull(desde, "El padron dice desde cuando cuenta");
        Objects.requireNonNull(hasta, "El padron dice hasta cuando cuenta");
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException("«hasta» no puede ser anterior a «desde»");
        }
        if (numero != null) {
            String limpio = numero.strip().toUpperCase(Locale.ROOT);
            numero = limpio.isEmpty() ? null : limpio;
        }
    }
}
