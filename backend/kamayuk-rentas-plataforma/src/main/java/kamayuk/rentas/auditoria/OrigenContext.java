package kamayuk.rentas.auditoria;

import java.util.Optional;

/**
 * Origen de la peticion en curso.
 *
 * <p>Gemelo deliberado de {@code TenantContext}, y por el mismo motivo: el usuario, el equipo y la
 * IP entran <b>una vez</b>, en el borde de la aplicacion, y de ahi los toma quien escribe la
 * auditoria. Ninguna capa intermedia los recibe ni los pasa.
 *
 * <p>La alternativa —que cada caso de uso reciba usuario, equipo e IP— parece mas explicita y es
 * peor: son tres parametros que atraviesan todas las firmas del sistema sin que ninguna capa
 * intermedia haga nada con ellos, y el dia que alguien escriba un caso de uso nuevo los rellenara
 * con lo que tenga a mano. Lo que si viaja en la firma es la {@code Observacion}, porque esa
 * <b>si</b> la escribe el usuario para esta operacion concreta y nadie mas la puede saber.
 *
 * <p>Nombre en ingles: es una utilidad tecnica, no vocabulario tributario (ARQ-04 §3).
 */
public final class OrigenContext {

    private static final ThreadLocal<Origen> ACTUAL = new ThreadLocal<>();

    private OrigenContext() {}

    public static void fijar(Origen origen) {
        if (origen == null) {
            throw new IllegalArgumentException("El origen de la peticion no admite un valor nulo");
        }
        ACTUAL.set(origen);
    }

    /**
     * Origen en curso.
     *
     * @throws IllegalStateException si no hay ninguno. Falla a proposito: una escritura auditada
     *     sin saber quien la hace no es una auditoria incompleta, es una auditoria inutil, y es
     *     preferible que la operacion no ocurra a que ocurra sin rastro.
     */
    public static Origen actual() {
        Origen origen = ACTUAL.get();
        if (origen == null) {
            throw new IllegalStateException(
                    "No hay origen de peticion fijado. Toda escritura auditada ocurre dentro de un"
                            + " origen establecido en el borde de la aplicacion, o con"
                            + " Origen.deProceso para lo que corre sin peticion");
        }
        return origen;
    }

    public static Optional<Origen> actualSiHay() {
        return Optional.ofNullable(ACTUAL.get());
    }

    /** Se llama siempre al cerrar la peticion, incluso si hubo error. */
    public static void limpiar() {
        ACTUAL.remove();
    }
}
