package kamayuk.rentas.licencias.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Un duplicado autorizado de una licencia (#44, RF-111).
 *
 * <h2>El duplicado NO es una licencia nueva</h2>
 *
 * <p>Es el criterio del AC de #44: «un duplicado conserva el numero original y se identifica como
 * duplicado». Por eso no hay aqui ni numero de licencia ni titular ni giros: esta fila registra el
 * <b>acto</b> de autorizar la reimpresion —con su resolucion, su recibo y su motivo—, y el papel
 * que sale de ella es la misma licencia con la marca {@code DUPLICADO N.o k} que {@code
 * EmitirDocumento.reimprimir} le pone.
 *
 * <p>{@link #reimpresion} copia el contador de {@code documento_emitido} en el momento del acto, y
 * es la misma tecnica que {@code valor_movimiento} usa con la exigibilidad (V28 §2): dos anios
 * despues, la fila tiene que explicar de que duplicado hablaba sin releer un contador que ya
 * avanzo.
 *
 * @param id nulo mientras no se haya guardado
 * @param licenciaId la licencia de la que se saca
 * @param numero el ordinal del duplicado dentro de esa licencia, desde 1
 * @param fecha el dia en que se autoriza
 * @param motivo por que se pide (extravio, deterioro, robo)
 * @param reciboId el recibo del derecho de tramite del duplicado
 * @param documentoId la resolucion que lo autoriza
 * @param reimpresion cuantas reimpresiones llevaba la licencia al autorizarlo
 * @param registradoEn el instante del registro
 * @param usuarioRegistro quien lo registro
 * @param observacion por que se registro (regla 10)
 */
public record DuplicadoDeLicencia(
        @Nullable Long id,
        long licenciaId,
        int numero,
        LocalDate fecha,
        String motivo,
        long reciboId,
        long documentoId,
        int reimpresion,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    public DuplicadoDeLicencia {
        Objects.requireNonNull(fecha, "El duplicado lleva la fecha del acto (regla 6)");
        Objects.requireNonNull(motivo, "Un duplicado se motiva: extravio, deterioro, robo");
        Objects.requireNonNull(registradoEn, "El duplicado dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        motivo = motivo.strip();
        if (motivo.isEmpty()) {
            throw new IllegalArgumentException("El motivo del duplicado no puede estar vacio");
        }
        if (numero < 1) {
            throw new IllegalArgumentException("El primer duplicado es el 1, no el " + numero);
        }
        if (reimpresion < 1) {
            throw new IllegalArgumentException(
                    "La primera reimpresion es la 1: el original es la emision, no un duplicado");
        }
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        Long guardado = id;
        if (guardado == null) {
            throw new IllegalStateException("El duplicado todavia no se ha guardado");
        }
        return guardado;
    }
}
