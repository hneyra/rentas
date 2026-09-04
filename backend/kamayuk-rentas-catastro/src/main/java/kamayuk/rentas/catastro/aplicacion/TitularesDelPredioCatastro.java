package kamayuk.rentas.catastro.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kamayuk.rentas.catastro.TitularDelPredio;
import kamayuk.rentas.catastro.TitularesDelPredio;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.Titularidad;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementacion de {@link TitularesDelPredio} (#366, ADR-0015 §2.4).
 *
 * <p>Es una proyeccion y nada mas: la vigencia a la fecha la resuelve el repositorio, que ya sabe
 * hacerlo para {@code PrediosDelContribuyente} y para la grilla, y duplicarla aqui —o resolver «la
 * ultima» filtrando en memoria— es el defecto que la ficha del contribuyente (#24) ya pago una vez.
 */
@Service
public class TitularesDelPredioCatastro implements TitularesDelPredio {

    private final CatastroRepository repositorio;

    public TitularesDelPredioCatastro(CatastroRepository repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TitularDelPredio> de(long predioId, LocalDate fecha) {
        Objects.requireNonNull(fecha, "De quien es el predio se pregunta a una fecha (regla 9)");
        return proyectar(repositorio.titularesDe(predioId, fecha));
    }

    /**
     * Si el identificador apunta a una fila del padron de esta municipalidad (#680).
     *
     * <p>{@code CatastroRepository.predio(long)} y no una consulta propia: bajo RLS esa lectura ya
     * acota por municipalidad, y <b>no mira el estado</b>, que es lo que aqui se quiere — un predio
     * dado de baja sigue estando en el padron y su deuda se tiene que poder mover.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean estaEnElPadron(long predioId) {
        return repositorio.predio(predioId).isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, List<TitularDelPredio>> deVarios(Collection<Long> predioIds, LocalDate fecha) {
        Objects.requireNonNull(predioIds, "La lista de predios es vacia, no nula");
        Objects.requireNonNull(fecha, "De quien es el predio se pregunta a una fecha (regla 9)");

        Map<Long, List<TitularDelPredio>> cuotas = new HashMap<>();
        repositorio
                .titularesDeVarios(predioIds, fecha)
                .forEach(
                        (predioId, titularidades) ->
                                cuotas.put(predioId, proyectar(titularidades)));
        return Map.copyOf(cuotas);
    }

    private static List<TitularDelPredio> proyectar(List<Titularidad> titularidades) {
        List<TitularDelPredio> cuotas = new ArrayList<>();
        for (Titularidad titularidad : titularidades) {
            cuotas.add(
                    new TitularDelPredio(
                            titularidad.contribuyenteId(),
                            titularidad.condicion().name(),
                            titularidad.porcentaje()));
        }
        return List.copyOf(cuotas);
    }
}
