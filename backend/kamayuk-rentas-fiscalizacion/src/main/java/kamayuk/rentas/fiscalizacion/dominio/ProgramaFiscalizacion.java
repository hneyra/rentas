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
 * <p><b>Reprogramar no borra el programa anterior</b> (AC de #45): no hay método que lo module. Un
 * programa nuevo es siempre una fila nueva, con su propio código; el anterior queda intacto, se
 * haya usado o no.
 *
 * <p><b>Pero un programa sí termina</b> (#341): {@link #cerrado()} es su única transición. Hasta
 * #341 esta misma frase decía «ni lo cierre», y se leía como si reprogramar y cerrar fueran lo
 * mismo: el programa nacía {@code ABIERTO}, nada movía su estado, y la exclusión de #481 —que sólo
 * aparta los predios de programas {@code ABIERTO} o {@code EN_PROCESO}— retenía para siempre a todo
 * predio sorteado una vez. Cerrar no edita lo que el programa sorteó ni con qué parámetros: mueve
 * el estado y nada más, y desde {@code V30} el privilegio no permite otra cosa.
 *
 * <p>{@code EN_PROCESO} no se escribe. Si algún día hace falta, se deriva de un hecho —«tiene acta
 * levantada»— y no se guarda, por lo mismo que {@code V19} decidió con el acta.
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

    /**
     * {@code programa_fiscalizacion.sector_codigo varchar(10)} (V1).
     *
     * <p>Hasta #422 el codigo y el fiscalizador tenian su tope y el sector no: un sector de once
     * caracteres llegaba al {@code INSERT} y el motor lo rechazaba con 22001, que salia como un 500
     * con incidencia. El tope es el ancho de la columna y no una regla de negocio: aqui se topa lo
     * que llega, no se ensancha la base.
     */
    public static final int SECTOR_MAXIMO = 10;

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
        if (sectorCodigo != null && sectorCodigo.length() > SECTOR_MAXIMO) {
            throw new IllegalArgumentException(
                    "El sector va hasta " + SECTOR_MAXIMO + " caracteres: '" + sectorCodigo + "'");
        }
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
     * EN_PROCESO} ya se llevó no vuelve a sortearse. Un programa {@code CERRADO} de 2021 no bloquea
     * el padrón para siempre — y desde #341 eso es cierto, porque {@link #cerrado()} existe y lo
     * publica {@code POST /fiscalizacion/programas/{id}/cierre}. Hasta entonces ningún programa
     * llegaba a {@code CERRADO} por la aplicación.
     */
    public boolean admiteVisitas() {
        return estado == EstadoDePrograma.ABIERTO || estado == EstadoDePrograma.EN_PROCESO;
    }

    /**
     * El mismo programa, {@code CERRADO} (#341): la única transición que este sistema escribe.
     *
     * <p>Es un acto de la administración y no la consecuencia de otro hecho —ninguna fila dice que
     * un programa terminó—, igual que la anulación del acta (#214) y la de la papeleta (#267). Por
     * eso lo lleva un caso de uso con su observación, su fecha y su auditoría, y no se deriva.
     *
     * <p>Pura (regla 6): no mira el reloj ni la base. La fecha del acto no es un dato del programa
     * —la columna no existe, y {@code fecha_fin} es el plazo que se programó, no el día en que se
     * cerró—, así que viaja a la auditoría.
     *
     * @throws TransicionIlegal si ya estaba cerrado: un programa cerrado no se reabre, y seguir
     *     fiscalizando es registrar otro
     */
    public ProgramaFiscalizacion cerrado() {
        if (estado == EstadoDePrograma.CERRADO) {
            throw new TransicionIlegal(id, codigo, estado, EstadoDePrograma.CERRADO);
        }
        return new ProgramaFiscalizacion(
                id,
                codigo,
                descripcion,
                tipo,
                fechaInicio,
                fechaFin,
                EstadoDePrograma.CERRADO,
                ejercicio,
                sectorCodigo,
                criterio,
                fiscalizador);
    }

    /**
     * Si el padrón que su muestra sortea es el de <b>predios</b> (#343): {@code tipo == PREDIAL}.
     *
     * <p>Hasta #343 esta regla no vivía en ningún sitio. El sorteo recorre la detección de omisos,
     * que es el cruce del padrón de predios contra las declaraciones juradas, y no miraba el tipo:
     * un programa {@code VEHICULAR} con criterio {@code OMISO} se llevaba los predios omisos del
     * distrito, ningún acta predial podía levantarse sobre ellos ({@code ProgramaDeOtroTipo}) y la
     * exclusión de #481 los apartaba de la muestra de todo programa predial mientras siguiera
     * abierto. No existe detección de vehículos, así que un programa vehicular no tiene padrón que
     * sortear; el día que exista será otro padrón y otra operación.
     *
     * <p>No está en {@link #parametrosDeLaMuestra()} a propósito: ese método nombra un parámetro
     * que <b>falta</b>, y el tipo no falta — el mensaje mentiría.
     */
    public boolean sorteaPredios() {
        return tipo == TipoDePrograma.PREDIAL;
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

    /**
     * El programa no admite esa transición (#341). Hoy sólo hay una —cerrar— y sólo la impide que
     * ya estuviera cerrado.
     */
    public static final class TransicionIlegal extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TransicionIlegal(
                @Nullable Long id, String codigo, EstadoDePrograma desde, EstadoDePrograma hasta) {
            super(
                    "El programa de fiscalizacion "
                            + codigo
                            + " ("
                            + id
                            + ") esta "
                            + desde
                            + " y no puede pasar a "
                            + hasta
                            + ": un programa cerrado no se reabre, y seguir fiscalizando es"
                            + " registrar otro programa");
        }
    }

    private static @Nullable String enBlancoEsNulo(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
