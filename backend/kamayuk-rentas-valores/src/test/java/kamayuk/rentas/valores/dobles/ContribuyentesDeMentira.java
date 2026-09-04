package kamayuk.rentas.valores.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;

/**
 * Un {@link DirectorioDeContribuyentes} con domicilios <b>por fecha</b>.
 *
 * <p>Los domicilios se declaran con su fecha de inicio y se resuelve el vigente a la fecha que se
 * pida, no el ultimo: es la diferencia que #15 dejo escrita y de la que depende que una
 * notificacion de marzo no se diligencie en la direccion a la que el contribuyente se mudo en
 * setiembre.
 */
public final class ContribuyentesDeMentira implements DirectorioDeContribuyentes {

    private final Map<String, ResumenDeContribuyente> porCodigo = new LinkedHashMap<>();
    private final Map<Long, TreeMap<LocalDate, String>> domicilios = new LinkedHashMap<>();

    public ContribuyentesDeMentira con(ResumenDeContribuyente contribuyente) {
        porCodigo.put(contribuyente.codigo(), contribuyente);
        return this;
    }

    /** Declara que desde esa fecha el domicilio fiscal es ese. */
    public ContribuyentesDeMentira conDomicilio(long id, LocalDate desde, String direccion) {
        domicilios.computeIfAbsent(id, k -> new TreeMap<>()).put(desde, direccion);
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
        TreeMap<LocalDate, String> suyos = domicilios.get(contribuyenteId);
        if (suyos == null) {
            return Optional.empty();
        }
        Map.Entry<LocalDate, String> vigente = suyos.floorEntry(fecha);
        return vigente == null ? Optional.empty() : Optional.of(vigente.getValue());
    }
}
