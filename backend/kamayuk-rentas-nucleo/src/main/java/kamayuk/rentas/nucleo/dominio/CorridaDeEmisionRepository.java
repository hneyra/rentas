package kamayuk.rentas.nucleo.dominio;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;

/**
 * Las corridas de emision anual del predial (#523).
 *
 * <p>Solo se escriben y se leen: una corrida es un hecho, y corregir lo que emitio se hace
 * corriendo otra, que deja su propia fila. La migracion lo sostiene —{@code kamayuk_app} no tiene
 * {@code UPDATE} ni {@code DELETE} sobre ninguna de las dos tablas—, y aqui no hay verbo para
 * intentarlo.
 */
public interface CorridaDeEmisionRepository {

    /**
     * Escribe la corrida con sus observados, en la misma transaccion que la ejecuta.
     *
     * @param corrida lo que hizo, sin id
     * @param observacion por que se corrio (regla 10, RNF-052)
     * @return la misma corrida con el id que asigno la base
     */
    CorridaDeEmision guardar(CorridaDeEmision corrida, Observacion observacion);

    /**
     * La ultima corrida de un ejercicio, <b>sin</b> sus observados.
     *
     * <p>Sin ellos a proposito: son cientos, la pantalla los pide aparte y una lectura que los
     * trajera siempre haria de la portada del modulo la peticion mas pesada del sistema.
     */
    Optional<CorridaDeEmision> ultimaDe(Ejercicio ejercicio);

    /** Los observados de una corrida, paginados: son la lista de cosas que arreglar. */
    Pagina<CorridaDeEmision.Observado> observadosDe(long corridaId, Paginacion paginacion);

    /** Las ultimas corridas, mas reciente primero y sin observados. */
    List<CorridaDeEmision> ultimas(int cuantas);
}
