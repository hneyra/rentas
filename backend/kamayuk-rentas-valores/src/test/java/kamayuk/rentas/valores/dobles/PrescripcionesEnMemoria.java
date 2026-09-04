package kamayuk.rentas.valores.dobles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.valores.dominio.ComputoDeEjercicio;
import kamayuk.rentas.valores.dominio.CriterioDePrescripciones;
import kamayuk.rentas.valores.dominio.Prescripcion;
import kamayuk.rentas.valores.dominio.PrescripcionEnLista;
import kamayuk.rentas.valores.dominio.PrescripcionRepository;

/** Un {@link PrescripcionRepository} en memoria, para las pruebas de los casos de uso de #39. */
public final class PrescripcionesEnMemoria implements PrescripcionRepository {

    private final List<Prescripcion> guardadas = new ArrayList<>();
    private long siguienteId = 1;

    @Override
    public Prescripcion insertar(Prescripcion prescripcion) {
        Prescripcion conId =
                new Prescripcion(
                        siguienteId++,
                        prescripcion.contribuyenteId(),
                        prescripcion.tributo(),
                        prescripcion.ejercicioDesde(),
                        prescripcion.ejercicioHasta(),
                        prescripcion.fechaPresentacion(),
                        prescripcion.causal(),
                        prescripcion.plazo(),
                        prescripcion.conjuntoId(),
                        prescripcion.resultado(),
                        prescripcion.resolucion(),
                        prescripcion.ejercicios(),
                        prescripcion.hechos(),
                        "prueba",
                        prescripcion.observacion());
        guardadas.add(conId);
        return conId;
    }

    @Override
    public Optional<Prescripcion> porId(long id) {
        return guardadas.stream().filter(p -> p.id() != null && p.id() == id).findFirst();
    }

    /**
     * La relacion, filtrada en memoria con los mismos cuatro criterios que el SQL.
     *
     * <p>Sin paginar de verdad: las pruebas que la usan tienen dobles y no miden la paginacion, que
     * se mide contra PostgreSQL con el {@code OrdenSeguro} real.
     */
    @Override
    public Pagina<PrescripcionEnLista> buscar(
            CriterioDePrescripciones criterio, Paginacion paginacion) {

        List<PrescripcionEnLista> filas =
                guardadas.stream()
                        .filter(p -> cumple(p, criterio))
                        .map(PrescripcionesEnMemoria::aFila)
                        .toList();
        return Pagina.de(filas, paginacion, filas.size());
    }

    private static boolean cumple(Prescripcion prescripcion, CriterioDePrescripciones criterio) {
        if (criterio.contribuyenteId() != null
                && prescripcion.contribuyenteId() != criterio.contribuyenteId()) {
            return false;
        }
        if (criterio.tributo() != null && !prescripcion.tributo().equals(criterio.tributo())) {
            return false;
        }
        if (criterio.ejercicio() != null
                && (prescripcion.ejercicioDesde().valor() > criterio.ejercicio()
                        || prescripcion.ejercicioHasta().valor() < criterio.ejercicio())) {
            return false;
        }
        return criterio.resultado() == null || prescripcion.resultado() == criterio.resultado();
    }

    private static PrescripcionEnLista aFila(Prescripcion prescripcion) {
        List<Ejercicio> prescritos =
                prescripcion.ejercicios().stream()
                        .filter(ComputoDeEjercicio::prescrita)
                        .map(ComputoDeEjercicio::ejercicio)
                        .toList();
        Long id = prescripcion.id();
        return new PrescripcionEnLista(
                id == null ? 0L : id,
                prescripcion.contribuyenteId(),
                prescripcion.tributo(),
                prescripcion.ejercicioDesde(),
                prescripcion.ejercicioHasta(),
                prescripcion.fechaPresentacion(),
                prescripcion.causal(),
                prescripcion.plazo(),
                prescripcion.resultado(),
                prescripcion.resolucion(),
                prescritos,
                "prueba",
                prescripcion.observacion().texto());
    }
}
