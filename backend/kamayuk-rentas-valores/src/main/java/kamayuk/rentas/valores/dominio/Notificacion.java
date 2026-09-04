package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import org.jspecify.annotations.Nullable;

/**
 * El acto de notificar un valor, con su acuse (V3 + V28, #39, RF-093).
 *
 * <h2>Una diligencia no se corrige: se vuelve a diligenciar</h2>
 *
 * <p>Un intento {@link ResultadoDeNotificacion#NO_UBICADO} deja su fila y el siguiente entra con el
 * {@link #intento} siguiente (AC de #39). Por eso este tipo no tiene ningun metodo que cambie un
 * campo: {@code notificacion} pierde el privilegio de {@code UPDATE} en V28, y lo que en otros
 * dominios seria una correccion, aqui es una fila mas.
 *
 * <h2>La exigibilidad viaja en la fila, no se recalcula al leerla</h2>
 *
 * <p>{@link #exigibleDesde} y {@link #conjuntoId} solo existen cuando el resultado {@linkplain
 * ResultadoDeNotificacion#surteEfecto() surte efecto}, y la base lo obliga. Se guardan porque un
 * expediente coactivo tiene que poder explicarse dentro de dos anios con lo que su propia fila
 * dice: si la exigibilidad se recalculara al leer, un plazo sellado despues daria otra fecha y
 * nadie lo notaria (ARQ-09 §3).
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param valorId el valor notificado
 * @param numero identifica la diligencia; unico por objeto
 * @param intento que diligencia es, desde 1
 * @param fechaDeLaDiligencia cuando se diligencio
 * @param modalidad como se diligencio (art. 104)
 * @param resultado con que resultado termino
 * @param notificador quien la llevo
 * @param direccion donde se diligencio: el domicilio fiscal vigente a esa fecha, no el ultimo (#15)
 * @param receptor quien recibio; nulo si nadie recibio
 * @param documentoReceptor su documento
 * @param vinculo su vinculo con el titular
 * @param acuse la constancia del cargo
 * @param exigibleDesde desde cuando la deuda es exigible; nulo si la diligencia no surtio efecto
 * @param conjuntoId de que conjunto sellado salio el plazo; nulo en el mismo caso
 * @param usuarioRegistro quien la registro; nulo mientras no se ha guardado
 * @param observacion por que se registra (regla 10)
 */
public record Notificacion(
        @Nullable Long id,
        long valorId,
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

    /** {@code objeto} de {@code notificacion} para un valor (V3). */
    public static final String OBJETO = "VALOR";

    private static final int NUMERO_MAXIMO = 20;
    private static final int NOTIFICADOR_MAXIMO = 60;
    private static final int DIRECCION_MAXIMA = 300;
    private static final int RECEPTOR_MAXIMO = 120;
    private static final int DOCUMENTO_MAXIMO = 20;
    private static final int VINCULO_MAXIMO = 40;
    private static final int ACUSE_MAXIMO = 80;

    public Notificacion {
        if (valorId <= 0) {
            throw new IllegalArgumentException(
                    "Una notificacion notifica un valor: el identificador debe ser positivo");
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
                            ? "Una diligencia que surte efecto fija desde cuando la deuda es"
                                    + " exigible, y con que conjunto sellado se calculo el plazo"
                            : "Una diligencia que no surte efecto no hace exigible nada: no lleva"
                                    + " fecha de exigibilidad ni conjunto");
        }
        if (exigibleDesde != null && exigibleDesde.isBefore(fechaDeLaDiligencia)) {
            throw new IllegalArgumentException(
                    "La deuda no puede ser exigible antes de la diligencia que la notifico");
        }
        usuarioRegistro = recortar(usuarioRegistro, "usuarioRegistro", NOTIFICADOR_MAXIMO);
        Objects.requireNonNull(
                observacion, "Toda modificacion de datos exige la observacion (regla 10)");
    }

    public boolean esNueva() {
        return id == null;
    }

    /** Si esta diligencia hizo exigible la deuda del valor. */
    public boolean surtioEfecto() {
        return resultado.surteEfecto();
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
