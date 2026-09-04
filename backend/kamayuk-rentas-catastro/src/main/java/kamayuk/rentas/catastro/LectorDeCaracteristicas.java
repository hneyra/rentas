package kamayuk.rentas.catastro;

import java.time.LocalDate;
import java.util.Optional;

/**
 * El uso y el sector de un predio, publicado para otros contextos acotados (ARQ-01 §4, #31).
 *
 * <p>Es la API publica de este modulo: vive en el paquete raiz, no en {@code .aplicacion} ni en
 * {@code .dominio}, mismo patron que {@link LectorDeFichas} y {@link GestorDeTitularidad}.
 */
public interface LectorDeCaracteristicas {

    /**
     * Las caracteristicas del predio a esa fecha, si el predio existe en esta municipalidad.
     *
     * <p>Un predio sin ficha vigente o sin sector no es un error: sus campos salen {@code null} en
     * {@link CaracteristicasDelPredio}, y quien determina arbitrios decide que hacer con eso —esta
     * API no lo decide por el.
     */
    Optional<CaracteristicasDelPredio> de(long predioId, LocalDate fecha);
}
