package kamayuk.rentas.documentos;

/**
 * Si esta instalacion es de demostracion o de verdad.
 *
 * <h2>Por que existe</h2>
 *
 * <p>Mientras D-02a siga abierta, cualquier importe que el sistema calcule sale de parametros que
 * nadie ha firmado. Una hoja de resumen impresa con una cifra plausible y sin marca es un documento
 * que alguien puede intentar cobrar, y la marcha blanca no es honesta sin eso resuelto (GOB-04 §5).
 *
 * <h2>Por que es un puerto de UNA pregunta</h2>
 *
 * <p>Porque lo unico que la capa de documentos necesita saber es si marca o no marca. No hay {@code
 * activar}, ni {@code desactivar}: quitar la marca es un {@code UPDATE} de {@code kamayuk_owner}
 * sobre {@code municipalidad.es_demostracion}, hecho a mano y seguido de un reinicio —por la cache
 * de {@link RegimenDeLaInstalacionJdbc}—. <b>No</b> lo hace el alta: con la fila ya puesta,
 * relanzar la implantacion no toca la marca (#122), y si lo que pide es el otro valor lo dice con
 * un {@code ERROR} en lugar de afirmarlo (#348). Un metodo aqui seria un camino desde una pantalla
 * hasta un documento sin marcar.
 *
 * <p>Y no recibe la municipalidad, como ningun metodo de dominio (regla 2): sale del contexto de
 * tenant, que es lo que impide que un llamador pregunte por la instalacion de otro.
 */
public interface RegimenDeLaInstalacion {

    /**
     * ¿Todo documento que salga de aqui tiene que ir marcado?
     *
     * <p>Se consulta por cada documento, asi que la implementacion no puede permitirse una consulta
     * por documento: una emision masiva son miles.
     */
    boolean esDeDemostracion();

    /** El regimen de una instalacion real, para las pruebas que no van contra la base. */
    RegimenDeLaInstalacion REAL = () -> false;

    /** El regimen de una instalacion de demostracion, para las pruebas. */
    RegimenDeLaInstalacion DEMOSTRACION = () -> true;
}
