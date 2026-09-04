package kamayuk.rentas.contribuyentes.dominio;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.CodigoContribuyente;
import kamayuk.rentas.dominio.DocumentoIdentidad;

/**
 * El padron de contribuyentes.
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2): sale del token y la aplica la politica RLS.
 *
 * <p><b>No hay {@code delete}.</b> Un contribuyente se da de baja; su codigo aparece en recibos ya
 * emitidos y en asientos del libro (RNF-051).
 */
public interface ContribuyenteRepository {

    Optional<Contribuyente> findById(long id);

    /**
     * Varios por identificador, en una sola consulta.
     *
     * <p>Lo pide {@code DirectorioDeContribuyentes.porIds}: una grilla de otro contexto resuelve
     * los titulares de una pagina entera de golpe. Con {@code findById} en un bucle no se nota en
     * la prueba y si en el padron de una provincia.
     */
    List<Contribuyente> findAllById(Collection<Long> ids);

    Optional<Contribuyente> findByCodigo(CodigoContribuyente codigo);

    Optional<Contribuyente> findByDocumento(DocumentoIdentidad documento);

    /**
     * Busca por los criterios dados. Si el criterio trae nombre, el resultado viene <b>ordenado por
     * parecido</b> y no por el campo que pida la paginacion: en una busqueda por aproximacion, el
     * orden alfabetico esconde la fila que se buscaba en la pagina cuatro.
     */
    Pagina<Contribuyente> buscar(CriterioDeBusqueda criterio, Paginacion paginacion);

    Contribuyente save(Contribuyente contribuyente);
}
