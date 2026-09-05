package kamayuk.rentas.rentas.dominio.proyeccion;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Un hecho tal como llega de {@code catastro} (C-8).
 *
 * @param secuencia el orden en que el emisor lo produjo. Es lo que impide que un hecho VIEJO que
 *     llega tarde pise a uno nuevo ya aplicado — el defecto que no se ve porque la fila queda
 *     plausible (`V4`)
 * @param cuerpo el JSON entero, TAL COMO LLEGO. No se reserializa: la huella describe estos bytes
 * @param huella sha256 del cuerpo canonico, calculada POR EL EMISOR. Aqui <b>no se recalcula</b>
 *     (`V9`): recalcularla comprobaria que lo que se tiene es igual a lo que se tiene
 */
public record HechoRecibido(
        UUID eventoId,
        long secuencia,
        TipoDeHechoDeCatastro tipo,
        @Nullable Long predioId,
        @Nullable Integer ejercicio,
        String cuerpo,
        String huella,
        Instant emitidoEn) {

    public HechoRecibido {
        Objects.requireNonNull(eventoId, "Un hecho recibido tiene identidad");
        Objects.requireNonNull(tipo, "Un hecho recibido dice de que tipo es");
        Objects.requireNonNull(cuerpo, "Un hecho recibido lleva su cuerpo");
        Objects.requireNonNull(huella, "Un hecho recibido lleva su huella");
        Objects.requireNonNull(emitidoEn, "Un hecho recibido sabe cuando se emitio");
        if (huella.length() != 64) {
            throw new IllegalArgumentException(
                    "La huella es un sha256 en hexadecimal: 64 caracteres, y esta tiene "
                            + huella.length());
        }
        if (secuencia < 0) {
            throw new IllegalArgumentException("La secuencia no puede ser negativa: " + secuencia);
        }
    }
}
