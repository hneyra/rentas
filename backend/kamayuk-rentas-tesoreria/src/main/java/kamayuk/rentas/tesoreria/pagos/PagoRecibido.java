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
        Dinero total,
        List<ReferenciaDeObligacion> obligaciones,
        String cuerpo,
        EstadoDelPagoRecibido estado,
        int asientos,
        @Nullable String motivo,
        Instant recibidoEn,
        @Nullable Instant aplicadoEn) {

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

    /** Uno recien llegado: en transito, sin asientos y sin hora de aplicacion. */
    public static PagoRecibido enTransito(
            UUID pagoId,
            TipoDePagoRecibido tipo,
            @Nullable UUID pagoOriginalId,
            String sistemaCaja,
            String reciboNumero,
            @Nullable Long contribuyenteId,
            LocalDate fechaDePago,
            Dinero total,
            List<ReferenciaDeObligacion> obligaciones,
            String cuerpo,
            Instant recibidoEn) {
        return new PagoRecibido(
                null,
                pagoId,
                tipo,
                pagoOriginalId,
                sistemaCaja,
                reciboNumero,
                contribuyenteId,
                fechaDePago,
                total,
                obligaciones,
                cuerpo,
                EstadoDelPagoRecibido.EN_TRANSITO,
                0,
                null,
                recibidoEn,
                null);
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
