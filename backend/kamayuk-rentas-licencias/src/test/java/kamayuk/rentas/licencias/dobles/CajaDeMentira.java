package kamayuk.rentas.licencias.dobles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import kamayuk.rentas.tesoreria.RecibosDeTramite;

/**
 * Un {@link RecibosDeTramite} con los recibos que la prueba le siembra.
 *
 * <p>Es el <b>puerto publico de tesoreria</b>, no su tabla, y ese es justamente el punto: si esta
 * prueba pudiera montar un doble del repositorio de recibos en vez de este, seria porque {@code
 * licencias} conoce {@code tesoreria.dominio}, que es lo que el AC de #44 prohibe y lo que Spring
 * Modulith verifica.
 */
public final class CajaDeMentira implements RecibosDeTramite {

    private final List<ReciboDeTramite> recibos = new ArrayList<>();

    public CajaDeMentira con(ReciboDeTramite recibo) {
        recibos.add(recibo);
        return this;
    }

    @Override
    public Optional<ReciboDeTramite> porNumeroImpreso(String numeroImpreso) {
        String buscado = numeroImpreso == null ? "" : numeroImpreso.strip();
        return recibos.stream().filter(recibo -> recibo.numero().equals(buscado)).findFirst();
    }
}
