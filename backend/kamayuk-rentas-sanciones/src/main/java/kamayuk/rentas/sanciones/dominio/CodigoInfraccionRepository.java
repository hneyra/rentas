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

    Optional<CodigoInfraccion> findById(long id);

    /**
     * La versión de ese código, de esa familia, vigente en esa fecha — no «la última» (regla 9).
     */
    Optional<CodigoInfraccion> vigenteA(Familia familia, String codigo, LocalDate fecha);

    Pagina<CodigoInfraccion> buscar(CriterioDeCodigoInfraccion criterio, Paginacion paginacion);

    CodigoInfraccion insertar(CodigoInfraccion codigoInfraccion);

    /** Guarda el cierre de una versión: la única escritura que admite un código ya guardado. */
    CodigoInfraccion actualizar(CodigoInfraccion codigoInfraccion);
}
