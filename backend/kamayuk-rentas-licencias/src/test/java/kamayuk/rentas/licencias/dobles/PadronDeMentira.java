package kamayuk.rentas.licencias.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;

/**
 * Lo minimo del directorio que la licencia necesita: resolver el codigo del titular y su nombre.
 *
 * <p>{@link #buscar} filtra <b>de verdad</b> por el texto, y no es un detalle: la prueba de que un
 * nombre inexistente devuelve la pagina vacia —en vez del padron entero— necesita un doble que
 * pueda devolver nada.
 */
public final class PadronDeMentira implements DirectorioDeContribuyentes {

    private final Map<String, ResumenDeContribuyente> porCodigo = new LinkedHashMap<>();

    public PadronDeMentira con(ResumenDeContribuyente contribuyente) {
        porCodigo.put(contribuyente.codigo(), contribuyente);
        return this;
    }

    @Override
    public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
        String buscado = texto.strip().toUpperCase(Locale.ROOT);
        List<ResumenDeContribuyente> encontrados = new ArrayList<>();
        for (ResumenDeContribuyente contribuyente : porCodigo.values()) {
            if (contribuyente.nombre().toUpperCase(Locale.ROOT).contains(buscado)) {
                encontrados.add(contribuyente);
            }
        }
        return encontrados.size() > maximo ? encontrados.subList(0, maximo) : encontrados;
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
