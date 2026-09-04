package kamayuk.rentas.tesoreria.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Un acto sobre un convenio ya registrado: su formalizacion o su cierre (V31, #35).
 *
 * <p><b>Solo se agrega.</b> {@code convenio_movimiento} recibe {@code SELECT} e {@code INSERT} y
 * nada mas, y esta en {@code TABLAS_INMUTABLES} del escaner de fuentes. Es a {@code convenio} lo
 * que {@code recibo_movimiento} (V30) es a {@code recibo} y {@code valor_movimiento} (V28) a {@code
 * valor}. Un quiebre registrado por error no se edita: lo que corresponde es un convenio nuevo, no
 * reescribir el acta.
 *
 * <p><b>{@link #importe} se congela.</b> Es lo que el acogimiento movio, o lo que el cierre
 * devolvio, copiado y no releido, por lo mismo que el desglose de {@code recibo_detalle}: dentro de
 * dos anios el libro dira otra cosa —habra mas asientos— y el acta tiene que poder explicarse sola.
 * Y no se supone: quien lo registra lo <b>comprueba</b> contra lo que {@code cuentacorriente} dijo
 * haber asentado.
 *
 * <p><b>{@link #asientos} cuenta filas del libro</b>, no dinero. Son dos unidades distintas y van
 * en dos campos: es lo que permite decir «este quiebre escribio catorce asientos, y ahi estan».
 *
 * @param id nulo mientras no se ha guardado
 * @param convenioId el convenio sobre el que se actua
 * @param tipo formalizacion, anulacion, quiebre o reformulacion
 * @param fecha el dia del acto; entra como argumento, no sale del reloj del dominio (regla 6)
 * @param reciboId el recibo que cobro la cuota; obligatorio en la formalizacion, nulo en el cierre
 * @param cuota que cuota pago ese recibo; 0 es la inicial
 * @param motivo por que se cierra; obligatorio en el cierre, nulo en la formalizacion
 * @param autorizadoPor quien lo autorizo, si consta
 * @param documentoAutorizacion el memorando o la resolucion que lo sustenta, si consta
 * @param importe lo que se movio o lo que se devolvio, congelado
 * @param asientos cuantas filas se escribieron en el libro
 * @param convenioNuevoId el convenio que sustituye a este; solo en la reformulacion
 * @param registradoEn el instante del registro; sale del reloj inyectado
 * @param usuarioRegistro quien lo registro; nulo mientras no se ha guardado, porque lo pone el
 *     repositorio desde el origen de la peticion y no quien construye el objeto
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record MovimientoDeConvenio(
        @Nullable Long id,
        long convenioId,
        TipoDeMovimientoDeConvenio tipo,
        LocalDate fecha,
        @Nullable Long reciboId,
        @Nullable Integer cuota,
        @Nullable String motivo,
        @Nullable String autorizadoPor,
        @Nullable String documentoAutorizacion,
        Dinero importe,
        int asientos,
        @Nullable Long convenioNuevoId,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** {@code convenio_movimiento.motivo varchar(80)}. */
    private static final int MOTIVO_MAXIMO = 80;

    /** {@code convenio_movimiento.autorizado_por varchar(80)}. */
    private static final int AUTORIZADO_MAXIMO = 80;

    /** {@code convenio_movimiento.documento_autorizacion varchar(40)}. */
    private static final int DOCUMENTO_MAXIMO = 40;

    public MovimientoDeConvenio {
        if (convenioId <= 0) {
            throw new IllegalArgumentException("Un movimiento es de un convenio concreto");
        }
        Objects.requireNonNull(tipo, "El movimiento necesita su tipo");
        Objects.requireNonNull(fecha, "El movimiento necesita su fecha");
        Objects.requireNonNull(importe, "El movimiento congela lo que movio");
        Objects.requireNonNull(registradoEn, "El movimiento dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
        if (importe.esNegativo()) {
            throw new IllegalArgumentException("Lo movido no es negativo: " + importe);
        }
        if (asientos < 0) {
            throw new IllegalArgumentException(
                    "Un movimiento escribe asientos, no los quita: " + asientos);
        }

        motivo = recortar(motivo, MOTIVO_MAXIMO, "El motivo");
        autorizadoPor = recortar(autorizadoPor, AUTORIZADO_MAXIMO, "La autorizacion");
        documentoAutorizacion =
                recortar(documentoAutorizacion, DOCUMENTO_MAXIMO, "El documento de autorizacion");

        if (tipo == TipoDeMovimientoDeConvenio.FORMALIZACION) {
            if (reciboId == null || cuota == null) {
                throw new IllegalArgumentException(
                        "Formalizar exige el recibo que cobro la cuota inicial y que cuota pago:"
                                + " sin cuota inicial pagada en caja no hay convenio (RF-084)");
            }
            if (cuota < 0) {
                throw new IllegalArgumentException("La cuota inicial es la 0; llego " + cuota);
            }
        } else if (motivo == null) {
            throw new IllegalArgumentException(
                    "Cerrar un convenio exige su motivo: sin el, el acta no dice por que la deuda"
                            + " volvio a su fase de origen (RNF-052)");
        }

        if ((tipo == TipoDeMovimientoDeConvenio.REFORMULACION) != (convenioNuevoId != null)) {
            throw new IllegalArgumentException(
                    "Solo una reformulacion nombra el convenio que la sustituye, y siempre lo"
                            + " nombra: si no, el saldo pendiente se quedaria sin convenio y sin"
                            + " rastro de a donde fue");
        }
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
    }

    /** La formalizacion sin guardar: el cobro de la inicial pone el convenio en vigor. */
    public static MovimientoDeConvenio formalizacion(
            long convenioId,
            LocalDate fecha,
            long reciboId,
            int cuota,
            Dinero acogido,
            int asientos,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDeConvenio(
                null,
                convenioId,
                TipoDeMovimientoDeConvenio.FORMALIZACION,
                fecha,
                reciboId,
                cuota,
                null,
                null,
                null,
                acogido,
                asientos,
                null,
                registradoEn,
                null,
                observacion);
    }

    /** Un cierre sin guardar: anulacion, quiebre o reformulacion, con lo que devolvio. */
    public static MovimientoDeConvenio cierre(
            long convenioId,
            TipoDeMovimientoDeConvenio tipo,
            LocalDate fecha,
            String motivo,
            @Nullable String autorizadoPor,
            @Nullable String documentoAutorizacion,
            Dinero devuelto,
            int asientos,
            @Nullable Long convenioNuevoId,
            Instant registradoEn,
            Observacion observacion) {
        if (!tipo.cierra()) {
            throw new IllegalArgumentException("La formalizacion no cierra un convenio: " + tipo);
        }
        return new MovimientoDeConvenio(
                null,
                convenioId,
                tipo,
                fecha,
                null,
                null,
                motivo,
                autorizadoPor,
                documentoAutorizacion,
                devuelto,
                asientos,
                convenioNuevoId,
                registradoEn,
                null,
                observacion);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El motivo, exigiendo que sea un cierre. */
    public String motivoDelCierre() {
        return Objects.requireNonNull(motivo, "Solo un cierre tiene motivo");
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
