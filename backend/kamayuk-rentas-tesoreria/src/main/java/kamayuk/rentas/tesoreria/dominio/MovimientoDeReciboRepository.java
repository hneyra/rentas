package kamayuk.rentas.tesoreria.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Los movimientos de un recibo (V30, #34). Ningun metodo recibe la municipalidad (regla 2): la
 * filtra la politica RLS.
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}</b>, y no es un olvido: V30 le concede a {@code
 * sgtm_app} solo {@code SELECT} e {@code INSERT}. Lo que le pasa a un recibo se agrega.
 */
public interface MovimientoDeReciboRepository {

    /**
     * Registra el movimiento.
     *
     * @throws ReciboYaAnulado si el recibo ya tenia su anulacion. Lo decide la base —{@code
     *     recibo_movimiento_anulacion_uq}—, no un {@code SELECT} previo: dos peticiones simultaneas
     *     pasarian las dos por cualquier comprobacion escrita en Java, y anular dos veces
     *     reversaria dos veces, dejando al contribuyente debiendo el doble
     */
    MovimientoDeRecibo registrar(MovimientoDeRecibo movimiento);

    /** La anulacion de ese recibo, si la hubo. Es de donde sale su estado efectivo. */
    Optional<MovimientoDeRecibo> anulacionDe(long reciboId);

    /** Todos los movimientos del recibo, del primero al ultimo. */
    List<MovimientoDeRecibo> deRecibo(long reciboId);

    /**
     * Cuantos duplicados se han sacado ya de ese recibo.
     *
     * <p>Es lo que numera el siguiente —{@code DUPLICADO N.° 3}— y lo que la pantalla muestra en su
     * columna «Duplicados». Un contador aparte en {@code recibo} no valdria: seria una columna que
     * hay que actualizar, y el recibo no se actualiza.
     */
    long duplicadosDe(long reciboId);

    /**
     * Ese recibo ya estaba anulado.
     *
     * <p>No es un reenvio inocente que deba devolver lo de la primera vez —esa es la idempotencia
     * del pase a coactiva—, es una peticion que el estado ya no admite: la deuda volvio a estar
     * pendiente y una segunda reversion la duplicaria. Quien llama responde 409.
     */
    final class ReciboYaAnulado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ReciboYaAnulado(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
