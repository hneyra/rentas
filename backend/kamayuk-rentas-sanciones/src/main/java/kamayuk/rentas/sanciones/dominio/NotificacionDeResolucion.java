package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import org.jspecify.annotations.Nullable;

/**
 * El acto de notificar una resolución de gerencia, con su acuse (V3 + V28, #50, RF-065).
 *
 * <h2>Es una fila de {@code notificacion}, la misma tabla que notifica un valor y una REC</h2>
 *
 * <p>V3 nació esa tabla polimórfica —{@code objeto} admite {@code VALOR}, {@code RESOLUCION},
 * {@code ACTO_COACTIVO} y {@code PAPELETA}— y V28 le puso, para #39, todo lo que una notificación
 * necesita: el intento, el receptor, el acuse, la exigibilidad con el conjunto sellado del que
 * salió el plazo, {@code notificacion_intento_uq} y el {@code REVOKE UPDATE}. #41 la usó tal cual
 * para la REC; #50 la usa tal cual para la resolución de gerencia, con {@code objeto =
 * 'RESOLUCION'}, y V41 <b>no le toca una columna</b>. Tercera vez que sirve sin cambios, que es el
 * motivo por el que se dejó polimórfica.
 *
 * <h2>De aquí sale el derecho a la sancionadora</h2>
 *
 * <p>{@link #exigibleDesde} es el día desde el que, vencido el plazo que la ordinaria concedió, se
 * puede dictar la sancionadora. Solo existe cuando el resultado {@linkplain
 * ResultadoDeNotificacion#surteEfecto() surte efecto}, y la base lo obliga ({@code
 * notificacion_exigibilidad_ck}, V28). Se guarda junto con el conjunto sellado del que salió el
 * plazo: recalcularlo al leer daría otra fecha el día que el plazo cambie.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param resolucionId la resolución notificada
 * @param numero identifica la diligencia; único por objeto
 * @param intento qué diligencia es, desde 1
 * @param fechaDeLaDiligencia cuándo se diligenció
 * @param modalidad cómo se diligenció (art. 104 del TUO del Código Tributario)
 * @param resultado con qué resultado terminó
 * @param notificador quién la llevó
 * @param direccion dónde se diligenció
 * @param receptor quién recibió; nulo si nadie recibió
 * @param documentoReceptor su documento
 * @param vinculo su vínculo con el administrado
 * @param acuse la constancia del cargo
 * @param exigibleDesde desde cuándo se puede sancionar; nulo si no surtió efecto
 * @param conjuntoId de qué conjunto sellado salió el plazo; nulo en el mismo caso
 * @param usuarioRegistro quién la registró; nulo mientras no se ha guardado
 * @param observacion por qué se registra (regla 10, RNF-052)
 */
public record NotificacionDeResolucion(
        @Nullable Long id,
        long resolucionId,
        String numero,
        int intento,
        LocalDate fechaDeLaDiligencia,
        ModalidadDeNotificacion modalidad,
        ResultadoDeNotificacion resultado,
        String notificador,
        String direccion,
        @Nullable String receptor,
        @Nullable String documentoReceptor,
        @Nullable String vinculo,
        @Nullable String acuse,
        @Nullable LocalDate exigibleDesde,
        @Nullable Long conjuntoId,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** El valor de {@code notificacion.objeto} para una resolución de gerencia (V3). */
    public static final String OBJETO = "RESOLUCION";

    private static final int NUMERO_MAXIMO = 20;
    private static final int NOTIFICADOR_MAXIMO = 60;
    private static final int DIRECCION_MAXIMA = 300;
    private static final int RECEPTOR_MAXIMO = 120;
    private static final int DOCUMENTO_MAXIMO = 20;
    private static final int VINCULO_MAXIMO = 40;
    private static final int ACUSE_MAXIMO = 80;

    public NotificacionDeResolucion {
        if (resolucionId <= 0) {
            throw new IllegalArgumentException(
                    "Una notificacion de resolucion notifica una resolucion: el identificador debe"
                            + " ser positivo");
        }
        numero = exigirTexto(numero, "numero", NUMERO_MAXIMO);
        if (intento < 1) {
            throw new IllegalArgumentException("El primer intento es el 1, no el " + intento);
        }
        Objects.requireNonNull(fechaDeLaDiligencia, "La notificacion necesita su fecha");
        Objects.requireNonNull(modalidad, "La notificacion necesita su modalidad (art. 104)");
        Objects.requireNonNull(resultado, "Una diligencia sin resultado no es un acuse");
        notificador = exigirTexto(notificador, "notificador", NOTIFICADOR_MAXIMO);
        direccion = exigirTexto(direccion, "direccion", DIRECCION_MAXIMA);
        receptor = recortar(receptor, "receptor", RECEPTOR_MAXIMO);
        documentoReceptor = recortar(documentoReceptor, "documentoReceptor", DOCUMENTO_MAXIMO);
        vinculo = recortar(vinculo, "vinculo", VINCULO_MAXIMO);
        acuse = recortar(acuse, "acuse", ACUSE_MAXIMO);

        // La misma condicion que notificacion_exigibilidad_ck (V28).
        boolean conExigibilidad = exigibleDesde != null && conjuntoId != null;
        if (resultado.surteEfecto() != conExigibilidad) {
            throw new IllegalArgumentException(
                    resultado.surteEfecto()
                            ? "Una diligencia que surte efecto fija desde cuando se puede dictar la"
                                    + " sancionadora, y con que conjunto sellado se calculo el plazo"
                            : "Una diligencia que no surte efecto no hace exigible nada: no lleva"
                                    + " fecha de exigibilidad ni conjunto");
        }
        if (exigibleDesde != null && exigibleDesde.isBefore(fechaDeLaDiligencia)) {
            throw new IllegalArgumentException(
                    "El plazo no puede vencer antes de la diligencia que lo abrio");
        }
        usuarioRegistro = recortar(usuarioRegistro, "usuarioRegistro", NOTIFICADOR_MAXIMO);
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
    }

    public boolean esNueva() {
        return id == null;
    }

    /** Si esta diligencia abrió el plazo. */
    public boolean surtioEfecto() {
        return resultado.surteEfecto();
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        return Objects.requireNonNull(id, "La notificacion todavia no se ha guardado");
    }

    private static String exigirTexto(@Nullable String valor, String campo, int maximo) {
        Objects.requireNonNull(valor, "La notificacion necesita su " + campo);
        String limpio = valor.strip();
        if (limpio.isEmpty() || limpio.length() > maximo) {
            throw new IllegalArgumentException(
                    "El campo '"
                            + campo
                            + "' va de 1 a "
                            + maximo
                            + " caracteres: '"
                            + valor
                            + "'");
        }
        return limpio;
    }

    private static @Nullable String recortar(@Nullable String valor, String campo, int maximo) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        if (limpio.isEmpty()) {
            return null;
        }
        if (limpio.length() > maximo) {
            throw new IllegalArgumentException(
                    "El campo '" + campo + "' no admite mas de " + maximo + " caracteres");
        }
        return limpio;
    }
}
