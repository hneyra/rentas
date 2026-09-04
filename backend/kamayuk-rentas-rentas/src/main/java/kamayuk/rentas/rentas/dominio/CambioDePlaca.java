package kamayuk.rentas.rentas.dominio;

import java.time.OffsetDateTime;
import java.util.Objects;
import kamayuk.rentas.dominio.Placa;

/**
 * Un cambio de placa, leido de la auditoria.
 *
 * <p>Lleva la observacion porque es lo que explica el cambio —una regrabacion, un duplicado, un
 * error de digitacion en el alta— y sin ella el historial dice que paso pero no por que, que es lo
 * que se pregunta cuando alguien reclama una papeleta.
 */
public record CambioDePlaca(
        Placa anterior, Placa nueva, String usuario, OffsetDateTime fecha, String observacion) {

    public CambioDePlaca {
        Objects.requireNonNull(anterior, "Un cambio de placa tiene una placa anterior");
        Objects.requireNonNull(nueva, "Un cambio de placa tiene una placa nueva");
        Objects.requireNonNull(usuario, "Un cambio de placa tiene quien lo hizo");
        Objects.requireNonNull(fecha, "Un cambio de placa tiene cuando fue");
        Objects.requireNonNull(observacion, "Un cambio de placa tiene por que se hizo");
    }
}
