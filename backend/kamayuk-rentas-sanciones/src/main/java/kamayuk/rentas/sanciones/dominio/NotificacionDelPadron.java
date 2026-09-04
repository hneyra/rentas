package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Una fila del padrón de notificaciones administrativas (#53, RF-074).
 *
 * <p>«Relación de las notificaciones emitidas por el sistema y el estado de la deuda cuando ya
 * existe papeleta», dice la pantalla. Esas tres últimas columnas no están en {@code
 * notificacion_administrativa}: salen del {@code LEFT JOIN} con la papeleta que la cita en {@code
 * notificacion_previa_id}, y por eso esto no es {@link NotificacionAdministrativa}.
 *
 * <p>{@link #importeDeLaPapeleta} es el importe <b>del acta</b>, con la fecha de la infracción; no
 * es lo que se debe hoy (regla 9, RNF-075). Lo que se debe hoy lo dice el libro, y pedírselo por
 * fila serían tantas consultas como filas tenga la página.
 *
 * @param notificacionId el identificador de la notificación
 * @param numero su número
 * @param fecha cuándo se notificó
 * @param direccion dónde se entregó
 * @param motivo por qué
 * @param plazoDias el plazo de subsanación que concedió, si concedió alguno
 * @param estado en qué punto está
 * @param papeletaNumero el número de la papeleta que la cita, si ya se generó
 * @param papeletaEstado en qué punto está esa papeleta
 * @param importeDeLaPapeleta el importe de su acta
 */
public record NotificacionDelPadron(
        long notificacionId,
        String numero,
        LocalDate fecha,
        String direccion,
        String motivo,
        @Nullable Short plazoDias,
        EstadoDeNotificacion estado,
        @Nullable String papeletaNumero,
        @Nullable EstadoDePapeleta papeletaEstado,
        @Nullable Dinero importeDeLaPapeleta) {

    public NotificacionDelPadron {
        Objects.requireNonNull(numero, "La fila necesita el numero de la notificacion");
        Objects.requireNonNull(fecha, "La fila necesita su fecha");
        Objects.requireNonNull(direccion, "La fila necesita la direccion");
        Objects.requireNonNull(motivo, "La fila necesita el motivo");
        Objects.requireNonNull(estado, "La fila necesita el estado de la notificacion");

        // O hay papeleta con sus tres columnas, o no la hay: media papeleta en la fila
        // dibujaria un numero sin importe, o un importe sin saber de que papeleta es.
        boolean conPapeleta = papeletaNumero != null;
        if (conPapeleta != (papeletaEstado != null)
                || conPapeleta != (importeDeLaPapeleta != null)) {
            throw new IllegalArgumentException(
                    "La papeleta de la fila va entera —numero, estado e importe— o no va");
        }
    }

    /** Si a esta notificación ya le siguió una papeleta. */
    public boolean tienePapeleta() {
        return papeletaNumero != null;
    }
}
