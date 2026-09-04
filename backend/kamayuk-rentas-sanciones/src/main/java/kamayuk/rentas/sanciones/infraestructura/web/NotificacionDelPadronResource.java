package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.sanciones.dominio.NotificacionDelPadron;
import org.jspecify.annotations.Nullable;

/**
 * Una fila del padrón de notificaciones administrativas, por HTTP (#53, RF-074).
 *
 * <p>{@code actualizadoA} es la fecha de la infracción de la papeleta cuando la hay, y la de la
 * propia notificación cuando no: el importe que sale es el <b>del acta</b>, y esa es su fecha
 * (regla 9, RNF-075). Poner «hoy» diría que la cifra está actualizada, y no lo está.
 */
public record NotificacionDelPadronResource(
        String numero,
        LocalDate fecha,
        String direccion,
        String motivo,
        @Nullable Integer plazoDias,
        String estado,
        boolean tienePapeleta,
        @Nullable String papeletaNumero,
        @Nullable String papeletaEstado,
        @Nullable Dinero importeDeLaPapeleta,
        LocalDate actualizadoA) {

    public static NotificacionDelPadronResource de(NotificacionDelPadron fila) {
        return new NotificacionDelPadronResource(
                fila.numero(),
                fila.fecha(),
                fila.direccion(),
                fila.motivo(),
                fila.plazoDias() == null ? null : (int) fila.plazoDias(),
                fila.estado().name(),
                fila.tienePapeleta(),
                fila.papeletaNumero(),
                fila.papeletaEstado() == null ? null : fila.papeletaEstado().name(),
                fila.importeDeLaPapeleta(),
                fila.fecha());
    }
}
