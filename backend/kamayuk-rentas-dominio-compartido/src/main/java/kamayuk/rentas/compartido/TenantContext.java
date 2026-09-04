package kamayuk.rentas.compartido;

import java.util.Optional;
import kamayuk.rentas.dominio.MunicipalidadId;

/**
 * Contexto de municipalidad de la peticion en curso.
 *
 * <p>Es el <b>unico</b> lugar donde el sistema conoce la municipalidad (ARQ-03 §2). El valor entra
 * una sola vez, desde el claim del token, en el borde de la aplicacion; de ahi lo toma el gestor de
 * transacciones para emitir {@code SET LOCAL app.municipalidad_id} al abrirla. Ninguna capa
 * intermedia lo recibe ni lo pasa: si el desarrollador no lo maneja, no puede olvidarlo.
 *
 * <p>Nombre en ingles a proposito: es una utilidad tecnica, no un concepto del dominio tributario
 * (ARQ-04 §3).
 */
public final class TenantContext {

    private static final ThreadLocal<MunicipalidadId> ACTUAL = new ThreadLocal<>();

    private TenantContext() {}

    /**
     * Fija la municipalidad de la peticion. Un unico llamador legitimo: el borde de la aplicacion.
     */
    public static void fijar(MunicipalidadId municipalidadId) {
        if (municipalidadId == null) {
            throw new IllegalArgumentException("El contexto de tenant no admite un valor nulo");
        }
        ACTUAL.set(municipalidadId);
    }

    /**
     * Municipalidad en curso.
     *
     * @throws IllegalStateException si no hay contexto. Falla a proposito en lugar de devolver algo
     *     por omision: la base hace lo mismo, y un error ruidoso es preferible a una fuga
     *     silenciosa (RNF-032).
     */
    public static MunicipalidadId actual() {
        MunicipalidadId municipalidadId = ACTUAL.get();
        if (municipalidadId == null) {
            throw new IllegalStateException(
                    "No hay contexto de municipalidad fijado. Toda operacion sobre datos de tenant"
                            + " ocurre dentro de un contexto establecido en el borde de la aplicacion");
        }
        return municipalidadId;
    }

    /** Para el codigo que legitimamente puede correr fuera de una peticion. */
    public static Optional<MunicipalidadId> actualSiHay() {
        return Optional.ofNullable(ACTUAL.get());
    }

    /** Se llama siempre al cerrar la peticion, incluso si hubo error. */
    public static void limpiar() {
        ACTUAL.remove();
    }
}
