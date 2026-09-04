package kamayuk.rentas.sanciones.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Lo que le pasa a un vehículo internado: su liberación o su declaración de abandono (#50, RF-064,
 * V41 §5).
 *
 * <h2>El recibo de la custodia viaja por su número impreso, no por su identificador</h2>
 *
 * <p>El recibo vive en {@code tesoreria}, y una clave foránea a su tabla cruzaría la frontera del
 * módulo (ARQ-01 §4 regla 2). Lo que se guarda es el número tal como está en el papel —{@code
 * 001-0000123}—, que es además lo que el administrado tiene en la mano. Que ese recibo exista, esté
 * vigente y cobre de verdad la tasa de custodia lo comprueba {@code LiberarVehiculoInternado}
 * preguntándole a {@code tesoreria} por su API pública, que es lo que el AC de #50 exige.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param internamientoId el ingreso sobre el que se actúa
 * @param tipo liberación o abandono
 * @param fecha el día del acto
 * @param acta el número del acta emitida; es el del documento
 * @param documentoId la fila de {@code documento_emitido} que la dibujó
 * @param reciboCustodia el recibo con que se pagó la custodia, como está impreso; obligatorio en la
 *     liberación
 * @param diasCustodia cuántos días estuvo en el depósito; obligatorio en la liberación
 * @param personaRetira quién retira el vehículo; obligatorio en la liberación
 * @param documentoRetira su documento de identidad; obligatorio en la liberación
 * @param soatAcreditado si se acreditó el SOAT vigente
 * @param registradoEn cuándo se registró
 * @param usuarioRegistro quién lo registró; nulo mientras no se ha guardado
 * @param observacion por qué se registra (regla 10, RNF-052)
 */
public record MovimientoDeInternamiento(
        @Nullable Long id,
        long internamientoId,
        TipoDeMovimientoDeInternamiento tipo,
        LocalDate fecha,
        String acta,
        long documentoId,
        @Nullable String reciboCustodia,
        @Nullable Integer diasCustodia,
        @Nullable String personaRetira,
        @Nullable String documentoRetira,
        boolean soatAcreditado,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** {@code internamiento_movimiento.recibo_custodia varchar(20)}. */
    public static final int RECIBO_MAXIMO = 20;

    /** {@code internamiento_movimiento.persona_retira varchar(120)}. */
    public static final int PERSONA_MAXIMA = 120;

    /** {@code internamiento_movimiento.documento_retira varchar(20)}. */
    public static final int DOCUMENTO_MAXIMO = 20;

    public MovimientoDeInternamiento {
        if (internamientoId <= 0) {
            throw new IllegalArgumentException("Un movimiento es de un internamiento concreto");
        }
        Objects.requireNonNull(tipo, "El movimiento necesita su tipo");
        Objects.requireNonNull(fecha, "El movimiento necesita su fecha");
        Objects.requireNonNull(acta, "El acto se materializa en un acta");
        acta = acta.strip().toUpperCase(Locale.ROOT);
        if (acta.isEmpty() || acta.length() > Internamiento.ACTA_MAXIMA) {
            throw new IllegalArgumentException(
                    "El acta va de 1 a "
                            + Internamiento.ACTA_MAXIMA
                            + " caracteres: '"
                            + acta
                            + "'");
        }
        if (documentoId <= 0) {
            throw new IllegalArgumentException(
                    "El acta del movimiento es un documento emitido: sin el no hay papel que"
                            + " entregar ni que reimprimir (RF-132)");
        }
        reciboCustodia = recortado(reciboCustodia, RECIBO_MAXIMO, "El recibo de la custodia");
        personaRetira = recortado(personaRetira, PERSONA_MAXIMA, "Quien retira el vehiculo");
        documentoRetira =
                recortado(documentoRetira, DOCUMENTO_MAXIMO, "El documento de quien retira");
        if (diasCustodia != null && diasCustodia < 0) {
            throw new IllegalArgumentException(
                    "Los dias de custodia no pueden ser negativos: " + diasCustodia);
        }

        // La misma condicion que internamiento_liberacion_ck (V41), aqui para que falle al
        // construir el objeto y no al llegar a la base.
        boolean conCustodia =
                reciboCustodia != null
                        && personaRetira != null
                        && documentoRetira != null
                        && diasCustodia != null;
        if (tipo.exigeCustodiaPagada() && !conCustodia) {
            throw new IllegalArgumentException(
                    "Un vehiculo no sale del deposito sin el recibo de la custodia, sin los dias"
                            + " que estuvo, sin quien lo retira y sin su documento (AC de #50)");
        }
        Objects.requireNonNull(registradoEn, "El movimiento dice cuando se registro");
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
    }

    /** La liberación de un vehículo, todavía sin guardar. */
    public static MovimientoDeInternamiento liberacion(
            long internamientoId,
            LocalDate fecha,
            String acta,
            long documentoId,
            String reciboCustodia,
            int diasCustodia,
            String personaRetira,
            String documentoRetira,
            boolean soatAcreditado,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDeInternamiento(
                null,
                internamientoId,
                TipoDeMovimientoDeInternamiento.LIBERACION,
                fecha,
                acta,
                documentoId,
                reciboCustodia,
                diasCustodia,
                personaRetira,
                documentoRetira,
                soatAcreditado,
                registradoEn,
                null,
                observacion);
    }

    /** La declaración de abandono, todavía sin guardar. */
    public static MovimientoDeInternamiento abandono(
            long internamientoId,
            LocalDate fecha,
            String acta,
            long documentoId,
            int diasCustodia,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDeInternamiento(
                null,
                internamientoId,
                TipoDeMovimientoDeInternamiento.ABANDONO,
                fecha,
                acta,
                documentoId,
                null,
                diasCustodia,
                null,
                null,
                false,
                registradoEn,
                null,
                observacion);
    }

    public boolean esNuevo() {
        return id == null;
    }

    private static @Nullable String recortado(@Nullable String valor, int maximo, String que) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        if (limpio.isEmpty()) {
            return null;
        }
        if (limpio.length() > maximo) {
            throw new IllegalArgumentException(que + " excede " + maximo + " caracteres");
        }
        return limpio;
    }
}
