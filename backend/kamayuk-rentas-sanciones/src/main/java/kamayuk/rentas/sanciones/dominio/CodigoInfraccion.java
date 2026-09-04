package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import org.jspecify.annotations.Nullable;

/**
 * Un código del catálogo de infracciones —de tránsito o CUIS administrativas—, versionado por
 * vigencia (#43, RF-063, RF-072).
 *
 * <p>Las dos familias comparten esta misma tabla y esta misma forma: lo que las distingue es {@link
 * #familia()} y su base legal, no el modelo ("un solo modelo, dos familias").
 *
 * <p><b>Nunca se edita en el sitio.</b> Modificar un código cierra la versión vigente con {@link
 * #cerradoEl(LocalDate)} e inserta una versión nueva (regla 4): la anterior queda, con su {@code
 * vigenciaHasta}. Una papeleta se explica con el código vigente el día de la infracción, no con el
 * de hoy (regla 9).
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param familia tránsito o administrativa
 * @param codigo el código del catálogo, único junto con la familia y la fecha en que empieza a
 *     regir
 * @param descripcion el texto de la infracción
 * @param porcentajeUit el porcentaje de la UIT con que se calcula la multa; el valor de la UIT vive
 *     en {@code parametro_tributario} (D-02a), no aquí
 * @param medida la medida preventiva, cuando el manual la trae
 * @param puntos los puntos que descuenta, cuando aplica
 * @param baseLegal la norma que sustenta el código; sin ella no se guarda (criterio de aceptación)
 * @param vigenciaDesde desde cuándo rige esta versión
 * @param vigenciaHasta nulo mientras esta versión está vigente
 */
public record CodigoInfraccion(
        @Nullable Long id,
        Familia familia,
        String codigo,
        String descripcion,
        Alicuota porcentajeUit,
        @Nullable String medida,
        @Nullable Short puntos,
        String baseLegal,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta) {

    private static final int CODIGO_MAXIMO = 20;
    private static final int DESCRIPCION_MAXIMA = 500;
    private static final int MEDIDA_MAXIMA = 160;
    private static final int BASE_LEGAL_MAXIMA = 200;

    public CodigoInfraccion {
        Objects.requireNonNull(familia, "El código necesita su familia: tránsito o administrativa");
        Objects.requireNonNull(codigo, "El código de infracción es obligatorio");
        codigo = codigo.strip().toUpperCase(Locale.ROOT);
        if (codigo.isEmpty() || codigo.length() > CODIGO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El código va de 1 a " + CODIGO_MAXIMO + " caracteres: '" + codigo + "'");
        }
        Objects.requireNonNull(descripcion, "El código necesita su descripción");
        descripcion = descripcion.strip();
        if (descripcion.isEmpty() || descripcion.length() > DESCRIPCION_MAXIMA) {
            throw new IllegalArgumentException(
                    "La descripción va de 1 a " + DESCRIPCION_MAXIMA + " caracteres");
        }
        Objects.requireNonNull(porcentajeUit, "El código necesita su porcentaje de la UIT");
        if (medida != null) {
            medida = medida.strip();
            if (medida.isEmpty()) {
                medida = null;
            } else if (medida.length() > MEDIDA_MAXIMA) {
                throw new IllegalArgumentException(
                        "La medida preventiva excede " + MEDIDA_MAXIMA + " caracteres");
            }
        }
        if (puntos != null && puntos < 0) {
            throw new IllegalArgumentException("Los puntos no pueden ser negativos");
        }
        Objects.requireNonNull(
                baseLegal, "Un código sin base legal no se guarda (criterio de aceptación)");
        baseLegal = baseLegal.strip();
        if (baseLegal.isEmpty() || baseLegal.length() > BASE_LEGAL_MAXIMA) {
            throw new IllegalArgumentException(
                    "La base legal va de 1 a " + BASE_LEGAL_MAXIMA + " caracteres");
        }
        Objects.requireNonNull(vigenciaDesde, "El código necesita desde cuándo rige");
        if (vigenciaHasta != null && vigenciaHasta.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "Una versión no puede dejar de regir antes de empezar: "
                            + vigenciaDesde
                            + ".."
                            + vigenciaHasta);
        }
    }

    /** Una versión nueva, todavía sin guardar y sin cerrar. */
    public static CodigoInfraccion nuevo(
            Familia familia,
            String codigo,
            String descripcion,
            Alicuota porcentajeUit,
            @Nullable String medida,
            @Nullable Short puntos,
            String baseLegal,
            LocalDate vigenciaDesde) {
        return new CodigoInfraccion(
                null,
                familia,
                codigo,
                descripcion,
                porcentajeUit,
                medida,
                puntos,
                baseLegal,
                vigenciaDesde,
                null);
    }

    public boolean esNuevo() {
        return id == null;
    }

    public boolean estaVigente() {
        return vigenciaHasta == null;
    }

    /** Si esta versión rige en esa fecha. Los dos extremos entran (regla 9). */
    public boolean rigeEn(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Preguntar por la vigencia exige la fecha");
        if (fecha.isBefore(vigenciaDesde)) {
            return false;
        }
        return vigenciaHasta == null || !fecha.isAfter(vigenciaHasta);
    }

    /**
     * Cierra esta versión. Sus demás datos no se tocan: lo único que cambia es hasta cuándo rige.
     *
     * <p>Quien orquesta un cambio de código (#43, {@code MantenerCatalogoDeInfracciones}) llama a
     * esto con el día anterior a cuando empieza a regir la versión nueva, para que las dos nunca se
     * crucen.
     */
    public CodigoInfraccion cerradoEl(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Cerrar una versión exige la fecha");
        if (!estaVigente()) {
            throw new IllegalStateException("Esta versión ya se cerró el " + vigenciaHasta);
        }
        if (fecha.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "No se puede cerrar el "
                            + fecha
                            + " una versión que empezó a regir el "
                            + vigenciaDesde);
        }
        return new CodigoInfraccion(
                id,
                familia,
                codigo,
                descripcion,
                porcentajeUit,
                medida,
                puntos,
                baseLegal,
                vigenciaDesde,
                fecha);
    }
}
