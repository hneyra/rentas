package kamayuk.rentas.valores.infraestructura.web;

import kamayuk.rentas.valores.dominio.MovimientoDeValor;

/**
 * Como sale un movimiento a coactiva por HTTP (RF-095, #39).
 *
 * <p>Lleva {@code exigibleDesde} porque es lo que sustenta el pase: un expediente coactivo tiene
 * que poder decir desde cuando la deuda era exigible y de que diligencia salio esa fecha.
 */
public record MovimientoResource(
        long id,
        String numeroDeValor,
        String tipoDeMovimiento,
        String descripcion,
        String fechaDelMovimiento,
        long notificacionId,
        String exigibleDesde,
        String observacion) {

    public static MovimientoResource de(MovimientoDeValor movimiento, String numeroDeValor) {
        return new MovimientoResource(
                java.util.Objects.requireNonNull(
                        movimiento.id(), "Un movimiento que sale por HTTP ya esta guardado"),
                numeroDeValor,
                movimiento.tipo().name(),
                movimiento.tipo().descripcion(),
                movimiento.fecha().toString(),
                movimiento.notificacionId(),
                movimiento.exigibleDesde().toString(),
                movimiento.observacion().texto());
    }
}
