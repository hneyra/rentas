package kamayuk.rentas.rentas.dominio;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Placa;

/**
 * Acceso al padron vehicular.
 *
 * <p>Ningun metodo recibe el identificador de municipalidad (regla 2): sale del token y lo aplica
 * la politica RLS de la tabla.
 */
public interface VehiculoRepository {

    /**
     * Busca por placa, <b>sin distinguir el guion</b>.
     *
     * <p>Es la unica forma de busqueda que sirve en ventanilla: quien pregunta trae la placa
     * escrita como se le ocurrio. Que el dominio y la base coincidan en eso lo garantiza el indice
     * unico de V16, no esta consulta.
     */
    Optional<Vehiculo> findByPlaca(Placa placa);

    Optional<Vehiculo> findById(long id);

    /** El padron vehicular que pide el criterio, paginado, con el titular ya resuelto (#25). */
    Pagina<VehiculoEncontrado> buscar(CriterioDeVehiculo criterio, Paginacion paginacion);

    Vehiculo save(Vehiculo vehiculo);

    /**
     * Las placas que este vehiculo ha tenido, de la mas reciente a la mas antigua.
     *
     * <p>Sale de la <b>auditoria</b>, no de una tabla propia: cada cambio ya deja ahi su fila con
     * los datos anteriores y los nuevos (ADR-0008), y una segunda tabla que dijera lo mismo seria
     * un segundo sitio donde equivocarse.
     *
     * <p>La consulta va por el <b>identificador</b> del vehiculo y no por su placa, y esa es la
     * decision que hace que el historial exista: si la auditoria se llavease por la placa, el
     * primer cambio partiria el historial en dos trozos que ya no se pueden juntar.
     */
    List<CambioDePlaca> historialDePlacas(long vehiculoId);
}
