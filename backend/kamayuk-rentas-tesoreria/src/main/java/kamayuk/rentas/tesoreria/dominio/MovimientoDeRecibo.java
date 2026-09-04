package kamayuk.rentas.tesoreria.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Un acto sobre un recibo ya emitido: su anulacion o uno de sus duplicados (V30, #34).
 *
 * <p><b>Solo se agrega.</b> {@code recibo_movimiento} recibe {@code SELECT} e {@code INSERT} y nada
 * mas, y esta en {@code TABLAS_INMUTABLES} del escaner de fuentes. Una anulacion registrada por
 * error no se edita: lo que corresponde es otro acto —una cobranza nueva—, no reescribir el acta.
 *
 * <p><b>{@link #cajaId} y {@link #turnoId} son los del recibo, no los de quien registra.</b> Una
 * anulacion del mismo dia saca dinero del cajon en el que entro, y el arqueo de ese turno (#36)
 * tiene que poder restarla. Copiarlos aqui es lo que deja que el cierre de caja los cuente sin
 * volver a cruzar con {@code recibo}.
 *
 * <p><b>{@link #importe} se congela.</b> Es lo que la reversion devolvio al libro, copiado y no
 * releido, por lo mismo que el desglose de {@code recibo_detalle}: dentro de dos anios el libro
 * dira otra cosa —habra mas asientos— y el acta tiene que poder explicarse sola.
 *
 * @param id nulo mientras no se ha guardado
 * @param reciboId el recibo sobre el que se actua
 * @param tipo anulacion o duplicado
 * @param fecha el dia del acto; entra como argumento, no sale del reloj del dominio (regla 6)
 * @param cajaId la ventanilla del recibo
 * @param turnoId la apertura contra la que se cobro el recibo
 * @param motivo por que se anula; obligatorio en una anulacion, nulo en un duplicado
 * @param autorizadoPor quien autorizo la anulacion, si consta
 * @param documentoAutorizacion el memorando o la resolucion que la sustenta, si consta
 * @param importe lo que la reversion devolvio a deber; nulo en un duplicado
 * @param resumen SHA-256 del recibo dibujado; nulo en una anulacion
 * @param usuarioRegistro quien lo registro; nulo mientras no se ha guardado, porque lo pone el
 *     repositorio desde el origen de la peticion y no quien construye el objeto
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record MovimientoDeRecibo(
        @Nullable Long id,
        long reciboId,
        TipoDeMovimientoDeRecibo tipo,
        LocalDate fecha,
        long cajaId,
        long turnoId,
        @Nullable String motivo,
        @Nullable String autorizadoPor,
        @Nullable String documentoAutorizacion,
        @Nullable Dinero importe,
        @Nullable String resumen,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** {@code recibo_movimiento.motivo varchar(80)}. */
    private static final int MOTIVO_MAXIMO = 80;

    /** {@code recibo_movimiento.autorizado_por varchar(80)}. */
    private static final int AUTORIZADO_MAXIMO = 80;

    /** {@code recibo_movimiento.documento_autorizacion varchar(40)}. */
    private static final int DOCUMENTO_MAXIMO = 40;

    /** {@code recibo_movimiento.resumen char(64)}: un SHA-256 en hexadecimal. */
    private static final int RESUMEN = 64;

    public MovimientoDeRecibo {
        if (reciboId <= 0) {
            throw new IllegalArgumentException("Un movimiento es de un recibo concreto");
        }
        Objects.requireNonNull(tipo, "El movimiento necesita su tipo");
        Objects.requireNonNull(fecha, "El movimiento necesita su fecha");
        if (cajaId <= 0 || turnoId <= 0) {
            throw new IllegalArgumentException(
                    "El movimiento copia la caja y el turno del recibo: el arqueo del dia los"
                            + " necesita para restar lo anulado (#36)");
        }
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        motivo = recortar(motivo, MOTIVO_MAXIMO, "El motivo");
        autorizadoPor = recortar(autorizadoPor, AUTORIZADO_MAXIMO, "La autorizacion");
        documentoAutorizacion =
                recortar(documentoAutorizacion, DOCUMENTO_MAXIMO, "El documento de autorizacion");

        if (tipo == TipoDeMovimientoDeRecibo.ANULACION) {
            if (motivo == null) {
                throw new IllegalArgumentException(
                        "Anular un recibo exige su motivo: sin el, el acta no dice por que dejo de"
                                + " valer un documento que el contribuyente tiene en la mano"
                                + " (RNF-052)");
            }
            if (importe == null) {
                throw new IllegalArgumentException(
                        "La anulacion congela lo que la reversion devolvio al libro; sin esa cifra"
                                + " el acta no se puede explicar dentro de dos anios");
            }
            if (importe.esNegativo()) {
                throw new IllegalArgumentException("Lo reversado no es negativo: " + importe);
            }
        } else if (resumen == null) {
            throw new IllegalArgumentException(
                    "Un duplicado guarda el resumen de lo que dibujo: es lo que convierte «la"
                            + " reimpresion sale identica» en algo que se comprueba");
        }

        if (resumen != null && resumen.length() != RESUMEN) {
            throw new IllegalArgumentException(
                    "El resumen SHA-256 son " + RESUMEN + " caracteres, no " + resumen.length());
        }
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
    }

    /** Una anulacion sin guardar, con lo que la reversion devolvio al libro. */
    public static MovimientoDeRecibo anulacion(
            Recibo recibo,
            LocalDate fecha,
            String motivo,
            @Nullable String autorizadoPor,
            @Nullable String documentoAutorizacion,
            Dinero reversado,
            Observacion observacion) {
        return new MovimientoDeRecibo(
                null,
                Objects.requireNonNull(recibo.id(), "Solo se anula un recibo ya emitido"),
                TipoDeMovimientoDeRecibo.ANULACION,
                fecha,
                recibo.cajaId(),
                recibo.turnoId(),
                motivo,
                autorizadoPor,
                documentoAutorizacion,
                reversado,
                null,
                null,
                observacion);
    }

    /** Un duplicado sin guardar, con el resumen de lo que se dibujo. */
    public static MovimientoDeRecibo duplicado(
            Recibo recibo, LocalDate fecha, String resumen, Observacion observacion) {
        return new MovimientoDeRecibo(
                null,
                Objects.requireNonNull(recibo.id(), "Solo se duplica un recibo ya emitido"),
                TipoDeMovimientoDeRecibo.DUPLICADO,
                fecha,
                recibo.cajaId(),
                recibo.turnoId(),
                null,
                null,
                null,
                null,
                resumen,
                null,
                observacion);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El motivo, exigiendo que sea una anulacion. */
    public String motivoDeLaAnulacion() {
        return Objects.requireNonNull(motivo, "Solo una anulacion tiene motivo");
    }

    /** Lo reversado, exigiendo que sea una anulacion. */
    public Dinero importeReversado() {
        return Objects.requireNonNull(importe, "Solo una anulacion congela lo reversado");
    }

    private static @Nullable String recortar(@Nullable String valor, int maximo, String que) {
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
