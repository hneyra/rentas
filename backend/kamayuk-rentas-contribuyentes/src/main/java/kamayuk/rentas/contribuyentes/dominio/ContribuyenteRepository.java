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

    /** Un contribuyente por su identificador. Vacio si no existe o es de otra municipalidad. */
    Optional<Contribuyente> findById(long id);

    /**
     * Varios por identificador, en una sola consulta.
     *
     * <p>Lo pide {@code DirectorioDeContribuyentes.porIds}: una grilla de otro contexto resuelve
     * los titulares de una pagina entera de golpe. Con {@code findById} en un bucle no se nota en
     * la prueba y si en el padron de una provincia.
     */
    List<Contribuyente> findAllById(Collection<Long> ids);

    /**
     * Un contribuyente por su codigo <b>entero</b>, el que sale en los recibos. Buscar por un trozo
     * del codigo es {@link #buscar}, que lo lee como prefijo.
     */
    Optional<Contribuyente> findByCodigo(CodigoContribuyente codigo);

    /**
     * Un contribuyente por su documento de identidad, tipo y numero. Hay a lo sumo uno: lo asegura
     * {@code contribuyente_documento_uq} en la base.
     */
    Optional<Contribuyente> findByDocumento(DocumentoIdentidad documento);

    /**
     * Busca por los criterios dados. Si el criterio trae nombre, el resultado viene <b>ordenado por
     * parecido</b> y no por el campo que pida la paginacion: en una busqueda por aproximacion, el
     * orden alfabetico esconde la fila que se buscaba en la pagina cuatro.
     */
    Pagina<Contribuyente> buscar(CriterioDeBusqueda criterio, Paginacion paginacion);

    /**
     * Da de alta al contribuyente si es nuevo —y lo devuelve con su identificador—, o reescribe su
     * ficha si ya existe. No comprueba repetidos: eso lo hace {@code RegistrarContribuyente} antes,
     * con {@link #findByCodigo} y {@link #findByDocumento}, y lo que dos altas simultaneas cuelen
     * entre esa consulta y el {@code INSERT} lo para la base con {@code contribuyente_codigo_uq} y
     * {@code contribuyente_documento_uq}, que llega como {@code DuplicateKeyException}.
     */
    Contribuyente save(Contribuyente contribuyente);
}
