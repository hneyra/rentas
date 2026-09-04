package kamayuk.rentas.catastro.aplicacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.Predio;
import kamayuk.rentas.catastro.dominio.Titularidad;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementacion de {@link PrediosDelContribuyente}. */
@Service
public class PrediosDelContribuyenteCatastro implements PrediosDelContribuyente {

    private final CatastroRepository repositorio;

    public PrediosDelContribuyenteCatastro(CatastroRepository repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
        List<Titularidad> titularidades = repositorio.prediosDe(contribuyenteId, fecha);

        // Cuanto suma la titularidad ENTERA de cada predio, no solo la cuota de quien pregunta
        // (#690). En una sola consulta para todos: `de` ya hace una por predio para leerlo, y
        // añadir otra por predio doblaria el coste de la corrida masiva del predial.
        //
        // Aqui «vigente» es «vigente a la fecha», y no «cuota abierta» como en el censo del
        // padron: son dos preguntas distintas y las dos son correctas. El censo responde «que
        // esta incompleto HOY» y por eso suma lo mismo que `titularidad_no_excede_trg`; esto
        // responde «cuanto habia registrado el dia que se determina», que es la unica fecha con
        // la que una determinacion se puede recalcular igual dentro de diez anios (regla 6).
        Set<Long> predios = new LinkedHashSet<>();
        for (Titularidad titularidad : titularidades) {
            predios.add(titularidad.predioId());
        }
        Map<Long, List<Titularidad>> todasLasCuotas = repositorio.titularesDeVarios(predios, fecha);

        List<PredioDelContribuyente> resultado = new ArrayList<>();
        for (Titularidad titularidad : titularidades) {
            Predio predio =
                    repositorio
                            .predio(titularidad.predioId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "La titularidad "
                                                            + titularidad.id()
                                                            + " referencia un predio que no"
                                                            + " existe: "
                                                            + titularidad.predioId()));
            resultado.add(
                    new PredioDelContribuyente(
                            Objects.requireNonNull(
                                    predio.id(), "un predio leido de la base tiene id"),
                            predio.codigo().valor(),
                            predio.tipo().name(),
                            predio.direccion(),
                            titularidad.porcentaje(),
                            sumaDe(todasLasCuotas.get(titularidad.predioId()))));
        }
        return resultado;
    }

    /** Lo que suman las cuotas de un predio; cero si no hay ninguna. */
    private static Porcentaje sumaDe(@Nullable List<Titularidad> cuotas) {
        if (cuotas == null || cuotas.isEmpty()) {
            return new Porcentaje(BigDecimal.ZERO);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Titularidad cuota : cuotas) {
            total = total.add(cuota.porcentaje().valor());
        }
        return new Porcentaje(total);
    }
}
