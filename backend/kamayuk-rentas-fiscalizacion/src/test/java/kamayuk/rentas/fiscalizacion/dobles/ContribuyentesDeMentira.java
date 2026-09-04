package kamayuk.rentas.fiscalizacion.dobles;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;

/** El padron de contribuyentes, en memoria: lo justo para poner un nombre en el papel. */
public final class ContribuyentesDeMentira implements DirectorioDeContribuyentes {

    private final Map<Long, ResumenDeContribuyente> porId = new HashMap<>();
    private final Map<Long, String> domicilios = new HashMap<>();

    public ContribuyentesDeMentira con(long id, String codigo, String nombre, String domicilio) {
        porId.put(id, new ResumenDeContribuyente(id, codigo, nombre, "DNI 12345678"));
        domicilios.put(id, domicilio);
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
        return Optional.ofNullable(domicilios.get(contribuyenteId));
    }
}
