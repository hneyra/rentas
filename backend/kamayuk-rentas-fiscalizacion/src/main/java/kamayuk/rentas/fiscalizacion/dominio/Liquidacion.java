package kamayuk.rentas.fiscalizacion.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * La liquidación de un proceso de fiscalización: el consolidado de lo hallado frente a lo declarado
 * para un acta y un periodo (#49, RF-053).
 *
 * <h2>El conjunto sellado lo fija cada línea, y ese es el AC 1</h2>
 *
 * <p>{@link LineaDeLiquidacion#conjuntoId} es el conjunto de parámetros <b>sellado</b> del
 * ejercicio de esa línea, copiado en el momento de emitir. Todo recálculo lo recupera por ese
 * identificador y nunca por ejercicio: resolver «el vigente del ejercicio» devolvería otra versión
 * el día que se selle una nueva, y la liquidación ya emitida cambiaría de cifra sin que nada
 * fallara —que es exactamente lo que el AC prohíbe—. Es el defecto que ARQ-09 §3 nombra.
 *
 * <p>Va en la línea y no aquí porque una fiscalización abarca un <b>periodo</b>: los parámetros de
 * 2022 no son los de 2026, y un conjunto único en la cabecera liquidaría 2022 con las cifras de
 * 2026.
 *
 * <h2>Una reliquidación no pisa a la anterior, y ese es el AC 2</h2>
 *
 * <p>{@link #reliquidadaPor} devuelve <b>otra</b> liquidación, con la versión siguiente y con
 * {@code liquidacionAnteriorId} apuntando a esta. Esta no cambia: {@code liquidacion_fiscalizacion}
 * no admite {@code UPDATE} desde V39. Es el precedente de {@code ficha_catastral} (#18) y de {@code
 * acta_fiscalizacion} (#45); qué cambió entre las dos lo explica {@link
 * DiferenciaEntreLiquidaciones}.
 *
 * <h2>Ni un importe</h2>
 *
 * <p>Esta cabecera no tiene ninguna cifra, y no es un olvido: el total de la liquidación es la suma
 * de sus líneas, y las líneas esperan a D-02a (#198). Guardar aquí un total sería guardar una cifra
 * que no se puede desglosar, y que además tendría que actualizarse cuando el detalle cambie —lo que
 * exigiría el {@code UPDATE} que la tabla no admite—.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param numero el «Nº Liquidación» impreso
 * @param ejercicio el ejercicio de la numeración, que es el de la emisión
 * @param correlativo el correlativo desnudo dentro de ese ejercicio
 * @param actaId el acta de fiscalización que la origina
 * @param version la liquidación número N de ese acta; la 1 es la original
 * @param liquidacionAnteriorId la liquidación que esta reliquida; nulo solo en la versión 1
 * @param ejercicioDesde primer ejercicio del periodo fiscalizado
 * @param ejercicioHasta último ejercicio del periodo fiscalizado
 * @param tipo cómo se determinó lo hallado
 * @param motivoDeterminante por qué se fiscalizó, en el vocabulario del expediente
 * @param fecha el día de la liquidación, no el de su registro
 * @param numeroNotificacion el «Nº Notificación», cuando ya se notificó
 * @param usuarioRegistro quién la registró; nulo mientras no se ha guardado
 * @param observacion por qué se registra (regla 10)
 */
public record Liquidacion(
        @Nullable Long id,
        String numero,
        Ejercicio ejercicio,
        long correlativo,
        long actaId,
        int version,
        @Nullable Long liquidacionAnteriorId,
        Ejercicio ejercicioDesde,
        Ejercicio ejercicioHasta,
        TipoDeFiscalizacion tipo,
        String motivoDeterminante,
        LocalDate fecha,
        @Nullable String numeroNotificacion,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    private static final int NUMERO_MAXIMO = 40;
    private static final int MOTIVO_MAXIMO = 1000;

    public Liquidacion {
        Objects.requireNonNull(numero, "La liquidacion necesita su numero");
        numero = numero.strip().toUpperCase(Locale.ROOT);
        if (numero.isEmpty() || numero.length() > NUMERO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero va de 1 a " + NUMERO_MAXIMO + " caracteres: '" + numero + "'");
        }
        Objects.requireNonNull(ejercicio, "La liquidacion necesita el ejercicio de su numeracion");
        if (correlativo <= 0) {
            throw new IllegalArgumentException("El correlativo empieza en 1: " + correlativo);
        }
        if (actaId <= 0) {
            throw new IllegalArgumentException("La liquidacion necesita el acta que la origina");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("La version de una liquidacion empieza en 1");
        }
        if ((version == 1) != (liquidacionAnteriorId == null)) {
            throw new IllegalArgumentException(
                    "La version 1 no sustituye a ninguna, y cualquier otra tiene que decir a cual"
                            + " sustituye: sin eso el historico no puede encadenar el proceso");
        }
        Objects.requireNonNull(ejercicioDesde, "El periodo fiscalizado necesita su inicio");
        Objects.requireNonNull(ejercicioHasta, "El periodo fiscalizado necesita su fin");
        if (ejercicioHasta.compareTo(ejercicioDesde) < 0) {
            throw new IllegalArgumentException(
                    "El periodo fiscalizado va de "
                            + ejercicioDesde
                            + " a "
                            + ejercicioHasta
                            + ", que esta al reves");
        }
        Objects.requireNonNull(tipo, "La liquidacion necesita su tipo de fiscalizacion");
        Objects.requireNonNull(motivoDeterminante, "La liquidacion necesita su motivo");
        motivoDeterminante = motivoDeterminante.strip();
        if (motivoDeterminante.isEmpty() || motivoDeterminante.length() > MOTIVO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El motivo determinante va de 1 a " + MOTIVO_MAXIMO + " caracteres");
        }
        Objects.requireNonNull(fecha, "La liquidacion necesita su fecha");
        if (numeroNotificacion != null) {
            numeroNotificacion = numeroNotificacion.strip().toUpperCase(Locale.ROOT);
            if (numeroNotificacion.isEmpty() || numeroNotificacion.length() > NUMERO_MAXIMO) {
                throw new IllegalArgumentException(
                        "El numero de notificacion va de 1 a " + NUMERO_MAXIMO + " caracteres");
            }
        }
        Objects.requireNonNull(
                observacion, "Sin observacion no se guarda una liquidacion (regla 10)");
    }

    /** La primera liquidación de un acta, todavía sin guardar. */
    public static Liquidacion primera(
            String numero,
            Ejercicio ejercicio,
            long correlativo,
            long actaId,
            Ejercicio ejercicioDesde,
            Ejercicio ejercicioHasta,
            TipoDeFiscalizacion tipo,
            String motivoDeterminante,
            LocalDate fecha,
            Observacion observacion) {
        return new Liquidacion(
                null,
                numero,
                ejercicio,
                correlativo,
                actaId,
                1,
                null,
                ejercicioDesde,
                ejercicioHasta,
                tipo,
                motivoDeterminante,
                fecha,
                null,
                null,
                observacion);
    }

    /**
     * La reliquidación que sustituye a esta: <b>otra</b> fila, con la versión siguiente y con esta
     * como anterior (AC 2).
     *
     * <p>Esta liquidación no cambia. Ni siquiera se marca como sustituida: la sustitución se lee de
     * que exista otra versión que la referencia, y esa lectura no puede desincronizarse con una
     * columna que alguien tendría que actualizar —y que la tabla no deja actualizar—.
     *
     * <p><b>Las líneas heredan el conjunto sellado de la versión anterior, y eso es deliberado.</b>
     * Una reliquidación corrige el contraste —un área mal medida, un ejercicio de más—, no el marco
     * normativo con el que se liquidó: resolver el conjunto de nuevo mezclaría dos correcciones en
     * una y haría imposible explicarle al contribuyente por qué cambió su deuda. Quien orquesta esa
     * herencia es {@code ReliquidarFiscalizacion}; este método construye la cabecera.
     */
    public Liquidacion reliquidadaPor(
            String nuevoNumero,
            Ejercicio ejercicioDeLaNumeracion,
            long nuevoCorrelativo,
            Ejercicio nuevoDesde,
            Ejercicio nuevoHasta,
            TipoDeFiscalizacion nuevoTipo,
            String nuevoMotivo,
            LocalDate nuevaFecha,
            Observacion nuevaObservacion) {
        Long propio = Objects.requireNonNull(id, "Solo se reliquida una liquidacion ya guardada");
        return new Liquidacion(
                null,
                nuevoNumero,
                ejercicioDeLaNumeracion,
                nuevoCorrelativo,
                actaId,
                version + 1,
                propio,
                nuevoDesde,
                nuevoHasta,
                nuevoTipo,
                nuevoMotivo,
                nuevaFecha,
                null,
                null,
                nuevaObservacion);
    }

    public boolean esNueva() {
        return id == null;
    }

    /** Si es una corrección de otra. */
    public boolean esReliquidacion() {
        return liquidacionAnteriorId != null;
    }

    /** El identificador ya asignado. Falla si todavía no se guardó. */
    public long identificador() {
        return Objects.requireNonNull(id, "La liquidacion todavia no se ha guardado");
    }
}
