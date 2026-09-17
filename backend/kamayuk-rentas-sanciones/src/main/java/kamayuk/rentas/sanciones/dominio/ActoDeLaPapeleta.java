package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;

/**
 * Un documento emitido por una papeleta, con su fecha y sus acuses (#50, RF-065, AC 4).
 *
 * <p>Es lo que la pantalla {@code transito_documentos} lista: «registra los documentos emitidos por
 * papeleta y conserva la secuencia del trámite». La secuencia es una sola aunque los documentos
 * salgan de dos sitios —{@code resolucion_gerencia} e {@code internamiento_movimiento}—, y por eso
 * esta proyección los uniforma: quien lee el expediente de una papeleta necesita <b>todos</b> los
 * papeles en orden, no dos listas que hay que intercalar a mano.
 *
 * <p>El acta de <b>ingreso</b> al depósito también entra: es un documento emitido por la papeleta
 * que dispuso la medida preventiva. Dejarla fuera haría que el listado no explicara de dónde salió
 * el vehículo que después se libera.
 *
 * @param clase de qué registro sale: {@code RESOLUCION_GERENCIA}, {@code ACTA_INTERNAMIENTO}
 * @param tipo qué documento es dentro de su clase
 * @param numero el número del documento emitido
 * @param fecha el día del acto
 * @param documentoId la fila de {@code documento_emitido} con que se reimprime (RF-132)
 * @param observacion por qué se emitió (regla 10)
 * @param acuses las diligencias de notificación, una fila por intento; vacía si no se notificó
 */
public record ActoDeLaPapeleta(
        String clase,
        String tipo,
        String numero,
        LocalDate fecha,
        long documentoId,
        Observacion observacion,
        List<AcuseDelActo> acuses) {

    /** Los actos que salen de {@code resolucion_gerencia}. */
    public static final String CLASE_RESOLUCION = "RESOLUCION_GERENCIA";

    /** Los actos que salen del internamiento y sus movimientos. */
    public static final String CLASE_INTERNAMIENTO = "ACTA_INTERNAMIENTO";

    /**
     * En qué punto de su notificación está, derivado de sus acuses (#185).
     *
     * <p>Se deriva y no se guarda, por lo mismo que en coactiva: ninguno de los registros de los
     * que salen estos actos admite {@code UPDATE}. Y no sustituye a {@link #acuses()}, que sigue
     * viajando entero: ver {@link EstadoDelActoDeLaPapeleta}.
     */
    public EstadoDelActoDeLaPapeleta estado() {
        return EstadoDelActoDeLaPapeleta.de(seNotifica(), acuses);
    }

    /**
     * Si este acto se notifica.
     *
     * <p>Las actas del depósito no: se entregan en mano al conductor o a quien retira, con su firma
     * en el papel. Por eso una lista de acuses vacía significa dos cosas distintas según la clase
     * —«todavía no se ha intentado» en una resolución, «no hay nada que intentar» en un acta—, y
     * decidirlo aquí es lo que evita que el estado diga que alguien tiene pendiente notificar un
     * acta de ingreso.
     */
    private boolean seNotifica() {
        return CLASE_RESOLUCION.equals(clase);
    }

    public ActoDeLaPapeleta {
        Objects.requireNonNull(clase, "El acto dice de que registro sale");
        Objects.requireNonNull(tipo, "El acto necesita su tipo");
        Objects.requireNonNull(numero, "El acto necesita el numero de su documento");
        Objects.requireNonNull(fecha, "El acto necesita su fecha");
        Objects.requireNonNull(observacion, "Todo acto dice por que se emitio (regla 10)");
        Objects.requireNonNull(acuses, "La lista es vacia, no nula");
        acuses = List.copyOf(acuses);
    }
}
