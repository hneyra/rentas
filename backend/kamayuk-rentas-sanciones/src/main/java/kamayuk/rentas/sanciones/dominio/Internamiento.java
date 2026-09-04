package kamayuk.rentas.sanciones.dominio;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * El ingreso de un vehículo al depósito municipal (#50, RF-064, V41 §5).
 *
 * <h2>La salida no está aquí</h2>
 *
 * <p>V4 le había puesto {@code fecha_salida}. V41 se la retira: liberar un vehículo no es rellenar
 * una fecha en la fila del ingreso, es un acto con su propia fecha, su acta, quién retira, con qué
 * documento y con qué recibo pagó la custodia. Todo eso vive en {@link MovimientoDeInternamiento},
 * y el {@link EstadoDeInternamiento} se deriva de ahí.
 *
 * <h2>La tarifa de la custodia tampoco</h2>
 *
 * <p>{@link #tasaCustodia} es el <b>código</b> del concepto del TUPA, no su importe. La tarifa vive
 * en {@code tasa} con su vigencia (regla 5, ADR-0007); copiarla aquí la pondría en dos sitios y el
 * día que la ordenanza la cambie, uno de los dos mentiría.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param papeletaId la papeleta que dispuso la medida preventiva, si la hubo
 * @param vehiculoId el vehículo del padrón, si está registrado
 * @param placa la placa del vehículo internado
 * @param deposito dónde quedó
 * @param fechaIngreso cuándo entró
 * @param acta el número del acta emitida; es el del documento
 * @param documentoId la fila de {@code documento_emitido} que la dibujó
 * @param tasaCustodia el código del concepto del TUPA con que se cobra la custodia diaria
 * @param registradoEn cuándo se registró
 * @param usuarioRegistro quién lo registró; nulo mientras no se ha guardado
 * @param observacion por qué se interna (regla 10, RNF-052)
 */
public record Internamiento(
        @Nullable Long id,
        @Nullable Long papeletaId,
        @Nullable Long vehiculoId,
        String placa,
        String deposito,
        Instant fechaIngreso,
        String acta,
        long documentoId,
        String tasaCustodia,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** {@code internamiento.placa varchar(10)}. */
    public static final int PLACA_MAXIMA = 10;

    /** {@code internamiento.deposito varchar(160)}. */
    public static final int DEPOSITO_MAXIMO = 160;

    /** {@code internamiento.acta varchar(40)} tras V41. */
    public static final int ACTA_MAXIMA = 40;

    /** {@code internamiento.tasa_custodia varchar(20)}. */
    public static final int CODIGO_DE_TASA_MAXIMO = 20;

    public Internamiento {
        Objects.requireNonNull(placa, "Un internamiento es de un vehiculo con placa");
        placa = placa.strip().toUpperCase(Locale.ROOT);
        if (placa.isEmpty() || placa.length() > PLACA_MAXIMA) {
            throw new IllegalArgumentException(
                    "La placa va de 1 a " + PLACA_MAXIMA + " caracteres: '" + placa + "'");
        }
        Objects.requireNonNull(deposito, "El internamiento dice donde quedo el vehiculo");
        deposito = deposito.strip();
        if (deposito.isEmpty() || deposito.length() > DEPOSITO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El deposito va de 1 a " + DEPOSITO_MAXIMO + " caracteres");
        }
        Objects.requireNonNull(fechaIngreso, "El internamiento necesita su fecha de ingreso");
        Objects.requireNonNull(acta, "Un vehiculo internado sin acta es un vehiculo retenido");
        acta = acta.strip().toUpperCase(Locale.ROOT);
        if (acta.isEmpty() || acta.length() > ACTA_MAXIMA) {
            throw new IllegalArgumentException(
                    "El acta va de 1 a " + ACTA_MAXIMA + " caracteres: '" + acta + "'");
        }
        if (documentoId <= 0) {
            throw new IllegalArgumentException(
                    "El acta de internamiento es un documento emitido: sin el no hay papel que"
                            + " entregar al conductor ni que reimprimir (RF-132)");
        }
        Objects.requireNonNull(
                tasaCustodia,
                "El internamiento dice con que concepto del TUPA se cobra la custodia");
        tasaCustodia = tasaCustodia.strip().toUpperCase(Locale.ROOT);
        if (tasaCustodia.isEmpty() || tasaCustodia.length() > CODIGO_DE_TASA_MAXIMO) {
            throw new IllegalArgumentException(
                    "El codigo de la tasa de custodia va de 1 a "
                            + CODIGO_DE_TASA_MAXIMO
                            + " caracteres");
        }
        Objects.requireNonNull(registradoEn, "El internamiento dice cuando se registro");
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
    }

    /** Un ingreso al depósito todavía sin guardar. */
    public static Internamiento nuevo(
            @Nullable Long papeletaId,
            @Nullable Long vehiculoId,
            String placa,
            String deposito,
            Instant fechaIngreso,
            String acta,
            long documentoId,
            String tasaCustodia,
            Instant registradoEn,
            Observacion observacion) {
        return new Internamiento(
                null,
                papeletaId,
                vehiculoId,
                placa,
                deposito,
                fechaIngreso,
                acta,
                documentoId,
                tasaCustodia,
                registradoEn,
                null,
                observacion);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        return Objects.requireNonNull(id, "El internamiento todavia no se ha guardado");
    }
}
