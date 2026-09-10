package kamayuk.rentas.seguridad.dominio;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Un evento tal como lo sirve {@code GET /identidad/api/v1/eventos/pendientes}.
 *
 * <p>El cuerpo es TEXTO con JSON dentro —la fila ENTERA tal como quedo en {@code identidad}, no un
 * delta— y la huella es el sha256 del cuerpo canonico que el emisor calculo. El tipo se guarda
 * <b>tal como llego</b> ({@link #tipoPublicado()}): si esta copia no lo conoce, {@link #tipo()} es
 * {@code null} y el nombre que llego es lo unico que dice cual falta.
 */
public record EventoDeIdentidadRecibido(
        UUID eventoId,
        long secuencia,
        String tipoPublicado,
        long sujetoId,
        String cuerpo,
        String huella,
        Instant creadoEn) {

    public EventoDeIdentidadRecibido {
        Objects.requireNonNull(eventoId, "Un evento recibido tiene identidad");
        Objects.requireNonNull(tipoPublicado, "Un evento recibido dice de que tipo es");
        Objects.requireNonNull(cuerpo, "Un evento recibido lleva su cuerpo");
        Objects.requireNonNull(huella, "Un evento recibido lleva su huella");
        Objects.requireNonNull(creadoEn, "Un evento recibido sabe cuando se emitio");
        // Un evento SIN tipo no es un tipo que no se sepa aplicar: es una respuesta que no tiene
        // la forma de un evento, y esa se arregla mirando el despliegue (la leccion de #54).
        if (tipoPublicado.isBlank()) {
            throw new IllegalArgumentException("Un evento recibido dice de que tipo es, y este no");
        }
        if (secuencia < 0) {
            throw new IllegalArgumentException("La secuencia no puede ser negativa: " + secuencia);
        }
    }

    /** El tipo, o {@code null} si esta copia no lo conoce. */
    public @Nullable TipoDeEventoDeIdentidad tipo() {
        return TipoDeEventoDeIdentidad.declarado(tipoPublicado);
    }
}
