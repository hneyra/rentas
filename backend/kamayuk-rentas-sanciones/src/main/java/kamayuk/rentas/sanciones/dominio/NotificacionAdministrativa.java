package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * La notificación administrativa previa (V4, #47, RF-070): "un paso previo a la generación de la
 * multa administrativa" que "puede o no" existir antes de una {@link Papeleta} de familia {@link
 * Familia#ADMINISTRATIVA}.
 *
 * <p>La tabla no lleva columna {@code observacion}: a diferencia de {@code papeleta}, aquí el "por
 * qué" queda solo en la bitácora de auditoría, no en la fila —así lo definió el esquema (V4)—; por
 * eso este tipo no la carga, aunque {@code RegistrarNotificacionAdministrativa} la exija como
 * argumento (regla 10) para poder auditar.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param numero identifica la notificación
 * @param fecha cuándo se emitió
 * @param contribuyenteId el administrado notificado, si se identificó
 * @param predioId el predio inspeccionado, si aplica
 * @param direccion donde se entregó
 * @param motivo por qué se notifica
 * @param plazoDias cuántos días tiene para subsanar; nulo si el acta no trae un plazo —en ese caso
 *     {@link #vencimiento} no existe y nada la vence
 * @param estado en qué punto está
 * @param usuarioRegistro quien la registró; nulo en una notificación que todavía no se guardó
 */
public record NotificacionAdministrativa(
        @Nullable Long id,
        String numero,
        LocalDate fecha,
        @Nullable Long contribuyenteId,
        @Nullable Long predioId,
        String direccion,
        String motivo,
        @Nullable Short plazoDias,
        EstadoDeNotificacion estado,
        @Nullable String usuarioRegistro) {

    private static final int NUMERO_MAXIMO = 20;
    private static final int DIRECCION_MAXIMA = 300;
    private static final int MOTIVO_MAXIMO = 500;

    public NotificacionAdministrativa {
        Objects.requireNonNull(numero, "La notificacion necesita su numero");
        numero = numero.strip().toUpperCase(Locale.ROOT);
        if (numero.isEmpty() || numero.length() > NUMERO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero va de 1 a " + NUMERO_MAXIMO + " caracteres: '" + numero + "'");
        }
        Objects.requireNonNull(fecha, "La notificacion necesita su fecha");
        Objects.requireNonNull(direccion, "La notificacion necesita la direccion de entrega");
        direccion = direccion.strip();
        if (direccion.isEmpty() || direccion.length() > DIRECCION_MAXIMA) {
            throw new IllegalArgumentException(
                    "La direccion va de 1 a " + DIRECCION_MAXIMA + " caracteres");
        }
        Objects.requireNonNull(motivo, "La notificacion necesita su motivo");
        motivo = motivo.strip();
        if (motivo.isEmpty() || motivo.length() > MOTIVO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El motivo va de 1 a " + MOTIVO_MAXIMO + " caracteres");
        }
        if (plazoDias != null && plazoDias <= 0) {
            throw new IllegalArgumentException("El plazo de subsanacion, si se da, es positivo");
        }
        Objects.requireNonNull(estado, "La notificacion necesita su estado");
    }

    /** Una notificación nueva, todavía sin guardar: nace {@code EMITIDA}. */
    public static NotificacionAdministrativa emitida(
            String numero,
            LocalDate fecha,
            @Nullable Long contribuyenteId,
            @Nullable Long predioId,
            String direccion,
            String motivo,
            @Nullable Short plazoDias) {
        return new NotificacionAdministrativa(
                null,
                numero,
                fecha,
                contribuyenteId,
                predioId,
                direccion,
                motivo,
                plazoDias,
                EstadoDeNotificacion.EMITIDA,
                null);
    }

    public boolean esNueva() {
        return id == null;
    }

    /**
     * El último día para subsanar, o vacío si el acta no trajo plazo —entonces nada la vence (#47
     * AC3).
     */
    public Optional<LocalDate> vencimiento() {
        return plazoDias == null ? Optional.empty() : Optional.of(fecha.plusDays(plazoDias));
    }

    /** La misma notificación, cerrada por subsanación (#47 AC2). */
    public NotificacionAdministrativa subsanada() {
        Objects.requireNonNull(id, "Solo se subsana una notificacion ya guardada");
        return new NotificacionAdministrativa(
                id,
                numero,
                fecha,
                contribuyenteId,
                predioId,
                direccion,
                motivo,
                plazoDias,
                EstadoDeNotificacion.SUBSANADA,
                usuarioRegistro);
    }
}
