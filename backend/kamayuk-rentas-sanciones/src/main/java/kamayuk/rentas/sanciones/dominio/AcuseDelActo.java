package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import org.jspecify.annotations.Nullable;

/**
 * Una diligencia de notificación de un acto de la papeleta, tal como la lista {@code
 * transito_documentos} (#50, RF-065).
 *
 * <p>Una fila por intento, no la última: el AC de #50 pide «su fecha y su acuse», y un acto
 * notificado al tercer intento tiene tres. Quedarse con la última escondería que las dos anteriores
 * no encontraron a nadie, que es justamente lo que hay que poder mostrar cuando el administrado
 * discute la notificación.
 *
 * @param intento qué diligencia es, desde 1
 * @param fecha cuándo se diligenció
 * @param modalidad cómo (art. 104 del TUO del Código Tributario)
 * @param resultado con qué resultado terminó
 * @param receptor quién recibió; nulo si nadie recibió
 * @param acuse la constancia del cargo; nulo si no la hubo
 * @param exigibleDesde desde cuándo abre plazo; nulo si la diligencia no surtió efecto
 */
public record AcuseDelActo(
        int intento,
        LocalDate fecha,
        ModalidadDeNotificacion modalidad,
        ResultadoDeNotificacion resultado,
        @Nullable String receptor,
        @Nullable String acuse,
        @Nullable LocalDate exigibleDesde) {

    public AcuseDelActo {
        if (intento < 1) {
            throw new IllegalArgumentException("El primer intento es el 1, no el " + intento);
        }
        Objects.requireNonNull(fecha, "Toda diligencia dice cuando se practico");
        Objects.requireNonNull(modalidad, "Toda diligencia dice como se practico");
        Objects.requireNonNull(resultado, "Una diligencia sin resultado no es un acuse");
    }
}
