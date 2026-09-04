package kamayuk.rentas.tesoreria.pagos;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * La puerta por la que este sistema le manda a la caja algo que cobrar (P5D, ADR-0026 §1).
 *
 * <h2>Lo que NO viaja, y es la mitad de la frontera</h2>
 *
 * <p>No viaja el tributo, ni el ejercicio, ni la cuota, ni el desglose. La caja no sabe que es un
 * tributo, y lo unico que la deja seguir sirviendo para cobrar un puesto de mercado es que este
 * puerto no le cuente nada de eso. Lo que se manda es un <b>concepto</b> —lo que el administrado
 * lee en el papel— y una <b>referencia</b> opaca que vuelve dentro del evento del pago.
 *
 * <p>El importe viaja <b>ya actualizado a una fecha</b>, y esa fecha viaja con el (regla 9,
 * RNF-075). La caja no recalcula nada: imprime la cifra que le dieron. Si recalculara, el sistema
 * tendria dos verdades sobre lo que se debe.
 */
public interface OrdenesDeCobro {

    /**
     * Da de alta una orden en la caja.
     *
     * <p><b>Es idempotente por {@code (sistemaOrigen, referenciaExterna)}</b> del lado de la caja:
     * reintentar no duplica la orden y devuelve la que ya estaba. Este puerto no lo garantiza —lo
     * garantiza el otro lado, con un indice unico— y por eso lo dice aqui.
     *
     * @throws CajaInalcanzable si la caja no contesta. <b>No devuelve nada</b>: una orden que se
     *     cree emitida y no lo esta deja al contribuyente sin poder pagar en ventanilla
     */
    Emitida emitir(Peticion peticion);

    /**
     * @param referencia como este sistema nombra lo que se cobra
     * @param concepto lo que se imprime en la linea del recibo
     * @param detalle lo que se quiera anadir debajo; <b>es la puerta de D-20</b>, que sigue abierta
     * @param importe cuanto, a la fecha de {@code actualizadoA}
     * @param actualizadoA a que fecha esta el importe (regla 9)
     */
    record Peticion(
            ReferenciaDeObligacion referencia,
            String concepto,
            @Nullable String detalle,
            Dinero importe,
            LocalDate fechaExigibilidad,
            LocalDate actualizadoA,
            @Nullable String pagadorDocumento,
            @Nullable String pagadorNombre,
            long contribuyenteId) {

        public Peticion {
            Objects.requireNonNull(referencia, "La orden dice que obligacion cobra");
            Objects.requireNonNull(concepto, "La orden dice que se imprime");
            Objects.requireNonNull(importe, "La orden dice cuanto");
            Objects.requireNonNull(fechaExigibilidad, "La orden dice desde cuando se cobra");
            Objects.requireNonNull(actualizadoA, "Toda cifra indica su fecha (regla 9)");
            if (contribuyenteId <= 0) {
                throw new IllegalArgumentException("La orden es de un contribuyente del padron");
            }
        }
    }

    /**
     * @param ordenId el identificador que la caja le dio; es lo que la ventanilla marca para cobrar
     * @param nueva si se creo ahora o ya estaba
     */
    record Emitida(long ordenId, String estado, boolean nueva) {}

    /** La caja no contesta. No es «no se pudo emitir»: es que no se pudo preguntar. */
    final class CajaInalcanzable extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public CajaInalcanzable(@Nullable String mensaje, @Nullable Throwable causa) {
            super(mensaje, causa);
        }
    }
}
