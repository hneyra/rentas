package kamayuk.rentas.nucleo.dominio;

import java.time.LocalDate;
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
     * La cadena completa de transferencias de un vehiculo, de la mas antigua a la mas reciente: el
     * gemelo de {@link #historicoDePredio} (#329).
     *
     * <p>Es la unica historia de propiedad que un vehiculo tiene: {@code vehiculo.contribuyente_id}
     * se sobrescribe en cada transferencia y la fecha solo queda aqui. Con ella se contesta de
     * quien era el vehiculo al 1 de enero de un ejercicio ({@code PropietarioAlPrimeroDeEnero}).
     */
    List<Transferencia> historicoDeVehiculo(long vehiculoId);

    /**
     * Los vehiculos que ese contribuyente transfirio con fecha igual o posterior a {@code fecha},
     * sin repetir (#329).
     *
     * <p>La fecha misma cuenta: una transferencia fechada el 1 de enero no le quita al transferente
     * ese ejercicio, porque el adquiriente es contribuyente desde el 1 de enero del ano siguiente
     * (art. 31 del TUO LTM; ver {@code PropietarioAlPrimeroDeEnero}).
     *
     * <p>Son los que pudo tener ese dia y ya no figuran a su nombre: el calculo por contribuyente
     * los suma a sus vehiculos de hoy antes de preguntarle a cada uno de quien era al 1 de enero.
     * No los filtra: uno comprado despues de la fecha y vendido otra vez tambien sale, y es la
     * regla del art. 31 la que lo descarta.
     */
    List<Long> vehiculosQueTransfirioDesde(long transferenteId, LocalDate fecha);

    /**
     * El identificador del contribuyente con ese codigo, si existe en esta municipalidad.
     *
     * <p>Vive aqui por el mismo motivo que en {@code AsientoRepository.contribuyentePorCodigo}: se
     * resuelve en SQL contra una tabla con la que {@code transferencia} ya tiene clave foranea, sin
     * conocer ningun tipo del contexto {@code contribuyentes} (ARQ-01 §4 regla 2).
     *
     * <p>Y por el mismo motivo compara en la forma de {@code CodigoContribuyente.formaDeBusqueda}
     * (#423): el calculo vehicular por contribuyente la llama con el codigo tal como se tecleo.
     */
    Optional<Long> contribuyentePorCodigo(String codigo);
}
