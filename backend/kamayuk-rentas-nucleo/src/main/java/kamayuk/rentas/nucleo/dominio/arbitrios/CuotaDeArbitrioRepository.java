package kamayuk.rentas.nucleo.dominio.arbitrios;

import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Las cuotas de arbitrio determinadas (#31). Ningún método recibe la municipalidad (regla 2): sale
 * del token y la aplica la política RLS.
 *
 * <p><b>No hay {@code actualizar} ni {@code delete}.</b> Una cuota no se corrige en el sitio: se
 * reversa el asiento que generó (regla 4). El {@code UNIQUE} de {@code determinacion_arbitrio}
 * (V23) es la garantía real de que determinar dos veces el mismo predio, servicio, periodo y
 * ejercicio no duplica la fila — no depende de que {@link #existe} se llame siempre antes de {@link
 * #insertar}.
 */
public interface CuotaDeArbitrioRepository {

    /** Si ya existe una cuota para ese predio, servicio, ejercicio y periodo. */
    boolean existe(long predioId, Servicio servicio, Ejercicio ejercicio, int periodo);

    CuotaDeArbitrio insertar(CuotaDeArbitrio cuota);

    Pagina<CuotaDeArbitrio> buscar(CriterioDeArbitrio criterio, Paginacion paginacion);
}
