package kamayuk.rentas.sanciones.infraestructura.web;

import kamayuk.rentas.sanciones.dominio.NotificacionAdministrativa;
import org.jspecify.annotations.Nullable;

/** Una notificación administrativa tal como sale por HTTP. Campos en español {@code camelCase}. */
public record NotificacionAdministrativaResource(
        long id,
        String numero,
        String fecha,
        @Nullable Long contribuyenteId,
        @Nullable Long predioId,
        String direccion,
        String motivo,
        @Nullable Integer plazoDias,
        @Nullable String vencimiento,
        String estado,
        @Nullable String usuarioRegistro) {

    public static NotificacionAdministrativaResource de(NotificacionAdministrativa notificacion) {
        return new NotificacionAdministrativaResource(
                notificacion.id() == null ? 0L : notificacion.id(),
                notificacion.numero(),
                notificacion.fecha().toString(),
                notificacion.contribuyenteId(),
                notificacion.predioId(),
                notificacion.direccion(),
                notificacion.motivo(),
                notificacion.plazoDias() == null ? null : notificacion.plazoDias().intValue(),
                notificacion.vencimiento().map(Object::toString).orElse(null),
                notificacion.estado().name(),
                notificacion.usuarioRegistro());
    }
}
