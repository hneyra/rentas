package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Lo que pide {@code adm_notificaciones_vencidas} (#47, RF-074): las notificaciones {@code EMITIDA}
 * cuyo plazo de subsanación —{@code fecha + plazoDias}, el <b>plazo parametrizado</b> de cada fila,
 * nunca uno fijo en el código (#47 AC3)— venció antes de {@link #vencidasAl}.
 *
 * @param numero de la notificación
 * @param estado si se quiere acotar a un estado distinto de {@code EMITIDA}
 * @param vencidasAl la fecha de corte contra la que se compara el vencimiento; sin ella, la pide el
 *     controlador al reloj inyectado
 * @param registradoPor quien la registró —lo más cerca que hay de "fiscalizador" en la fila, que no
 *     tiene esa columna—
 * @param motivoContiene texto libre buscado dentro de {@code motivo}
 * @param conPapeleta si se acota a las que ya tienen (o no) una papeleta administrativa enlazada
 *     por {@code notificacion_previa_id}; sin fijar, no filtra
 */
public record CriterioDeNotificacion(
        @Nullable String numero,
        @Nullable EstadoDeNotificacion estado,
        LocalDate vencidasAl,
        @Nullable String registradoPor,
        @Nullable String motivoContiene,
        @Nullable Boolean conPapeleta) {

    public CriterioDeNotificacion {
        Objects.requireNonNull(vencidasAl, "El criterio necesita la fecha de corte");
        numero = limpiar(numero);
        registradoPor = limpiar(registradoPor);
        motivoContiene =
                motivoContiene == null || motivoContiene.isBlank() ? null : motivoContiene.strip();
    }

    private static @Nullable String limpiar(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio.toUpperCase(Locale.ROOT);
    }
}
