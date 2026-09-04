package kamayuk.rentas.tesoreria.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;

/** Lo minimo del directorio que la caja necesita: resolver un codigo a su identificador. */
public final class ContribuyentesDeMentira implements DirectorioDeContribuyentes {

    private final Map<String, ResumenDeContribuyente> porCodigo = new LinkedHashMap<>();

    public ContribuyentesDeMentira con(ResumenDeContribuyente contribuyente) {
        porCodigo.put(contribuyente.codigo(), contribuyente);
        return this;
    }

    @Override
    public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
        return new ArrayList<>(porCodigo.values());
    }

    @Override
    public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
        return Optional.ofNullable(porCodigo.get(codigo));
    }

    @Override
    public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
        Map<Long, ResumenDeContribuyente> encontrados = new LinkedHashMap<>();
        for (ResumenDeContribuyente contribuyente : porCodigo.values()) {
            if (ids.contains(contribuyente.id())) {
                encontrados.put(contribuyente.id(), contribuyente);
            }
        }
        return encontrados;
    }

    @Override
    public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
        return Optional.empty();
    }
}
