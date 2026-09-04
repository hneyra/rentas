package kamayuk.rentas.tesoreria.dominio;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * En que situacion esta un turno de caja. <b>Se deriva</b>, no se guarda (V32, #36).
 *
 * <p>V3 le habia puesto a {@code cierre_caja} una columna {@code estado} con {@code DEFAULT
 * 'ABIERTO'}. V32 la retira por el mismo motivo que V30 retiro {@code recibo.estado} y V31 {@code
 * convenio.estado}: el turno no se actualiza —{@code REVOKE UPDATE}—, asi que esa columna diria
 * ABIERTO para siempre, tambien para un turno cerrado, y cualquier consulta ad hoc la leeria como
 * la verdad. El estado sale ahora de {@code cierre_turno}: hay cierre vigente o no lo hay.
 *
 * <p>Contra un turno cerrado <b>no se cobra</b>: su arqueo esta firmado y ese dinero no estaria en
 * el. Volver a cobrar ese dia exige reversar el cierre, que es lo que reabre el turno —«abrir otro»
 * no existe, {@code cierre_uq} lo hace unico por (caja, cajero, fecha)—.
 */
public enum EstadoDeTurno {
    ABIERTO,
    CERRADO;

    /**
     * El estado despues de esa historia de movimientos.
     *
     * @param historia los movimientos del turno, del primero al ultimo
     */
    public static EstadoDeTurno deLosMovimientos(List<CierreDeTurno> historia) {
        return trasElUltimoMovimiento(
                historia.isEmpty() ? null : historia.get(historia.size() - 1).tipo());
    }

    /**
     * El estado sabiendo solo cual fue el <b>ultimo</b> movimiento del turno.
     *
     * <p>Es lo que el repositorio puede leer barato —una fila, por {@code cierre_turno_turno_ix}—
     * sin traerse el historial entero cada vez que una cobranza bloquea su turno.
     *
     * @param ultimo el tipo del ultimo movimiento; nulo si el turno no tiene ninguno
     */
    public static EstadoDeTurno trasElUltimoMovimiento(@Nullable TipoDeMovimientoDeTurno ultimo) {
        return ultimo != null && ultimo.cierra() ? CERRADO : ABIERTO;
    }
}
