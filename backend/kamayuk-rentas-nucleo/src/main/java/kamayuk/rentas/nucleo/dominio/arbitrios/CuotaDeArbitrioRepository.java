package kamayuk.rentas.nucleo.dominio.arbitrios;

import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Las cuotas de arbitrio determinadas (#31). Ningún método recibe la municipalidad (regla 2): sale
 * del token y la aplica la política RLS.
 *
 * <p><b>No hay {@code actualizar} ni {@code delete}.</b> Una cuota no se corrige en el sitio: se
 * reversa el asiento que generó (regla 4). La restricción {@code det_arbitrio_uq} ({@code
 * V1__baseline.sql}) es la garantía real de que determinar dos veces el mismo predio, servicio,
 * periodo y ejercicio no duplica la fila — no depende de que {@link #existe} se llame siempre antes
 * de {@link #insertar}.
 */
public interface CuotaDeArbitrioRepository {

    /** Si ya existe una cuota para ese predio, servicio, ejercicio y periodo. */
    boolean existe(long predioId, Servicio servicio, Ejercicio ejercicio, int periodo);

    /**
     * Inserta la cuota y la devuelve con su identificador. Una segunda cuota del mismo predio,
     * servicio, ejercicio y periodo la rechaza {@code det_arbitrio_uq}, se haya preguntado antes
     * {@link #existe} o no.
     */
    CuotaDeArbitrio insertar(CuotaDeArbitrio cuota);

    /**
     * Las cuotas del ejercicio del criterio, paginadas; con {@code codigoPredial}, solo las del
     * predio que lleva ese codigo de referencia catastral en la proyeccion local de catastro.
     */
    Pagina<CuotaDeArbitrio> buscar(CriterioDeArbitrio criterio, Paginacion paginacion);
}
