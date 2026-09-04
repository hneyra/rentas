package kamayuk.rentas.valores.aplicacion;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.valores.dominio.CriterioDePrescripciones;
import kamayuk.rentas.valores.dominio.PrescripcionEnLista;
import kamayuk.rentas.valores.dominio.PrescripcionRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La relacion de prescripciones declaradas: que deuda quedo sin accion de cobro (#674, RF-094).
 *
 * <h2>Para que existe, y por que la decision de #674 la necesita</h2>
 *
 * <p>Declarar la prescripcion <b>no toca el libro</b> y la deuda sigue siendo cartera pendiente y
 * emision del ejercicio hasta que la administracion la de de baja (RF-044). El razonamiento entero
 * esta en {@link DeclararPrescripcion} y en {@code ActoDelLibro}. La contrapartida de esa decision
 * es que la prescripcion tiene que <b>verse</b> en alguna parte: hasta aqui, el unico rastro de una
 * declaracion era la fila que la escribio y el estado {@code PRESCRITO} de los valores que alcanzo,
 * y ninguna lectura la publicaba. Quien audita la cartera no tenia como saber que parte de ella ya
 * no se puede exigir, y quien registra la baja de RF-044 —cuya primera causal se llama literalmente
 * «PRESCRIPCIÓN DECLARADA»— no tenia donde encontrar la declaracion que la sustenta.
 *
 * <h2>Por que es un caso de uso y no un passthrough del controlador</h2>
 *
 * <p>Por {@code @Transactional(readOnly = true)}: sin transaccion no hay {@code SET LOCAL}, y sin
 * el la politica RLS no puede evaluar {@code current_setting('app.municipalidad_id')} —la consulta
 * no devuelve vacio, <b>falla</b> con 500 (#486)—. Y ademas las dos lecturas tienen que ir juntas:
 * la pagina de declaraciones y los nombres de sus contribuyentes se resuelven en la misma
 * transaccion, o la relacion puede nombrar a alguien que se renombro entre una lectura y la otra.
 */
@Service
public class ConsultaDePrescripciones {

    private final PrescripcionRepository repositorio;
    private final DirectorioDeContribuyentes padron;

    public ConsultaDePrescripciones(
            PrescripcionRepository repositorio, DirectorioDeContribuyentes padron) {
        this.repositorio = repositorio;
        this.padron = padron;
    }

    /**
     * Resuelve el codigo de contribuyente que escribio el usuario.
     *
     * <p>Vacio si el codigo no existe. Quien llama decide que hacer con eso; aqui no se traduce a
     * una pagina vacia, porque «no hay nadie con ese codigo» y «esa persona no tiene ninguna
     * declaracion» son dos respuestas distintas.
     */
    @Transactional(readOnly = true)
    public Optional<ResumenDeContribuyente> contribuyentePorCodigo(String codigo) {
        return padron.porCodigo(codigo.strip());
    }

    /** La pagina de declaraciones que pide el criterio, con el nombre de cada contribuyente. */
    @Transactional(readOnly = true)
    public Pagina<FilaDePrescripcion> buscar(
            CriterioDePrescripciones criterio, Paginacion paginacion) {

        Pagina<PrescripcionEnLista> pagina = repositorio.buscar(criterio, paginacion);
        if (pagina.estaVacia()) {
            return pagina.mapear(fila -> new FilaDePrescripcion(fila, null));
        }

        Set<Long> ids = new LinkedHashSet<>();
        for (PrescripcionEnLista fila : pagina.contenido()) {
            ids.add(fila.contribuyenteId());
        }
        Map<Long, ResumenDeContribuyente> resumenes = padron.porIds(ids);

        return pagina.mapear(
                fila -> new FilaDePrescripcion(fila, resumenes.get(fila.contribuyenteId())));
    }

    /**
     * Una fila de la relacion y a quien se le declaro.
     *
     * <p>{@code contribuyente} nulo significa que el padron no devolvio ese identificador. La fila
     * sale igual: es justo la que hay que revisar, y esconderla no la arregla.
     */
    public record FilaDePrescripcion(
            PrescripcionEnLista prescripcion, @Nullable ResumenDeContribuyente contribuyente) {}
}
