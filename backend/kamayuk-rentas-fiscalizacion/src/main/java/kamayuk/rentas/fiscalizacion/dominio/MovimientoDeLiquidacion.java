package kamayuk.rentas.fiscalizacion.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Una línea del historial de una liquidación: su apertura, o un cambio de estado (#49, RF-056).
 *
 * <p>Solo se agrega. Un movimiento equivocado se corrige con otro movimiento, nunca editando el
 * anterior: V39 no le concede {@code UPDATE} a {@code kamayuk_app} y el escáner del código fuente
 * lo vigila además en {@code TABLAS_INMUTABLES}.
 *
 * @param id nulo mientras no se ha guardado
 * @param liquidacionId a qué liquidación pertenece
 * @param tipo si abre la liquidación o le cambia el estado
 * @param estado en qué estado la deja
 * @param fecha el día del acto, no el de su registro
 * @param motivo por qué se mueve, en el vocabulario de quien opera
 * @param usuarioRegistro quién lo registró; nulo mientras no se ha guardado
 * @param observacion por qué se registra (regla 10)
 */
public record MovimientoDeLiquidacion(
        @Nullable Long id,
        long liquidacionId,
        TipoDeMovimientoDeLiquidacion tipo,
        EstadoDeLiquidacion estado,
        LocalDate fecha,
        String motivo,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    private static final int MOTIVO_MAXIMO = 300;

    public MovimientoDeLiquidacion {
        if (liquidacionId <= 0) {
            throw new IllegalArgumentException("El movimiento necesita su liquidacion");
        }
        Objects.requireNonNull(tipo, "El movimiento necesita su tipo");
        Objects.requireNonNull(estado, "El movimiento dice en que estado deja la liquidacion");
        if (tipo == TipoDeMovimientoDeLiquidacion.APERTURA
                && estado != EstadoDeLiquidacion.ABIERTA) {
            throw new IllegalArgumentException(
                    "La apertura solo abre en ABIERTA: es el estado con el que nace una"
                            + " liquidacion");
        }
        Objects.requireNonNull(fecha, "El movimiento necesita su fecha");
        Objects.requireNonNull(motivo, "El movimiento necesita su motivo");
        motivo = motivo.strip();
        if (motivo.isEmpty() || motivo.length() > MOTIVO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El motivo va de 1 a " + MOTIVO_MAXIMO + " caracteres");
        }
        Objects.requireNonNull(
                observacion, "Sin observacion no se guarda un movimiento (regla 10)");
    }

    /** La apertura de una liquidación recién emitida. */
    public static MovimientoDeLiquidacion apertura(
            long liquidacionId, LocalDate fecha, String motivo, Observacion observacion) {
        return new MovimientoDeLiquidacion(
                null,
                liquidacionId,
                TipoDeMovimientoDeLiquidacion.APERTURA,
                EstadoDeLiquidacion.ABIERTA,
                fecha,
                motivo,
                null,
                observacion);
    }

    /** Un cambio de estado. */
    public static MovimientoDeLiquidacion cambioDeEstado(
            long liquidacionId,
            EstadoDeLiquidacion nuevo,
            LocalDate fecha,
            String motivo,
            Observacion observacion) {
        return new MovimientoDeLiquidacion(
                null,
                liquidacionId,
                TipoDeMovimientoDeLiquidacion.ESTADO,
                nuevo,
                fecha,
                motivo,
                null,
                observacion);
    }
}
