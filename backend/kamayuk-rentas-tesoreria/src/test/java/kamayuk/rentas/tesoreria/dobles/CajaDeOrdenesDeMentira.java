package kamayuk.rentas.tesoreria.dobles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.tesoreria.pagos.OrdenesDeCobro;

/**
 * La caja, de mentira, con la idempotencia que la de verdad sostiene con un indice unico (P5D).
 *
 * <p>La clave es {@code (sistemaOrigen, referenciaExterna)} y aqui el sistema de origen es siempre
 * el mismo, asi que basta la referencia. Reintentar devuelve la orden que ya estaba con {@code
 * nueva = false}, que es exactamente lo que {@code orden_referencia_uq} hace al otro lado.
 */
public final class CajaDeOrdenesDeMentira implements OrdenesDeCobro {

    private final Map<String, Long> porReferencia = new LinkedHashMap<>();
    private final List<Peticion> recibidas = new ArrayList<>();
    private long siguienteId = 900_001L;
    private boolean apagada;

    /** La caja deja de contestar: la orden no se puede emitir y nadie finge que si. */
    public CajaDeOrdenesDeMentira apagar() {
        apagada = true;
        return this;
    }

    public List<Peticion> recibidas() {
        return List.copyOf(recibidas);
    }

    @Override
    public Emitida emitir(Peticion peticion) {
        if (apagada) {
            throw new CajaInalcanzable("la caja de mentira esta apagada", null);
        }
        recibidas.add(peticion);
        String referencia = peticion.referencia().texto();
        Long yaEstaba = porReferencia.get(referencia);
        if (yaEstaba != null) {
            return new Emitida(yaEstaba, "PENDIENTE", false);
        }
        long id = siguienteId++;
        porReferencia.put(referencia, id);
        return new Emitida(id, "PENDIENTE", true);
    }
}
