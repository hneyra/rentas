package kamayuk.rentas.fiscalizacion.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * La selección de qué predios o vehículos entran a un proceso de fiscalización, con su fiscalizador
 * y su plazo (RF-050).
 *
 * <p><b>Reprogramar no borra el programa anterior</b> (AC de #45): no hay método que lo module ni
 * lo cierre. Un programa nuevo es siempre una fila nueva, con su propio código; el anterior queda
 * intacto, se haya usado o no.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param codigo identifica el programa, único por municipalidad
 * @param descripcion qué se fiscaliza y por qué
 * @param tipo si selecciona predios o vehículos
 * @param fechaInicio desde cuándo corre
 * @param fechaFin hasta cuándo, si ya se fijó
 * @param estado en qué punto está
 * @param ejercicio qué ejercicio examina; nulo en los programas anteriores a {@code V60}
 * @param sectorCodigo sobre qué sector del padrón se sortea, o nulo para todo el distrito
 * @param criterio qué condición busca; nulo en los programas anteriores a {@code V60}
 * @param fiscalizador a quién está asignado; es de donde el acta toma el suyo
 */
public record ProgramaFiscalizacion(
        @Nullable Long id,
        String codigo,
        String descripcion,
        TipoDePrograma tipo,
        LocalDate fechaInicio,
        @Nullable LocalDate fechaFin,
        EstadoDePrograma estado,
        @Nullable Ejercicio ejercicio,
        @Nullable String sectorCodigo,
        @Nullable CondicionFiscalizada criterio,
        @Nullable String fiscalizador) {

    private static final int CODIGO_MAXIMO = 20;
    private static final int DESCRIPCION_MAXIMA = 300;
    private static final int FISCALIZADOR_MAXIMO = 60;

    public ProgramaFiscalizacion {
        Objects.requireNonNull(codigo, "El programa de fiscalizacion necesita su codigo");
        codigo = codigo.strip().toUpperCase(Locale.ROOT);
        if (codigo.isEmpty() || codigo.length() > CODIGO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El codigo va de 1 a " + CODIGO_MAXIMO + " caracteres: '" + codigo + "'");
        }
        Objects.requireNonNull(descripcion, "El programa de fiscalizacion necesita su descripcion");
        descripcion = descripcion.strip();
        if (descripcion.isEmpty() || descripcion.length() > DESCRIPCION_MAXIMA) {
            throw new IllegalArgumentException(
                    "La descripcion va de 1 a " + DESCRIPCION_MAXIMA + " caracteres");
        }
        Objects.requireNonNull(tipo, "El programa de fiscalizacion necesita su tipo");
        Objects.requireNonNull(
                fechaInicio, "El programa de fiscalizacion necesita su fecha de inicio");
        if (fechaFin != null && fechaFin.isBefore(fechaInicio)) {
            throw new IllegalArgumentException(
                    "La fecha de fin no puede ser anterior a la fecha de inicio");
        }
        Objects.requireNonNull(estado, "El programa de fiscalizacion necesita su estado");
        sectorCodigo = enBlancoEsNulo(sectorCodigo);
        fiscalizador = enBlancoEsNulo(fiscalizador);
        if (fiscalizador != null && fiscalizador.length() > FISCALIZADOR_MAXIMO) {
            throw new IllegalArgumentException(
                    "El fiscalizador va hasta " + FISCALIZADOR_MAXIMO + " caracteres");
        }
    }

    /**
     * Un programa <b>anterior a {@code V60}</b>, sin los cuatro parámetros de su muestra.
     *
     * <p>Existe porque la columna los admite nulos: no se puede afirmar que {@code
     * programa_fiscalizacion} esté vacía en ningún ambiente, y un programa así es exactamente lo
     * que hay en la base. No puede generar muestra —{@link #parametrosDeLaMuestra()} dice cuál le
     * falta—, y eso es lo único honesto que se puede hacer con él.
     */
    public ProgramaFiscalizacion(
            @Nullable Long id,
            String codigo,
            String descripcion,
            TipoDePrograma tipo,
            LocalDate fechaInicio,
            @Nullable LocalDate fechaFin,
            EstadoDePrograma estado) {
        this(id, codigo, descripcion, tipo, fechaInicio, fechaFin, estado, null, null, null, null);
    }

    /**
     * Un programa nuevo <b>sin los parámetros de su muestra</b>: la forma que existía antes de
     * {@code V60}. No puede sortear nada, y {@link #parametrosDeLaMuestra()} dice qué le falta.
     */
    public static ProgramaFiscalizacion nuevo(
            String codigo,
            String descripcion,
            TipoDePrograma tipo,
            LocalDate fechaInicio,
            @Nullable LocalDate fechaFin) {
        return nuevo(codigo, descripcion, tipo, fechaInicio, fechaFin, null, null, null, null);
    }

    /** Un programa nuevo, siempre {@code ABIERTO}. */
    public static ProgramaFiscalizacion nuevo(
            String codigo,
            String descripcion,
            TipoDePrograma tipo,
            LocalDate fechaInicio,
            @Nullable LocalDate fechaFin,
            @Nullable Ejercicio ejercicio,
            @Nullable String sectorCodigo,
            @Nullable CondicionFiscalizada criterio,
            @Nullable String fiscalizador) {
        return new ProgramaFiscalizacion(
                null,
                codigo,
                descripcion,
                tipo,
                fechaInicio,
                fechaFin,
                EstadoDePrograma.ABIERTO,
                ejercicio,
                sectorCodigo,
                criterio,
                fiscalizador);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /**
     * Está abierto para la exclusión de #481: un predio que un programa {@code ABIERTO} o {@code
     * EN_PROCESO} ya se llevó no vuelve a sortearse. Un programa {@code CERRADO} de 2021 no puede
     * bloquear el padrón para siempre.
     */
    public boolean admiteVisitas() {
        return estado == EstadoDePrograma.ABIERTO || estado == EstadoDePrograma.EN_PROCESO;
    }

    /**
     * El nombre del parámetro que le falta para poder sortear su muestra, o vacío si los tiene.
     *
     * <p>{@code sectorCodigo} no está: su nulo significa «todo el distrito», que es una respuesta y
     * no una falta.
     */
    public java.util.Optional<String> parametrosDeLaMuestra() {
        if (ejercicio == null) {
            return java.util.Optional.of("ejercicio");
        }
        if (criterio == null) {
            return java.util.Optional.of("criterio");
        }
        if (fiscalizador == null) {
            return java.util.Optional.of("fiscalizador");
        }
        return java.util.Optional.empty();
    }

    private static @Nullable String enBlancoEsNulo(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
