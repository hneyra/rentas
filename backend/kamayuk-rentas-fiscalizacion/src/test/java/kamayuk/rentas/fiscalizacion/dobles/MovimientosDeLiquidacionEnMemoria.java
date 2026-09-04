package kamayuk.rentas.fiscalizacion.dobles;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeMovimientoDeLiquidacion;

/**
 * El historial en memoria.
 *
 * <p>Reproduce la unicidad de la apertura que {@code liquidacion_movimiento_apertura_uq} (V39)
 * garantiza en la base: sin ella, una prueba de caso de uso podria abrir dos veces la misma
 * liquidacion y pasar en verde, mientras la base real lo rechaza.
 */
public final class MovimientosDeLiquidacionEnMemoria implements MovimientoDeLiquidacionRepository {

    private final List<MovimientoDeLiquidacion> guardados = new ArrayList<>();
    private long siguiente = 1;

    @Override
    public MovimientoDeLiquidacion insertar(MovimientoDeLiquidacion movimiento) {
        if (movimiento.tipo() == TipoDeMovimientoDeLiquidacion.APERTURA
                && guardados.stream()
                        .anyMatch(
                                otro ->
                                        otro.liquidacionId() == movimiento.liquidacionId()
                                                && otro.tipo()
                                                        == TipoDeMovimientoDeLiquidacion
                                                                .APERTURA)) {
            throw new AperturaDuplicada(movimiento.liquidacionId());
        }
        MovimientoDeLiquidacion guardado =
                new MovimientoDeLiquidacion(
                        siguiente++,
                        movimiento.liquidacionId(),
                        movimiento.tipo(),
                        movimiento.estado(),
                        movimiento.fecha(),
                        movimiento.motivo(),
                        "pruebas",
                        movimiento.observacion());
        guardados.add(guardado);
        return guardado;
    }

    @Override
    public List<MovimientoDeLiquidacion> deLiquidacion(long liquidacionId) {
        return guardados.stream().filter(m -> m.liquidacionId() == liquidacionId).toList();
    }
}
