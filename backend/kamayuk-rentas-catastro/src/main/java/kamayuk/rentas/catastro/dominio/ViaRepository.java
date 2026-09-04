package kamayuk.rentas.catastro.dominio;

import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * Puerto de persistencia del catalogo vial.
 *
 * <p>La interfaz vive en el dominio y la implementacion en {@code infraestructura}: asi la
 * dependencia apunta hacia el dominio y no desde el (ARQ-04 §1), y las reglas se pueden probar con
 * una implementacion en memoria sin levantar Spring ni Docker.
 *
 * <p>Nombre y metodos en <b>ingles</b>: es un patron, no vocabulario tributario (ARQ-04 §3).
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2). Lo que se ve desde aqui es lo que la politica
 * RLS deje ver, con el contexto que {@code TenantTransactionManager} fijo al abrir la transaccion.
 */
public interface ViaRepository {

    Optional<Via> findById(long id);

    Optional<Via> findByCodigo(String codigo);

    /** El catalogo entero, paginado. Es {@link #buscar} sin acotar. */
    default Pagina<Via> findAll(Paginacion paginacion) {
        return buscar(CriterioDeVia.todas(), paginacion);
    }

    /**
     * Las vias que pide el criterio (#565).
     *
     * <p>Es la unica puerta: {@link #findAll} delega aqui para que no haya dos consultas que puedan
     * apartarse la una de la otra.
     */
    Pagina<Via> buscar(CriterioDeVia criterio, Paginacion paginacion);

    /** Inserta la via nueva o actualiza la existente, y devuelve la via con su identificador. */
    Via save(Via via);
}
