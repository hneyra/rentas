package kamayuk.rentas.fiscalizacion.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Las transferencias a rentas con su resolucion de determinacion. Ningun metodo recibe la
 * municipalidad (regla 2).
 *
 * <p><b>No hay {@code actualizar} ni {@code eliminar}, y no es una omision.</b> {@link #registrar}
 * es el unico punto de escritura: la resolucion se notifica al contribuyente, que se lleva el
 * papel, y su cargo ya esta en el libro. Corregirla en el sitio dejaria al papel, al libro y a la
 * base diciendo tres cosas distintas. V49 no le concede {@code UPDATE} a {@code kamayuk_app}, y el
 * escaner del codigo fuente lo vigila ademas en {@code TABLAS_INMUTABLES}.
 */
public interface ResolucionDeDeterminacionRepository {

    /**
     * Registra la transferencia y su resolucion.
     *
     * @throws LiquidacionYaTransferida si esa liquidacion ya se transfirio. La detecta la base con
     *     {@code resolucion_determinacion_liquidacion_uq} (V49), no una comprobacion en Java: dos
     *     peticiones simultaneas pasan las dos por cualquier {@code if} (AC 6)
     */
    ResolucionDeDeterminacion registrar(ResolucionDeDeterminacion resolucion);

    /** La resolucion por su numero, que es el del papel. Vacio si no existe o es de otra muni. */
    Optional<ResolucionDeDeterminacion> porNumero(String numero);

    /** La transferencia de una liquidacion, si ya se transfirio. */
    Optional<ResolucionDeDeterminacion> deLiquidacion(long liquidacionId);

    /**
     * Las transferencias que se le hicieron a un contribuyente, de la mas reciente a la primera.
     */
    List<ResolucionDeDeterminacion> deContribuyente(long contribuyenteId);

    /** Esa liquidacion ya se transfirio: transferirla otra vez duplicaria versiones y cargos. */
    final class LiquidacionYaTransferida extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public LiquidacionYaTransferida(long liquidacionId) {
            super(
                    "La liquidacion "
                            + liquidacionId
                            + " ya se transfirio al padron: transferirla otra vez abriria una"
                            + " segunda version de la ficha y asentaria los cargos por duplicado");
        }
    }
}
