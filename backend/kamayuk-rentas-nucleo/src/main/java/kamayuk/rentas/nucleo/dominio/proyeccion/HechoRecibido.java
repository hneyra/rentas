package kamayuk.rentas.nucleo.dominio.proyeccion;

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
 * @param tipoPublicado el nombre del tipo <b>TAL COMO EL EMISOR LO ESCRIBIO</b>, y no el enumerado
 *     de este lado. Es una sola fuente de verdad a proposito: guardar los dos —lo que el emisor
 *     dijo y lo que este sistema entendio— permitiria que discreparan, y ademas un tipo que aqui no
 *     se sabe aplicar <b>no tiene</b> valor de enumerado y aun asi hay que nombrarlo en el aviso
 *     (#54)
 * @param cuerpo el JSON entero, TAL COMO LLEGO. No se reserializa: la huella describe estos bytes
 * @param huella sha256 del cuerpo canonico, calculada POR EL EMISOR. Aqui <b>no se recalcula</b>
 *     (`V9`): recalcularla comprobaria que lo que se tiene es igual a lo que se tiene
 */
public record HechoRecibido(
        UUID eventoId,
        long secuencia,
        String tipoPublicado,
        @Nullable Long predioId,
        @Nullable Integer ejercicio,
        String cuerpo,
        String huella,
        Instant emitidoEn) {

    public HechoRecibido {
        Objects.requireNonNull(eventoId, "Un hecho recibido tiene identidad");
        Objects.requireNonNull(tipoPublicado, "Un hecho recibido dice de que tipo es");
        Objects.requireNonNull(cuerpo, "Un hecho recibido lleva su cuerpo");
        Objects.requireNonNull(huella, "Un hecho recibido lleva su huella");
        Objects.requireNonNull(emitidoEn, "Un hecho recibido sabe cuando se emitio");
        // UN HECHO SIN TIPO NO ES UN TIPO QUE NO SE SEPA APLICAR: es una respuesta que no tiene la
        // forma de un hecho, y esa se arregla mirando el despliegue. Las dos se distinguen a
        // proposito, porque no se atienden igual (#54).
        if (tipoPublicado.isBlank()) {
            throw new IllegalArgumentException("Un hecho recibido dice de que tipo es, y este no");
        }
        if (huella.length() != 64) {
            throw new IllegalArgumentException(
                    "La huella es un sha256 en hexadecimal: 64 caracteres, y esta tiene "
                            + huella.length());
        }
        if (secuencia < 0) {
            throw new IllegalArgumentException("La secuencia no puede ser negativa: " + secuencia);
        }
    }

    /**
     * El tipo que este sistema sabe aplicar para este hecho, o {@code null} si no sabe ninguno.
     *
     * <p>Se deriva y no se guarda: {@link #tipoPublicado()} es la unica fuente. Un {@code null}
     * aqui <b>no</b> es un fallo — es un tipo del territorio, o el octavo que {@code catastro}
     * publique manana—, y lo que la ingestion hace con el lo dice el javadoc de {@link
     * TipoDeHechoDeCatastro}: se ignora, se avisa nombrandolo y la vuelta sigue (#54).
     */
    public @Nullable TipoDeHechoDeCatastro tipo() {
        return TipoDeHechoDeCatastro.declarado(tipoPublicado);
    }
}
