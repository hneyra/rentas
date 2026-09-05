package kamayuk.rentas.licencias.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Lo que le paso a un FUE, con la resolucion que lo sustenta (#48, V43 §7).
 *
 * <p><b>Solo se agrega.</b> V43 le concede a {@code kamayuk_app} nada mas que {@code SELECT} e
 * {@code INSERT} sobre {@code edificacion_movimiento}, y el escaner de fuentes rechaza un {@code
 * UPDATE edificacion_movimiento SET} antes de que llegue a ejecutarse.
 *
 * <p>El <b>numero de la licencia</b> vive aqui y no en la cabecera, y es la decision de diseno de
 * este issue: un FUE existe antes de que haya licencia —se presenta, se completa por partes y
 * recien entonces se emite—, asi que un numero obligatorio desde el {@code INSERT} obligaria a
 * numerar expedientes que pueden no llegar a serlo nunca, como un anteproyecto en consulta.
 *
 * @param id nulo mientras no se haya guardado
 * @param fueId el expediente sobre el que se actua
 * @param tipo que le paso
 * @param fecha el dia del acto; entra como argumento, no del reloj (regla 6)
 * @param numeroLicencia el numero de la licencia municipal; solo en la {@code EMISION}
 * @param motivo por que se anula; obligatorio en la anulacion y ausente en las otras dos
 * @param reciboId el recibo de caja de tasas del derecho; en la emision y en la revalidacion
 * @param documentoId la fila de {@code documento_emitido} que lo materializa
 * @param documentoNumero su numero impreso, copiado en el mismo {@code INSERT}
 * @param registradoEn el instante de registro, del reloj inyectado
 * @param usuarioRegistro quien lo registro; sale del origen de la sesion, nunca de la peticion
 * @param observacion por que se registro (regla 10, RNF-052)
 */
public record MovimientoDeEdificacion(
        @Nullable Long id,
        long fueId,
        TipoDeMovimientoDeEdificacion tipo,
        LocalDate fecha,
        @Nullable String numeroLicencia,
        @Nullable String motivo,
        @Nullable Long reciboId,
        long documentoId,
        String documentoNumero,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    public MovimientoDeEdificacion {
        Objects.requireNonNull(tipo, "Un movimiento sin tipo no dice nada");
        Objects.requireNonNull(fecha, "El movimiento lleva la fecha del acto (regla 6)");
        Objects.requireNonNull(documentoNumero, "El movimiento copia el numero de su resolucion");
        Objects.requireNonNull(registradoEn, "El movimiento dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        numeroLicencia = vacioEsNulo(numeroLicencia);
        motivo = vacioEsNulo(motivo);

        boolean esEmision = tipo == TipoDeMovimientoDeEdificacion.EMISION;
        if (esEmision != (numeroLicencia != null)) {
            throw new IllegalArgumentException(
                    "Solo la emision numera la licencia: la revalidacion prorroga la misma y la"
                            + " anulacion la deja sin efecto, y ninguna de las dos vuelve a"
                            + " numerar");
        }
        boolean esAnulacion = tipo == TipoDeMovimientoDeEdificacion.ANULACION;
        if (esAnulacion != (motivo != null)) {
            throw new IllegalArgumentException(
                    "Una anulacion se motiva y una emision no: su motivo es la solicitud del"
                            + " administrado, que ya esta en el expediente");
        }
        boolean cobraDerecho = !esAnulacion;
        if (cobraDerecho != (reciboId != null)) {
            throw new IllegalArgumentException(
                    "La emision y la revalidacion se cobran en caja de tasas antes de dictarse"
                            + " (AC 5 de #48); la anulacion no, porque ninguna norma condiciona"
                            + " dejar sin efecto un acto a un pago");
        }
    }

    /** El movimiento que otorga la licencia. */
    public static MovimientoDeEdificacion emision(
            long fueId,
            LocalDate fecha,
            String numeroLicencia,
            long reciboId,
            long documentoId,
            String documentoNumero,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDeEdificacion(
                null,
                fueId,
                TipoDeMovimientoDeEdificacion.EMISION,
                fecha,
                numeroLicencia,
                null,
                reciboId,
                documentoId,
                documentoNumero,
                registradoEn,
                null,
                observacion);
    }

    /** El movimiento que prorroga el plazo. */
    public static MovimientoDeEdificacion revalidacion(
            long fueId,
            LocalDate fecha,
            long reciboId,
            long documentoId,
            String documentoNumero,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDeEdificacion(
                null,
                fueId,
                TipoDeMovimientoDeEdificacion.REVALIDACION,
                fecha,
                null,
                null,
                reciboId,
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

    private static @Nullable String vacioEsNulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
