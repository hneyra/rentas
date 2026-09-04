package kamayuk.rentas.sanciones.dominio;

import java.util.List;

/**
 * En qué situación está un vehículo del depósito. <b>Se deriva de los movimientos</b>, nunca se
 * escribe (V41 §5).
 *
 * <p>Los tres valores son los del desplegable «Estado» de la pantalla {@code internamiento}. Que
 * sea una función de la lista de movimientos y no una columna es lo que hace imposible que el
 * estado y la traza digan cosas distintas: es el mismo patrón que {@code EstadoDelExpediente} (V33)
 * y {@code EstadoDeConvenio} (V31).
 */
public enum EstadoDeInternamiento {
    INTERNADO,
    LIBERADO,
    EN_ABANDONO;

    /**
     * El estado que produce ese historial.
     *
     * <p>La liberación gana sobre el abandono aunque se registre antes: un vehículo declarado en
     * abandono y luego entregado a su titular <b>salió del depósito</b>, y decir que sigue en
     * abandono sería seguir devengando custodia por un vehículo que ya no está.
     */
    public static EstadoDeInternamiento delHistorial(List<MovimientoDeInternamiento> movimientos) {
        EstadoDeInternamiento estado = INTERNADO;
        for (MovimientoDeInternamiento movimiento : movimientos) {
            if (movimiento.tipo() == TipoDeMovimientoDeInternamiento.LIBERACION) {
                return LIBERADO;
            }
            estado = EN_ABANDONO;
        }
        return estado;
    }

    /** Si el vehículo sigue en el depósito. */
    public boolean sigueEnDeposito() {
        return this != LIBERADO;
    }
}
