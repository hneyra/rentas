package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * El catálogo de códigos de infracción (#43). Ningún método recibe la municipalidad (regla 2): sale
 * del token y la aplica la política RLS.
 *
 * <p><b>No hay {@code delete}.</b> Modificar un código cierra la versión vigente con {@link
 * CodigoInfraccion#cerradoEl} —guardado con {@link #actualizar}— e inserta la versión nueva con
 * {@link #insertar}; la anterior queda (regla 4).
 */
public interface CodigoInfraccionRepository {

    /**
     * Una versión del código por su identificador, esté vigente o cerrada. Vacío si no existe o es
     * de otra municipalidad.
     */
    Optional<CodigoInfraccion> findById(long id);

    /**
     * La versión de ese código, de esa familia, vigente en esa fecha — no «la última» (regla 9).
     */
    Optional<CodigoInfraccion> vigenteA(Familia familia, String codigo, LocalDate fecha);

    /**
     * El catálogo de una familia, paginado; sin {@code vigenteA} trae también las versiones
     * cerradas, que son las que explican las papeletas de antes.
     */
    Pagina<CodigoInfraccion> buscar(CriterioDeCodigoInfraccion criterio, Paginacion paginacion);

    /**
     * Inserta una versión del código y la devuelve con su identificador: la primera, o la que
     * sucede a la que se acaba de cerrar con {@link #actualizar}.
     */
    CodigoInfraccion insertar(CodigoInfraccion codigoInfraccion);

    /** Guarda el cierre de una versión: la única escritura que admite un código ya guardado. */
    CodigoInfraccion actualizar(CodigoInfraccion codigoInfraccion);
}
