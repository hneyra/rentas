package kamayuk.rentas.licencias.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Lo que le paso a una licencia, con la resolucion que lo sustenta (#44, V37 §5).
 *
 * <p><b>Solo se agrega.</b> V37 le concede a {@code sgtm_app} nada mas que {@code SELECT} e {@code
 * INSERT} sobre {@code licencia_movimiento}, y el escaner de fuentes rechaza un {@code UPDATE
 * licencia_movimiento SET} antes de que llegue a ejecutarse. Corregir un movimiento seria corregir
 * la historia de un acto administrativo ya notificado.
 *
 * <p>El documento va con las dos formas —su identificador y su numero impreso— escritas en el mismo
 * {@code INSERT} desde la misma emision. No pueden divergir, y tenerlas las dos es lo que permite
 * leer el historial sin cruzar tablas. Es el patron de {@code valor_movimiento} (V28) y de {@code
 * acto_coactivo} (V34).
 *
 * @param id nulo mientras no se haya guardado
 * @param licenciaId la licencia sobre la que se actua
 * @param tipo que le paso
 * @param fecha el dia del acto; entra como argumento, no del reloj (regla 6)
 * @param motivo por que se cancela; obligatorio en la cancelacion y ausente en la emision, tal como
 *     exige {@code licencia_movimiento_motivo_ck}
 * @param documentoId la fila de {@code documento_emitido} que lo materializa
 * @param documentoNumero su numero impreso, copiado
 * @param registradoEn el instante de registro, del reloj inyectado
 * @param usuarioRegistro quien lo registro; sale del origen de la sesion, nunca de la peticion
 * @param observacion por que se registro (regla 10, RNF-052)
 */
public record MovimientoDeLicencia(
        @Nullable Long id,
        long licenciaId,
        TipoDeMovimientoDeLicencia tipo,
        LocalDate fecha,
        @Nullable String motivo,
        long documentoId,
        String documentoNumero,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    public MovimientoDeLicencia {
        Objects.requireNonNull(tipo, "Un movimiento sin tipo no dice nada");
        Objects.requireNonNull(fecha, "El movimiento lleva la fecha del acto (regla 6)");
        Objects.requireNonNull(documentoNumero, "El movimiento copia el numero de su resolucion");
        Objects.requireNonNull(registradoEn, "El movimiento dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        if (motivo != null) {
            motivo = motivo.strip();
            if (motivo.isEmpty()) {
                motivo = null;
            }
        }
        boolean esCancelacion = tipo == TipoDeMovimientoDeLicencia.CANCELACION;
        if (esCancelacion != (motivo != null)) {
            throw new IllegalArgumentException(
                    "Una cancelacion se motiva y una emision no: su motivo es la solicitud del"
                            + " administrado, que ya esta en el expediente");
        }
    }

    /** El movimiento que nace con la licencia. */
    public static MovimientoDeLicencia emision(
            long licenciaId,
            LocalDate fecha,
            long documentoId,
            String documentoNumero,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDeLicencia(
                null,
                licenciaId,
                TipoDeMovimientoDeLicencia.EMISION,
                fecha,
                null,
                documentoId,
                documentoNumero,
                registradoEn,
                null,
                observacion);
    }

    /** El movimiento que la deja sin efecto. */
    public static MovimientoDeLicencia cancelacion(
            long licenciaId,
            LocalDate fecha,
            String motivo,
            long documentoId,
            String documentoNumero,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDeLicencia(
                null,
                licenciaId,
                TipoDeMovimientoDeLicencia.CANCELACION,
                fecha,
                motivo,
                documentoId,
                documentoNumero,
                registradoEn,
                null,
                observacion);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        Long guardado = id;
        if (guardado == null) {
            throw new IllegalStateException("El movimiento todavia no se ha guardado");
        }
        return guardado;
    }
}
