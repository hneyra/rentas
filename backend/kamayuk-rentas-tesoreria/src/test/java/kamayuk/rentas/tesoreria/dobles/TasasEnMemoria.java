package kamayuk.rentas.tesoreria.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import kamayuk.rentas.tesoreria.dominio.Tasa;
import kamayuk.rentas.tesoreria.dominio.TasaRepository;

/** Las tarifas del TUPA, en memoria, resolviendo la vigente igual que la consulta real. */
public final class TasasEnMemoria implements TasaRepository {

    private final List<Tasa> tasas = new ArrayList<>();

    public TasasEnMemoria con(Tasa tasa) {
        tasas.add(tasa);
        return this;
    }

    @Override
    public Optional<Tasa> vigenteA(String codigo, LocalDate fecha) {
        String buscado = codigo.strip().toUpperCase(Locale.ROOT);
        return tasas.stream()
                .filter(t -> t.codigo().equals(buscado) && t.vigenteA(fecha))
                .max(Comparator.comparing(Tasa::vigenciaDesde));
    }
}
