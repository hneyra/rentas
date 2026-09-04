package kamayuk.rentas.sanciones.dominio;

import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * La grilla de «Infracción administrativa» contra PostgreSQL (#397, RF-071). Ningún método recibe
 * la municipalidad (regla 2).
 *
 * <h2>Solo lee</h2>
 *
 * <p>Un procedimiento sancionador no se escribe por aquí: el acta la registra {@code
 * PapeletaRepository}, la RIS la dicta {@code ResolverConResolucionDeGerencia} y la notificación
 * previa la emite {@code RegistrarNotificacionAdministrativa}. Esto es la lectura que
 * <b>compone</b> lo que los tres dejaron escrito, que es justamente por lo que la fase no necesita
 * columna.
 */
public interface ProcedimientoSancionadorRepository {

    /** Una página de la grilla, con la fase ya resuelta a {@code criterio.aLaFecha()}. */
    Pagina<ProcedimientoSancionador> buscar(
            CriterioDelProcedimiento criterio, Paginacion paginacion);
}
