package kamayuk.rentas.sanciones.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * El escrito que el administrado presenta contra una papeleta (#50, RF-064, V41 §2).
 *
 * <h2>Solo se agrega: lo resuelto no vive aquí</h2>
 *
 * <p>V4 le había puesto {@code resultado}, {@code resolucion} y {@code fecha_resolucion}: el
 * resultado del descargo escrito <b>dentro del propio descargo</b>. V41 se las retira. Quien
 * resuelve es la gerencia, con una {@link ResolucionDeGerencia} que se emite, se numera, se
 * notifica y se lleva el administrado; guardar su sentido aquí dejaría dos sitios donde vive lo
 * resuelto, y el papel que el administrado tiene en la mano solo puede salir de uno.
 *
 * <h2>El plazo entra como dato y la fila lo copia</h2>
 *
 * <p>La pantalla dice «Dentro del plazo (5 días hábiles)». Ese cinco es una cifra normativa igual
 * que los siete días del art. 14.1 de la Ley 26979 que #41 parametrizó: sale del conjunto sellado
 * (regla 5) y lo que se guarda aquí es el <b>día resultante</b> —{@link #presentadoHasta}— junto
 * con el conjunto del que salió. Releerlo dentro de dos años daría otra fecha el día que el plazo
 * cambie (ARQ-09 §3).
 *
 * <p>Un recurso fuera de plazo <b>se registra igual</b>, con {@link #enPlazo} en falso: lo que
 * corresponde es declararlo improcedente, y para eso hay que poder registrarlo. Lo que la base
 * impide ({@code descargo_plazo_ck}) es que la fila diga que llegó en plazo mientras sus propias
 * fechas dicen lo contrario.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param papeletaId la papeleta impugnada
 * @param numeroExpediente el número con que entra por mesa de partes
 * @param fecha el día de presentación
 * @param tipoRecurso qué recurso es
 * @param sustento el fundamento del administrado
 * @param presentadoHasta el último día en que era admisible, derivado del plazo parametrizado
 * @param conjuntoId de qué conjunto sellado salió ese plazo
 * @param enPlazo si llegó dentro; coherente con las dos fechas por {@code descargo_plazo_ck}
 * @param registradoEn cuándo se registró
 * @param usuarioRegistro quién lo registró; nulo mientras no se ha guardado
 * @param observacion por qué se registra (regla 10, RNF-052)
 */
public record Descargo(
        @Nullable Long id,
        long papeletaId,
        String numeroExpediente,
        LocalDate fecha,
        TipoDeRecurso tipoRecurso,
        String sustento,
        LocalDate presentadoHasta,
        long conjuntoId,
        boolean enPlazo,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** {@code descargo.numero_expediente varchar(20)}. */
    public static final int EXPEDIENTE_MAXIMO = 20;

    /** {@code descargo.sustento varchar(1000)}. */
    public static final int SUSTENTO_MAXIMO = 1000;

    public Descargo {
        if (papeletaId <= 0) {
            throw new IllegalArgumentException("Un descargo se presenta contra una papeleta");
        }
        Objects.requireNonNull(numeroExpediente, "El descargo necesita su numero de expediente");
        numeroExpediente = numeroExpediente.strip().toUpperCase(Locale.ROOT);
        if (numeroExpediente.isEmpty() || numeroExpediente.length() > EXPEDIENTE_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero de expediente va de 1 a "
                            + EXPEDIENTE_MAXIMO
                            + " caracteres: '"
                            + numeroExpediente
                            + "'");
        }
        Objects.requireNonNull(fecha, "El descargo necesita su fecha de presentacion");
        Objects.requireNonNull(tipoRecurso, "El descargo necesita su tipo de recurso");
        Objects.requireNonNull(sustento, "Un descargo sin fundamento no es un descargo");
        sustento = sustento.strip();
        if (sustento.isEmpty() || sustento.length() > SUSTENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El fundamento del administrado va de 1 a " + SUSTENTO_MAXIMO + " caracteres");
        }
        Objects.requireNonNull(
                presentadoHasta,
                "El descargo dice hasta cuando era admisible: el plazo entra como dato (regla 5)");
        if (conjuntoId <= 0) {
            throw new IllegalArgumentException(
                    "El descargo dice de que conjunto sellado salio su plazo (ARQ-09 §3)");
        }
        // La misma condicion que descargo_plazo_ck (V41), aqui para que falle al construir el
        // objeto y no al llegar a la base.
        if (enPlazo != !fecha.isAfter(presentadoHasta)) {
            throw new IllegalArgumentException(
                    "El descargo se presento el "
                            + fecha
                            + " y el plazo vencia el "
                            + presentadoHasta
                            + ": decir lo contrario dejaria un recurso tardio admitido como si"
                            + " hubiera llegado a tiempo");
        }
        Objects.requireNonNull(registradoEn, "El descargo dice cuando se registro");
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
    }

    /** Un descargo recién presentado, todavía sin guardar. */
    public static Descargo nuevo(
            long papeletaId,
            String numeroExpediente,
            LocalDate fecha,
            TipoDeRecurso tipoRecurso,
            String sustento,
            LocalDate presentadoHasta,
            long conjuntoId,
            Instant registradoEn,
            Observacion observacion) {
        return new Descargo(
                null,
                papeletaId,
                numeroExpediente,
                fecha,
                tipoRecurso,
                sustento,
                presentadoHasta,
                conjuntoId,
                !fecha.isAfter(presentadoHasta),
                registradoEn,
                null,
                observacion);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        return Objects.requireNonNull(id, "El descargo todavia no se ha guardado");
    }
}
