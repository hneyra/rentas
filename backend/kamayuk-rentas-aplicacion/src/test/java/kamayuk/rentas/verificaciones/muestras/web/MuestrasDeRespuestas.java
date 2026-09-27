package kamayuk.rentas.verificaciones.muestras.web;

import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;

/**
 * Muestras para el censo de respuestas (#436): lo que {@code RevisorDeRespuestas} tiene que ver, y
 * lo que no.
 *
 * <p>La primera es la cadena que el censo se dejaba hasta #436: el metodo llama a un ayudante que
 * llama a otro que lanza {@code NO_ENCONTRADO} —{@code exigirQueExista -> noExiste}, como en {@code
 * FichaDelContribuyenteController}—. Un censo de un solo salto no la ve.
 */
@SuppressWarnings("unused")
public final class MuestrasDeRespuestas {

    /** La cadena de dos ayudantes: tiene que salir 404. */
    public Object conCadenaDeDosAyudantes(long id) {
        exigirQueExista(id);
        return "ok";
    }

    /** Un 409 dicho en el propio metodo. */
    public Object conConflicto() {
        throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, "el estado no lo admite");
    }

    /** El contraste: nada que declarar. */
    public Object sinNada() {
        return "ok";
    }

    private void exigirQueExista(long id) {
        if (id <= 0) {
            noExiste(id);
        }
    }

    private void noExiste(long id) {
        throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, "no existe " + id);
    }
}
