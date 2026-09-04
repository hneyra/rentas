package kamayuk.rentas.tesoreria.dominio;

import java.util.List;

/**
 * Los cierres de turno y sus reversiones (V32, #36). Ningun metodo recibe la municipalidad (regla
 * 2): la filtra la politica RLS.
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}</b>, y no es un olvido: V32 le concede a {@code
 * sgtm_app} solo {@code SELECT} e {@code INSERT}. Un cierre equivocado se reversa (regla 4).
 */
public interface CierreDeTurnoRepository {

    /**
     * Registra el cierre o su reversion, con el arqueo congelado si es un cierre.
     *
     * @throws TurnoYaTieneEseMovimiento si otra peticion escribio la misma secuencia. Lo decide la
     *     base —{@code cierre_turno_secuencia_uq}—, no un {@code SELECT} previo: dos peticiones
     *     simultaneas de cierre calculan la misma secuencia, y dos cierres del mismo turno dejarian
     *     dos arqueos vigentes sobre el mismo dinero
     */
    CierreDeTurno registrar(CierreDeTurno movimiento);

    /** Los movimientos del turno, del primero al ultimo. Es de donde sale su estado. */
    List<CierreDeTurno> deTurno(long turnoId);

    /** Los recibos del turno, con lo que su anulacion devolvio si la hubo. */
    List<ReciboDelTurno> recibosDelTurno(long turnoId);

    /**
     * Ese turno ya tenia un movimiento con esa secuencia.
     *
     * <p>Dos cierres simultaneos, o una reversion registrada dos veces. No es un reenvio inocente
     * que deba devolver lo de la primera vez: el estado del turno ya cambio, y quien llama responde
     * 409.
     */
    final class TurnoYaTieneEseMovimiento extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public TurnoYaTieneEseMovimiento(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
