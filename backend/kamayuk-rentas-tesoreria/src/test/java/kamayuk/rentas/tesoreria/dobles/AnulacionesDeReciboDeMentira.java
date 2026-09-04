package kamayuk.rentas.tesoreria.dobles;

import java.util.HashSet;
import java.util.Set;
import kamayuk.rentas.tesoreria.AnulacionesDeRecibo;

/**
 * Que recibos de {@code caja} estan anulados, en memoria (P5D).
 *
 * <p>Sustituye a {@code MovimientosEnMemoria} en las pruebas del convenio. La diferencia no es de
 * forma sino de <b>quien responde</b>: antes la anulacion era una fila de {@code recibo_movimiento}
 * en esta misma base y se sembraba anulando el recibo de verdad; desde `V7` el recibo vive en
 * {@code caja} y lo unico que este sistema puede hacer es preguntar. El doble reproduce eso: se le
 * dice que recibos estan anulados, porque es lo que la respuesta de {@code caja} diria.
 *
 * <p><b>Lo que este doble NO puede demostrar</b>, y conviene decirlo: que la anulacion del recibo
 * ocurriera de verdad, con su turno, su autorizacion y su reversion en el libro. Eso es un acto de
 * {@code caja} y se prueba en {@code caja}. Lo que si demuestra —y es lo que la guarda de {@code
 * CerrarConvenio} existe para sostener— es que anular el convenio depende de esa respuesta y no de
 * un supuesto.
 */
public final class AnulacionesDeReciboDeMentira implements AnulacionesDeRecibo {

    private final Set<Long> anulados = new HashSet<>();

    /** Como si {@code caja} hubiera registrado la anulacion de ese recibo. */
    public AnulacionesDeReciboDeMentira anular(long reciboId) {
        anulados.add(reciboId);
        return this;
    }

    @Override
    public boolean estaAnulado(long reciboId) {
        return anulados.contains(reciboId);
    }
}
