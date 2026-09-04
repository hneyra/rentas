package kamayuk.rentas.valores.infraestructura.web;

import kamayuk.rentas.valores.dominio.Notificacion;
import org.jspecify.annotations.Nullable;

/**
 * Como sale una diligencia por HTTP (RF-093, #39).
 *
 * <p>Las tres fechas van juntas y en texto ISO. {@code exigibleDesde} es {@code null} cuando la
 * diligencia no surtio efecto, y eso es informacion, no un hueco: dice que el plazo no empezo a
 * correr y que hay que volver a diligenciar.
 *
 * <p>Ninguna cifra de dinero sale por aqui, asi que no hay ningun {@code actualizadoA} que
 * acompanar: lo que esta respuesta describe es un acto, no un importe.
 */
public record NotificacionResource(
        long id,
        String numeroDeValor,
        int intento,
        String fechaDeNotificacion,
        String modalidad,
        String resultado,
        String notificador,
        String direccion,
        @Nullable String personaQueRecibe,
        @Nullable String documentoDeQuienRecibe,
        @Nullable String vinculo,
        @Nullable String acuse,
        boolean surtioEfecto,
        @Nullable String exigibleDesde,
        String observacion) {

    public static NotificacionResource de(Notificacion notificacion, String numeroDeValor) {
        return new NotificacionResource(
                requerido(notificacion.id()),
                numeroDeValor,
                notificacion.intento(),
                notificacion.fechaDeLaDiligencia().toString(),
                notificacion.modalidad().name(),
                notificacion.resultado().name(),
                notificacion.notificador(),
                notificacion.direccion(),
                notificacion.receptor(),
                notificacion.documentoReceptor(),
                notificacion.vinculo(),
                notificacion.acuse(),
                notificacion.surtioEfecto(),
                notificacion.exigibleDesde() == null
                        ? null
                        : notificacion.exigibleDesde().toString(),
                notificacion.observacion().texto());
    }

    private static long requerido(@Nullable Long id) {
        return java.util.Objects.requireNonNull(
                id, "Una diligencia que sale por HTTP ya esta guardada");
    }
}
