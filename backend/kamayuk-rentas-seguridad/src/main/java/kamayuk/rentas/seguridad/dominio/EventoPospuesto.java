package kamayuk.rentas.seguridad.dominio;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Un evento que TODAVIA no se puede aplicar —su dependencia no ha llegado— con el motivo que lo
 * dejo asi.
 *
 * <p>No se acusa y no se aparta, asi que el buzon lo vuelve a servir en la vuelta siguiente. Eso es
 * lo correcto mientras la dependencia este en camino, y es exactamente lo que lo hace invisible
 * cuando NO lo esta: la corrida sigue saliendo bien, la copia se queda sin ese permiso y nadie se
 * entera. Por eso el consumidor los junta y {@link AlertaDeEventosSinAplicar} avisa de los que
 * llevan demasiado tiempo esperando.
 *
 * @param evento el evento tal como lo sirvio {@code identidad}
 * @param motivo lo que le falta a esta copia para poder aplicarlo
 */
public record EventoPospuesto(EventoDeIdentidadRecibido evento, String motivo) {

    public EventoPospuesto {
        Objects.requireNonNull(evento, "Un pospuesto es un evento");
        Objects.requireNonNull(motivo, "Un pospuesto dice que le falta");
    }

    /**
     * Cuanto lleva esperando, contado desde que {@code identidad} lo emitio.
     *
     * <p>Se mide con {@code creadoEn} —que el buzon publica en cada evento— y no con cuando esta
     * copia lo leyo por primera vez: un consumidor que se reinicia cada cinco minutos no recuerda
     * la primera vez, y el instante del emisor es el mismo para los cuatro consumidores.
     */
    public Duration edad(Instant ahora) {
        return Duration.between(evento.creadoEn(), ahora);
    }
}
