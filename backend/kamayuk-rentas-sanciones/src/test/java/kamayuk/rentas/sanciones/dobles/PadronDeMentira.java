package kamayuk.rentas.sanciones.dobles;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;

/**
 * El padron de contribuyentes, en memoria: los identificadores que se le siembran y ninguno mas.
 *
 * <p>Existe por #422: un caso de uso que pregunta si el contribuyente citado existe necesita un
 * padron que pueda decir que no. Uno que contestara a todo que si ocultaria justo el rechazo que se
 * quiere medir.
 */
public final class PadronDeMentira implements DirectorioDeContribuyentes {

    private final Map<Long, ResumenDeContribuyente> porId = new HashMap<>();

    public PadronDeMentira con(long... ids) {
        for (long id : ids) {
            porId.put(id, new ResumenDeContribuyente(id, "C-" + id, "CONTRIBUYENTE " + id, "DNI"));
        }
        return this;
    }

    @Override
    public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
        return List.copyOf(porId.values());
    }

    @Override
    public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
        return porId.values().stream().filter(r -> r.codigo().equals(codigo)).findFirst();
    }

    @Override
    public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
        Map<Long, ResumenDeContribuyente> encontrados = new HashMap<>();
        for (Long id : ids) {
            ResumenDeContribuyente resumen = porId.get(id);
            if (resumen != null) {
                encontrados.put(id, resumen);
            }
        }
        return encontrados;
    }

    @Override
    public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
        return Optional.empty();
    }
}
