package kamayuk.rentas.tesoreria.pagos;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Un pago que la caja publico (P5D, ADR-0026 §3). Una fila de {@code pago_recibido}.
 *
 * @param pagoId el identificador que <b>genero la caja</b>. Un reintento manda el mismo, y por eso
 *     se puede deduplicar. Es la mitad receptora del criterio 3 del encargo
 * @param pagoOriginalId el pago que una anulacion deshace; solo en {@link
 *     TipoDePagoRecibido#PAGO_ANULADO}
 * @param obligaciones lo que el pago cobro, ya leido de la referencia externa. <b>Vacio en una
 *     anulacion</b>: lo que se deshace es el pago entero, y sus asientos se encuentran por el
 *     documento de origen
 * @param motivoDeLaAnulacion por que se anulo el recibo, en las palabras de quien lo autorizo en
 *     ventanilla; solo en {@link TipoDePagoRecibido#PAGO_ANULADO}. <b>Nulo tambien ahi si la fila
 *     es anterior a `V10`</b>: hasta C-1 este campo lo descartaba Jackson en silencio
 * @param fechaDeAnulacion cuando se anulo, y por tanto la fecha valor de la reversion; misma
 *     condicion que el anterior. NO es {@code fechaDePago}, que es la del recibo original
 * @param total lo que el pago DIJO que se cobro, congelado
 */
public record PagoRecibido(
        @Nullable Long id,
        UUID pagoId,
        TipoDePagoRecibido tipo,
        @Nullable UUID pagoOriginalId,
        String sistemaCaja,
        String reciboNumero,
        @Nullable Long contribuyenteId,
        LocalDate fechaDePago,
        @Nullable String motivoDeLaAnulacion,
        @Nullable LocalDate fechaDeAnulacion,
        Dinero total,
        List<ReferenciaDeObligacion> obligaciones,
        String cuerpo,
        EstadoDelPagoRecibido estado,
        int asientos,
        @Nullable String motivo,
        Instant recibidoEn,
        @Nullable Instant aplicadoEn) {

    /** El ancho de {@code pago_recibido.motivo_anulacion}. Ver la cabecera de `V10`. */
    private static final int LARGO_DEL_MOTIVO = 300;

    public PagoRecibido {
        Objects.requireNonNull(pagoId, "Un pago recibido lleva su identificador");
        Objects.requireNonNull(tipo, "Un pago recibido dice que le paso al dinero");
        Objects.requireNonNull(sistemaCaja, "Un pago recibido dice de que caja viene");
        Objects.requireNonNull(reciboNumero, "Un pago recibido nombra su recibo");
        Objects.requireNonNull(fechaDePago, "Toda cifra indica su fecha (regla 9, RNF-075)");
        Objects.requireNonNull(total, "Un pago recibido trae su total");
        Objects.requireNonNull(cuerpo, "El evento se guarda entero, congelado");
        Objects.requireNonNull(estado, "Un pago recibido dice en que esta");
        Objects.requireNonNull(recibidoEn, "Un pago recibido dice cuando llego");
        obligaciones = List.copyOf(obligaciones);
        if ((tipo == TipoDePagoRecibido.PAGO_ANULADO) != (pagoOriginalId != null)) {
            throw new IllegalArgumentException(
                    "Una anulacion dice que pago deshace, y un cobro no puede decirlo: tipo="
                            + tipo);
        }
        // EN UNA SOLA DIRECCION, y a proposito (C-1). Que un cobro NO pueda traer el motivo ni
        // la fecha de una anulacion es siempre cierto; que una anulacion los traiga siempre no
        // lo es, porque este record tambien RECONSTRUYE filas leidas de la base y las
        // anteriores a `V10` no los tienen. La direccion que falta la sostienen el borde
        // —`PagoController`, que es donde llega el cuerpo de la caja— y `enTransito`, que es
        // por donde entra un pago nuevo. Es el reparto que `V77` de `sgtm` dejo medido.
        if (tipo != TipoDePagoRecibido.PAGO_ANULADO
                && (motivoDeLaAnulacion != null || fechaDeAnulacion != null)) {
            throw new IllegalArgumentException(
                    "Un cobro no se anula a si mismo: solo un PAGO_ANULADO lleva motivo y fecha de"
                            + " anulacion. tipo="
                            + tipo);
        }
        if (motivoDeLaAnulacion != null) {
            motivoDeLaAnulacion = motivoDeLaAnulacion.strip();
            if (motivoDeLaAnulacion.isEmpty()) {
                throw new IllegalArgumentException(
                        "El motivo de la anulacion no puede estar en blanco: es el sustento de"
                                + " dejar sin efecto un documento que el contribuyente tiene en la"
                                + " mano (RNF-052)");
            }
            if (motivoDeLaAnulacion.length() > LARGO_DEL_MOTIVO) {
                throw new IllegalArgumentException(
                        "El motivo de la anulacion excede "
                                + LARGO_DEL_MOTIVO
                                + " caracteres, que es lo que admite la columna y lo que deja"
                                + " sitio para que quepa en la observacion de 500: "
                                + motivoDeLaAnulacion.length());
            }
        }
        if ((estado == EstadoDelPagoRecibido.APLICADO) != (aplicadoEn != null)) {
            throw new IllegalArgumentException(
                    "Un pago APLICADO lleva la hora en que se imputo, y uno que no lo esta no puede"
                            + " llevarla: estado="
                            + estado);
        }
        if (estado == EstadoDelPagoRecibido.RECHAZADO
                && (motivo == null || motivo.strip().length() < 5)) {
            throw new IllegalArgumentException(
                    "Un pago RECHAZADO dice por que: es dinero cobrado que el libro no admitio, y"
                            + " sin motivo quien lo mire tiene que reconstruirlo desde cero");
        }
        if (asientos < 0) {
            throw new IllegalArgumentException("Los asientos se cuentan desde cero: " + asientos);
        }
    }

    /**
     * Uno recien llegado: en transito, sin asientos y sin hora de aplicacion.
     *
     * <p><b>Aqui si se exigen las dos mitades de la anulacion</b>, al reves que en el constructor
     * compacto: este es el punto de entrada de un pago nuevo y no reconstruye ninguna fila, asi que
     * una anulacion que llegue sin su motivo o sin su fecha es un cuerpo que la caja no publica.
     */
    public static PagoRecibido enTransito(
            UUID pagoId,
            TipoDePagoRecibido tipo,
            @Nullable UUID pagoOriginalId,
            String sistemaCaja,
            String reciboNumero,
            @Nullable Long contribuyenteId,
            LocalDate fechaDePago,
            @Nullable String motivoDeLaAnulacion,
            @Nullable LocalDate fechaDeAnulacion,
            Dinero total,
            List<ReferenciaDeObligacion> obligaciones,
            String cuerpo,
            Instant recibidoEn) {
        if (tipo == TipoDePagoRecibido.PAGO_ANULADO
                && (motivoDeLaAnulacion == null || fechaDeAnulacion == null)) {
            throw new IllegalArgumentException(
                    "Una anulacion que entra al buzon dice por que y cuando: sin el motivo nadie"
                            + " puede explicar por que una deuda volvio a estar viva, y sin la"
                            + " fecha su reversion se asentaria con la del recibo original y"
                            + " reescribiria la historia (C-1)");
        }
        return new PagoRecibido(
                null,
                pagoId,
                tipo,
                pagoOriginalId,
                sistemaCaja,
                reciboNumero,
                contribuyenteId,
                fechaDePago,
                motivoDeLaAnulacion,
                fechaDeAnulacion,
                total,
                obligaciones,
                cuerpo,
                EstadoDelPagoRecibido.EN_TRANSITO,
                0,
                null,
                recibidoEn,
                null);
    }

    /**
     * El motivo con que la caja anulo, exigido.
     *
     * <p>Lo llama la reversion, que solo corre sobre una fila recien insertada por {@link
     * #enTransito}: ahi las dos mitades estan garantizadas. Falla en voz alta —igual que {@link
     * #idGuardado()}— en vez de componer una frase sin el, que dejaria el libro diciendo que una
     * deuda volvio a estar viva «por la anulacion del recibo» y nada mas.
     */
    public String motivoDeLaAnulacionExigido() {
        return Objects.requireNonNull(
                motivoDeLaAnulacion,
                "Esta anulacion no trae su motivo: solo puede ser una fila anterior a `V10`, y esas"
                        + " no se vuelven a reversar");
    }

    /** La fecha en que se anulo, exigida. Misma razon que {@link #motivoDeLaAnulacionExigido()}. */
    public LocalDate fechaDeAnulacionExigida() {
        return Objects.requireNonNull(
                fechaDeAnulacion,
                "Esta anulacion no trae su fecha: solo puede ser una fila anterior a `V10`, y esas"
                        + " no se vuelven a reversar");
    }

    public long idGuardado() {
        return Objects.requireNonNull(id, "Un pago leido del buzon trae su identificador");
    }

    /**
     * Como se marcan en el libro los asientos de este pago.
     *
     * <p>Es el mismo texto que la caja escribia cuando el cobro era una sola transaccion —{@code
     * "RECIBO " + numero}— y se conserva letra por letra: cambiarlo dejaria los abonos de antes de
     * P5D sin poder emparejarse con los de despues, y la anulacion de un recibo viejo no
     * encontraria sus asientos.
     */
    public String documentoDeOrigen() {
        return "RECIBO " + reciboNumero;
    }

    /** Y como se marcan los de su reversion. Misma razon. */
    public String documentoDeLaAnulacion() {
        return "ANULACION RECIBO " + reciboNumero;
    }
}
