package kamayuk.rentas.catastro.infraestructura.web;

import kamayuk.rentas.catastro.dominio.Depreciacion;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la tabla de depreciacion, tal como sale por HTTP (ARQ-04 §3).
 *
 * <p>Publica el {@code uso} porque sin el la lista es indescifrable: el Anexo I tabula cuatro
 * tablas, asi que la misma combinacion de material, estado y antiguedad aparece hasta cuatro veces
 * con porcentajes distintos, y quien la lee no tendria con que distinguirlas. La pantalla del
 * prototipo ya tenia su filtro «Uso» —esta en el contrato desde #312— esperando este dato.
 *
 * @param antiguedadHasta nulo en el tramo abierto de cada tabla, «mas de 50 anios»
 */
public record DepreciacionResource(
        long id,
        String uso,
        String material,
        String estadoConservacion,
        @Nullable Integer antiguedadHasta,
        String porcentaje,
        String documentoFuente) {

    public static DepreciacionResource de(Depreciacion depreciacion) {
        return new DepreciacionResource(
                depreciacion.id() == null ? 0L : depreciacion.id(),
                depreciacion.uso(),
                depreciacion.material(),
                depreciacion.estadoConservacion(),
                depreciacion.antiguedadHasta(),
                depreciacion.porcentaje().valor().toPlainString(),
                depreciacion.documentoFuente());
    }
}
