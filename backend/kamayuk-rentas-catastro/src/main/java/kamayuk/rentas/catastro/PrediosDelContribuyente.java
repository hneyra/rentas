package kamayuk.rentas.catastro;

import java.time.LocalDate;
import java.util.List;

/**
 * Los predios de los que un contribuyente es titular, publicado para otros contextos acotados
 * (ARQ-01 §4, #25).
 *
 * <p>Es la API publica de este modulo: vive en el paquete raiz, no en {@code .aplicacion} ni en
 * {@code .dominio}, mismo patron que {@link LectorDeFichas} y {@link GestorDeTitularidad}.
 */
public interface PrediosDelContribuyente {

    /**
     * Los predios del contribuyente, con la titularidad vigente en esa fecha. Vacia si no tiene
     * ninguno.
     */
    List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha);
}
