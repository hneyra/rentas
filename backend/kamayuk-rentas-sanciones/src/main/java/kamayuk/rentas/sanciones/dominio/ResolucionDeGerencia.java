package kamayuk.rentas.sanciones.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Una resolución de gerencia sobre una papeleta (#50, RF-065, RF-074, V41 §3).
 *
 * <h2>La sancionadora lleva su sustento dentro</h2>
 *
 * <p>{@link #ordinariaNotificacionId} y {@link #ordinariaExigibleDesde} son la diligencia que
 * notificó la ordinaria y el día desde el que, vencido el plazo, se puede sancionar. Se
 * <b>copian</b>, no se releen: es el patrón exacto de {@code acto_coactivo.rec1_exigible_desde}
 * (V34) y de {@code valor_movimiento.exigible_desde} (V28), y el motivo es el mismo —la fila tiene
 * que poder explicarse sola dentro de dos años, con el plazo que regía entonces—.
 *
 * <p>Las tres condiciones que la base comprueba ({@code resolucion_gerencia_fallo_ck}, {@code
 * ..._sustento_ck} y {@code ..._plazo_ck}) están además aquí, para que fallen al construir el
 * objeto en vez de al llegar al motor.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param papeletaId la papeleta sobre la que se resuelve
 * @param tipo cuál de las tres resoluciones es
 * @param numero el número del documento emitido; no es un correlativo propio
 * @param documentoId la fila de {@code documento_emitido} que la dibujó
 * @param fecha el día de la resolución
 * @param descargoId el recurso que resuelve, si resuelve alguno
 * @param sentido con qué sentido lo resuelve; va con el descargo o no va
 * @param efecto qué le pasa a la multa; va con el descargo o no va
 * @param ordinariaNotificacionId la diligencia que notificó la ordinaria; solo en la sancionadora
 * @param ordinariaExigibleDesde desde cuándo se puede sancionar; solo en la sancionadora
 * @param sancionAccesoria la sanción no pecuniaria que se deriva, si la hay
 * @param sustento el fundamento de la resolución
 * @param registradoEn cuándo se registró
 * @param usuarioRegistro quién la dictó; nulo mientras no se ha guardado
 * @param observacion por qué se dicta (regla 10, RNF-052)
 */
public record ResolucionDeGerencia(
        @Nullable Long id,
        long papeletaId,
        TipoDeResolucionDeGerencia tipo,
        String numero,
        long documentoId,
        LocalDate fecha,
        @Nullable Long descargoId,
        @Nullable SentidoDelFallo sentido,
        @Nullable EfectoSobreLaMulta efecto,
        @Nullable Long ordinariaNotificacionId,
        @Nullable LocalDate ordinariaExigibleDesde,
        @Nullable String sancionAccesoria,
        String sustento,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** {@code resolucion_gerencia.numero varchar(40)}. */
    public static final int NUMERO_MAXIMO = 40;

    /** {@code resolucion_gerencia.sustento varchar(1000)}. */
    public static final int SUSTENTO_MAXIMO = 1000;

    /** {@code resolucion_gerencia.sancion_accesoria varchar(200)}. */
    public static final int SANCION_MAXIMA = 200;

    public ResolucionDeGerencia {
        if (papeletaId <= 0) {
            throw new IllegalArgumentException("Una resolucion resuelve sobre una papeleta");
        }
        Objects.requireNonNull(tipo, "La resolucion necesita su tipo");
        Objects.requireNonNull(fecha, "La resolucion necesita su fecha");
        Objects.requireNonNull(registradoEn, "La resolucion dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
        if (documentoId <= 0) {
            throw new IllegalArgumentException(
                    "Una resolucion se materializa en un documento emitido: sin el no hay nada que"
                            + " notificar ni que reimprimir (RF-132)");
        }
        numero = exigido(numero, NUMERO_MAXIMO, "El numero de la resolucion");
        sustento = exigido(sustento, SUSTENTO_MAXIMO, "El sustento de la resolucion");
        sancionAccesoria = recortado(sancionAccesoria, SANCION_MAXIMA, "La sancion accesoria");

        // resolucion_gerencia_fallo_ck: el fallo va con el recurso que resuelve.
        boolean conFallo = sentido != null && efecto != null;
        if ((descargoId != null) != conFallo) {
            throw new IllegalArgumentException(
                    descargoId != null
                            ? "Una resolucion que resuelve un recurso declara su sentido y su"
                                    + " efecto sobre la multa"
                            : "Una resolucion que no resuelve ningun recurso no declara nada"
                                    + " fundado ni infundado: sin descargo no hay fallo");
        }

        // resolucion_gerencia_sustento_ck: solo la sancionadora lleva el suyo, y entero.
        boolean conSustentoDeLaOrdinaria =
                ordinariaNotificacionId != null && ordinariaExigibleDesde != null;
        if (tipo.exigeOrdinariaVencida() != conSustentoDeLaOrdinaria) {
            throw new IllegalArgumentException(
                    tipo.exigeOrdinariaVencida()
                            ? "La sancionadora lleva dentro su sustento: que diligencia notifico la"
                                    + " ordinaria y desde cuando, vencido el plazo, se puede"
                                    + " sancionar"
                            : "Solo la sancionadora se sustenta en el plazo de la ordinaria");
        }

        // resolucion_gerencia_plazo_ck: no hay sancionadora antes de que venza el plazo.
        if (ordinariaExigibleDesde != null && fecha.isBefore(ordinariaExigibleDesde)) {
            throw new IllegalArgumentException(
                    "La sancionadora no puede ser anterior al "
                            + ordinariaExigibleDesde
                            + ", que es cuando vence el plazo que la ordinaria concedio: dictada"
                            + " antes, la sancion es nula");
        }
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
    }

    /**
     * Una resolución sin guardar que no es la sancionadora: sin el sustento del plazo, con o sin
     * recurso resuelto.
     */
    public static ResolucionDeGerencia nueva(
            long papeletaId,
            TipoDeResolucionDeGerencia tipo,
            String numero,
            long documentoId,
            LocalDate fecha,
            @Nullable Long descargoId,
            @Nullable SentidoDelFallo sentido,
            @Nullable EfectoSobreLaMulta efecto,
            @Nullable String sancionAccesoria,
            String sustento,
            Instant registradoEn,
            Observacion observacion) {
        return new ResolucionDeGerencia(
                null,
                papeletaId,
                tipo,
                numero,
                documentoId,
                fecha,
                descargoId,
                sentido,
                efecto,
                null,
                null,
                sancionAccesoria,
                sustento,
                registradoEn,
                null,
                observacion);
    }

    /** La sancionadora sin guardar, con el sustento de la ordinaria copiado. */
    public static ResolucionDeGerencia sancionadora(
            long papeletaId,
            String numero,
            long documentoId,
            LocalDate fecha,
            @Nullable Long descargoId,
            @Nullable SentidoDelFallo sentido,
            @Nullable EfectoSobreLaMulta efecto,
            long ordinariaNotificacionId,
            LocalDate ordinariaExigibleDesde,
            @Nullable String sancionAccesoria,
            String sustento,
            Instant registradoEn,
            Observacion observacion) {
        return new ResolucionDeGerencia(
                null,
                papeletaId,
                TipoDeResolucionDeGerencia.SANCIONADORA,
                numero,
                documentoId,
                fecha,
                descargoId,
                sentido,
                efecto,
                ordinariaNotificacionId,
                ordinariaExigibleDesde,
                sancionAccesoria,
                sustento,
                registradoEn,
                null,
                observacion);
    }

    public boolean esNueva() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        return Objects.requireNonNull(id, "La resolucion todavia no se ha guardado");
    }

    /** Si esta resolución deja la multa sin efecto y, por tanto, mueve el libro. */
    public boolean dejaLaMultaSinEfecto() {
        return efecto != null && efecto.extingueLaDeuda();
    }

    private static String exigido(String valor, int maximo, String que) {
        String limpio = Objects.requireNonNull(valor, que + " es obligatorio").strip();
        if (limpio.isEmpty()) {
            throw new IllegalArgumentException(que + " es obligatorio");
        }
        if (limpio.length() > maximo) {
            throw new IllegalArgumentException(que + " excede " + maximo + " caracteres");
        }
        return limpio;
    }

    private static @Nullable String recortado(@Nullable String valor, int maximo, String que) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        if (limpio.isEmpty()) {
            return null;
        }
        if (limpio.length() > maximo) {
            throw new IllegalArgumentException(que + " excede " + maximo + " caracteres");
        }
        return limpio;
    }
}
