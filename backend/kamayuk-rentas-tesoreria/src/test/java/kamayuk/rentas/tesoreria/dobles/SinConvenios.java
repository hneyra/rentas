package kamayuk.rentas.tesoreria.dobles;

import java.time.Clock;
import kamayuk.rentas.tesoreria.aplicacion.FormalizarConvenio;

/**
 * Un {@link FormalizarConvenio} sobre repositorios vacios, para las pruebas que <b>no</b> cobran
 * cuotas iniciales.
 *
 * <p>{@code CobrarDeuda} lo necesita como colaborador desde #35 —el cobro de una cuota inicial va
 * por la misma ventanilla, el mismo turno y la misma transaccion que el resto—, y las pruebas de
 * #33 y #34 no lo ejercitan. Darles un doble que <b>no tiene ningun convenio</b> es mas honesto que
 * un {@code null} o que un simulacro que devuelve lo que le pidan: si alguna de esas pruebas
 * intentara formalizar algo, fallaria diciendo que ese convenio no existe, que es exactamente lo
 * que deberia pasar.
 */
public final class SinConvenios {

    private SinConvenios() {}

    /** El formalizador de un sistema sin ningun convenio registrado. */
    public static FormalizarConvenio formalizador(Clock reloj) {
        return new FormalizarConvenio(
                new ConveniosEnMemoria(),
                new MovimientosDeConvenioEnMemoria(),
                new AcogimientoDeMentira(),
                registro -> {},
                reloj);
    }
}
