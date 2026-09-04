package kamayuk.rentas.coactiva.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import org.jspecify.annotations.Nullable;

/**
 * El acto de notificar una resolucion coactiva, con su acuse (V3 + V28, #41, RF-103).
 *
 * <h2>Es una fila de {@code notificacion}, la misma tabla que notifica un valor</h2>
 *
 * <p>V3 nacio esa tabla polimorfica —{@code objeto} admite {@code VALOR}, {@code RESOLUCION},
 * {@code ACTO_COACTIVO} y {@code PAPELETA}— y V28 le puso, para #39, todo lo que una notificacion
 * necesita: el intento, el receptor, el acuse, la exigibilidad con el conjunto sellado del que
 * salio el plazo, y {@code notificacion_intento_uq}. Nada de eso era especifico del valor, asi que
 * #41 <b>no toca el esquema de la tabla</b>: escribe filas con {@code objeto = 'ACTO_COACTIVO'} y
 * {@code objeto_id = acto_coactivo.id}.
 *
 * <p>Este record existe —en vez de reusar {@code valores.dominio.Notificacion}— porque aquel vive
 * en un subpaquete de otro contexto y Spring Modulith lo trata como interno (ARQ-01 §4). Lo que
 * <b>no</b> se duplica es lo que importa: el vocabulario ({@link ModalidadDeNotificacion}, {@link
 * ResultadoDeNotificacion}) y el computo del plazo viven en el dominio compartido desde #41, porque
 * la columna es una sola y su restriccion {@code CHECK} tambien.
 *
 * <h2>Una diligencia no se corrige: se vuelve a diligenciar</h2>
 *
 * <p>Un intento {@link ResultadoDeNotificacion#NO_UBICADO} deja su fila y el siguiente entra con el
 * {@link #intento} siguiente. Este tipo no tiene ningun metodo que cambie un campo: {@code
 * notificacion} perdio el privilegio de {@code UPDATE} en V28, y lo que en otro dominio seria una
 * correccion, aqui es una fila mas.
 *
 * <h2>La exigibilidad viaja en la fila</h2>
 *
 * <p>{@link #exigibleDesde} es el dia desde el que, vencidos los siete dias habiles del art. 14.1
 * de la Ley 26979, se puede dictar la medida cautelar. Solo existe cuando el resultado {@linkplain
 * ResultadoDeNotificacion#surteEfecto() surte efecto}, y la base lo obliga ({@code
 * notificacion_exigibilidad_ck}, V28). Se guarda junto con el conjunto sellado del que salio el
 * plazo: si se recalculara al leer, un plazo sellado despues daria otra fecha y nadie lo notaria.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param actoId el acto coactivo notificado
 * @param numero identifica la diligencia; unico por objeto
 * @param intento que diligencia es, desde 1
 * @param fechaDeLaDiligencia cuando se diligencio
 * @param modalidad como se diligencio (art. 104 del TUO del Codigo Tributario)
 * @param resultado con que resultado termino
 * @param notificador quien la llevo
 * @param direccion donde se diligencio: la direccion referencial vigente del expediente, o la que
 *     quien registra haya dado
 * @param receptor quien recibio; nulo si nadie recibio
 * @param documentoReceptor su documento
 * @param vinculo su vinculo con el obligado
 * @param acuse la constancia del cargo
 * @param exigibleDesde desde cuando se puede dictar la medida; nulo si no surtio efecto
 * @param conjuntoId de que conjunto sellado salio el plazo; nulo en el mismo caso
 * @param usuarioRegistro quien la registro; nulo mientras no se ha guardado
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record NotificacionCoactiva(
        @Nullable Long id,
        long actoId,
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

    /** El valor de {@code notificacion.objeto} para un acto coactivo (V3). */
    public static final String OBJETO = "ACTO_COACTIVO";

    private static final int NUMERO_MAXIMO = 20;
    private static final int NOTIFICADOR_MAXIMO = 60;
    private static final int DIRECCION_MAXIMA = 300;
    private static final int RECEPTOR_MAXIMO = 120;
    private static final int DOCUMENTO_MAXIMO = 20;
    private static final int VINCULO_MAXIMO = 40;
    private static final int ACUSE_MAXIMO = 80;

    public NotificacionCoactiva {
        if (actoId <= 0) {
            throw new IllegalArgumentException(
                    "Una notificacion coactiva notifica un acto: el identificador debe ser"
                            + " positivo");
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

        // La misma condicion que notificacion_exigibilidad_ck (V28), aqui para que falle al
        // construir el objeto y no al llegar a la base.
        boolean conExigibilidad = exigibleDesde != null && conjuntoId != null;
        if (resultado.surteEfecto() != conExigibilidad) {
            throw new IllegalArgumentException(
                    resultado.surteEfecto()
                            ? "Una diligencia que surte efecto fija desde cuando se puede dictar la"
                                    + " medida cautelar, y con que conjunto sellado se calculo el"
                                    + " plazo"
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

    /** Si esta diligencia abrio el plazo del art. 14.1. */
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
