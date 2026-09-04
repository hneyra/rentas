package kamayuk.rentas.contribuyentes.dominio;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Un telefono, un correo, un gestor o un contacto del contribuyente (RF-013).
 *
 * <p>El manual los tiene en fichas separadas —Contactos, Gestores, Telefonos-Email—; aqui van en
 * una sola tabla tipada. Son la misma cosa: una forma de ubicar a alguien, con un tipo.
 *
 * <p>Se da de baja con {@code vigente}, no se borra: un gestor que ya no lo es aparece en
 * notificaciones anteriores, y explicar por que se le notifico exige que su ficha siga ahi.
 *
 * @param nombre de quien es el contacto, cuando no es el propio contribuyente
 */
public record Contacto(
        @Nullable Long id,
        long contribuyenteId,
        TipoContacto tipo,
        String valor,
        @Nullable String nombre,
        @Nullable String documento,
        @Nullable String observacion,
        boolean vigente) {

    private static final int VALOR_MAXIMO = 200;
    private static final int NOMBRE_MAXIMO = 240;
    private static final int DOCUMENTO_MAXIMO = 20;
    private static final int OBSERVACION_MAXIMA = 300;

    public Contacto {
        Objects.requireNonNull(tipo, "El contacto necesita su tipo");
        Objects.requireNonNull(valor, "El contacto necesita su valor");

        valor = valor.strip();
        if (valor.isEmpty() || valor.length() > VALOR_MAXIMO) {
            throw new IllegalArgumentException(
                    "El valor del contacto va de 1 a " + VALOR_MAXIMO + " caracteres");
        }
        if (nombre != null && nombre.strip().length() > NOMBRE_MAXIMO) {
            throw new IllegalArgumentException(
                    "El nombre del contacto excede " + NOMBRE_MAXIMO + " caracteres");
        }
        if (documento != null && documento.strip().length() > DOCUMENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento del contacto excede " + DOCUMENTO_MAXIMO + " caracteres");
        }
        if (observacion != null && observacion.strip().length() > OBSERVACION_MAXIMA) {
            throw new IllegalArgumentException(
                    "La observacion del contacto excede " + OBSERVACION_MAXIMA + " caracteres");
        }
        // Un correo sin arroba no es un correo, y se descubre el dia que falla el envio
        // masivo de la emision.
        if (tipo == TipoContacto.EMAIL && !valor.contains("@")) {
            throw new IllegalArgumentException("Un correo lleva arroba: '" + valor + "'");
        }
    }

    public static Contacto nuevo(long contribuyenteId, TipoContacto tipo, String valor) {
        return new Contacto(null, contribuyenteId, tipo, valor, null, null, null, true);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** Deja de usarse. No se borra: aparece en notificaciones ya hechas. */
    public Contacto dadoDeBaja() {
        return new Contacto(
                id, contribuyenteId, tipo, valor, nombre, documento, observacion, false);
    }
}
