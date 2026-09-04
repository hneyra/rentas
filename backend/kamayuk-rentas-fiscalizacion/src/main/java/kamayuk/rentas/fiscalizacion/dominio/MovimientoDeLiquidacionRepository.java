package kamayuk.rentas.fiscalizacion.dominio;

import java.util.List;

/**
 * El historial de las liquidaciones. Ningún método recibe la municipalidad (regla 2).
 *
 * <p>Solo agrega y solo lee: no hay {@code actualizar} ni {@code eliminar}. De aquí se deriva el
 * estado ({@link EstadoDeLiquidacion#delHistorial}), y una corrección es otro movimiento.
 */
public interface MovimientoDeLiquidacionRepository {

    /**
     * Inserta el movimiento.
     *
     * @throws AperturaDuplicada si ya hay una apertura de esa liquidación. La detecta la base con
     *     {@code liquidacion_movimiento_apertura_uq} (V39), no una comprobación en Java: dos
     *     peticiones simultáneas pasan las dos por cualquier {@code if}
     */
    MovimientoDeLiquidacion insertar(MovimientoDeLiquidacion movimiento);

    /** El historial completo de una liquidación, del primer movimiento al último. */
    List<MovimientoDeLiquidacion> deLiquidacion(long liquidacionId);

    /** Ya existe la apertura de esa liquidación. */
    final class AperturaDuplicada extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public AperturaDuplicada(long liquidacionId) {
            super(
                    "La liquidacion "
                            + liquidacionId
                            + " ya esta abierta: una liquidacion se abre una vez, y el resto de su"
                            + " vida son cambios de estado");
        }
    }
}
