package kamayuk.rentas.fiscalizacion.dominio;

/**
 * Si un predio esta en la muestra que un programa sorteo (#397).
 *
 * <p>Es lo que sostiene, mientras D-18 siga abierta, lo que la clave foranea {@code
 * acta_fisc_predio_fk} sostenia hasta que {@code V6} la retiro: que el acta predial se levante
 * sobre un predio que existe. Estar en la muestra implica que el predio existia el dia del sorteo,
 * y ademas que el programa lo eligio —ADR-0023 §1: no hay ninguna ruta por la que entre un predio
 * elegido a mano—. La consulta es local, sobre tabla propia: no cruza ninguna frontera.
 *
 * <p>Es una interfaz de un metodo y no un metodo mas de {@link MuestraDelProgramaRepository} porque
 * quien la necesita —registrar un acta— no necesita nada mas de la muestra.
 */
@FunctionalInterface
public interface PertenenciaALaMuestra {

    /** Si ese predio esta en la muestra sorteada de ese programa. */
    boolean contiene(long programaId, long predioId);
}
