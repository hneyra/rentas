package kamayuk.rentas.rentas.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Las transferencias de predios y vehiculos. Ningun metodo recibe la municipalidad (regla 2).
 *
 * <p><b>No hay {@code eliminar} ni {@code editar}.</b> {@link #insertar} es el unico punto de
 * escritura: una transferencia es un hecho consumado, y corregirla es un asiento contable o una
 * nueva transferencia que la revierta, nunca una edicion de esta fila.
 */
public interface TransferenciaRepository {

    /** Inserta la transferencia y devuelve la fila guardada, con su {@code id}. */
    Transferencia insertar(Transferencia transferencia);

    /**
     * La transferencia sobre la que se determina la alcabala (#32), si existe en esta
     * municipalidad.
     */
    Optional<Transferencia> findById(long id);

    /**
     * La cadena completa de transferencias de un predio, de la mas antigua a la mas reciente
     * (RF-030): quien fue titular, de quien, y desde cuando.
     */
    List<Transferencia> historicoDePredio(long predioId);

    /**
     * El identificador del contribuyente con ese codigo, si existe en esta municipalidad.
     *
     * <p>Vive aqui por el mismo motivo que en {@code AsientoRepository.contribuyentePorCodigo}: se
     * resuelve en SQL contra una tabla con la que {@code transferencia} ya tiene clave foranea, sin
     * conocer ningun tipo del contexto {@code contribuyentes} (ARQ-01 §4 regla 2).
     */
    Optional<Long> contribuyentePorCodigo(String codigo);
}
