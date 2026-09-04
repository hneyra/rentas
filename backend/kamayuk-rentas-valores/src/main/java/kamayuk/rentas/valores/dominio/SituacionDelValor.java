package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * En que punto de la cobranza esta un valor <b>a una fecha</b>, tal como lo muestra {@code
 * consulta_valores} (RF-041, #25).
 *
 * <h2>Por que no basta con {@link EstadoDeValor}</h2>
 *
 * <p>{@link EstadoDeValor} es la columna {@code valor.estado}: lo que la cabecera guarda. Pero la
 * pregunta que hace la pantalla —«¿esto ya se puede cobrar?»— no la responde esa columna sola. Un
 * valor {@code NOTIFICADO} el 3 de abril con plazo hasta el 4 de mayo no es exigible el 10 de abril
 * y si lo es el 10 de mayo, <b>sin que ninguna fila haya cambiado</b>: lo que cambio es la fecha
 * desde la que se mira. Por eso esto es una funcion de la fecha (regla 9) y no un estado guardado.
 *
 * <p>Funcion pura (regla 6): sin base, sin reloj y sin configuracion. La fecha entra como
 * argumento, de modo que reconstruir en 2037 la situacion de un valor el 10 de mayo de 2027
 * devuelve lo mismo que devolvia entonces.
 *
 * <h2>El vocabulario del prototipo y el del dominio</h2>
 *
 * <p>La pantalla ofrece «EMITIDO, NOTIFICADO, FIRME, RECLAMADO, COACTIVA, ANULADO». {@link
 * #EXIGIBLE} es lo que el prototipo llama <b>FIRME</b> —el plazo para reclamar vencio y la deuda se
 * puede exigir—, y {@link #porNombre} acepta las dos palabras. <b>RECLAMADO no existe aqui</b>, y
 * no por descuido: no hay reclamacion en el dominio todavia, asi que no hay ninguna fila que mirar.
 * Quien pida ese filtro recibe un 422 con el motivo, nunca el listado sin filtrar.
 */
public enum SituacionDelValor {

    /** Emitido y todavia sin notificar. */
    EMITIDO,

    /** Notificado, pero el plazo aun corre: la deuda no se puede exigir. */
    NOTIFICADO,

    /** El plazo vencio. Es lo que el prototipo llama «FIRME». */
    EXIGIBLE,

    /** Pasado a cobranza coactiva. */
    COACTIVA,

    /** Pagado. */
    PAGADO,

    /** Anulado (regla 4: un valor no se corrige, se anula). */
    ANULADO,

    /** Declarado prescrito. */
    PRESCRITO;

    /** Como llama el prototipo a {@link #EXIGIBLE}. */
    private static final String FIRME = "FIRME";

    /** Lo que la pantalla ofrece y el dominio no tiene: no hay reclamacion todavia. */
    public static final String RECLAMADO = "RECLAMADO";

    /**
     * La situacion de un valor a una fecha.
     *
     * <p>El orden de las comprobaciones es el orden en que se leen: primero lo terminal —pagado,
     * anulado, prescrito—, porque sobre eso ya no hay cobranza que describir; despues coactiva, que
     * absorbe cualquier plazo; y solo entonces se mira si el plazo vencio.
     *
     * @param estado lo que la cabecera guarda
     * @param exigibleDesde desde cuando la deuda es exigible; nulo si ninguna diligencia surtio
     *     efecto todavia
     * @param enCoactiva si existe el pase (PCO) de {@code valor_movimiento}
     * @param fecha desde que dia se mira
     */
    public static SituacionDelValor de(
            EstadoDeValor estado,
            @Nullable LocalDate exigibleDesde,
            boolean enCoactiva,
            LocalDate fecha) {

        Objects.requireNonNull(estado, "La situacion parte del estado guardado");
        Objects.requireNonNull(fecha, "La situacion se calcula a una fecha, nunca «ahora mismo»");

        switch (estado) {
            case PAGADO -> {
                return PAGADO;
            }
            case ANULADO -> {
                return ANULADO;
            }
            case PRESCRITO -> {
                return PRESCRITO;
            }
            default -> {
                // Sigue abajo: los cuatro que quedan dependen de la fecha.
            }
        }
        if (enCoactiva || estado == EstadoDeValor.COACTIVA) {
            return COACTIVA;
        }
        if (exigibleDesde != null && !fecha.isBefore(exigibleDesde)) {
            return EXIGIBLE;
        }
        return estado == EstadoDeValor.NOTIFICADO ? NOTIFICADO : EMITIDO;
    }

    /**
     * La situacion cuyo nombre coincide, admitiendo tambien el vocabulario del prototipo.
     *
     * @throws SinEquivalenteEnElDominio si el nombre es {@code RECLAMADO}, que la pantalla ofrece y
     *     el dominio no tiene
     * @throws IllegalArgumentException si el nombre no es ninguno de los dos vocabularios
     */
    public static SituacionDelValor porNombre(String nombre) {
        String limpio = Objects.requireNonNull(nombre).strip().toUpperCase(Locale.ROOT);
        if (FIRME.equals(limpio)) {
            return EXIGIBLE;
        }
        if (RECLAMADO.equals(limpio)) {
            throw new SinEquivalenteEnElDominio();
        }
        for (SituacionDelValor situacion : values()) {
            if (situacion.name().equals(limpio)) {
                return situacion;
            }
        }
        throw new IllegalArgumentException(
                "Situacion desconocida: '"
                        + nombre
                        + "'. Se admite EMITIDO, NOTIFICADO, FIRME (o EXIGIBLE), COACTIVA, PAGADO,"
                        + " ANULADO y PRESCRITO");
    }

    /**
     * El filtro pedido existe en la pantalla y no en el dominio.
     *
     * <p>Se lanza en vez de ignorar el filtro por el mismo motivo que {@code ConsultaController}
     * rechaza {@code conciliadaConRentas}: devolver el listado completo daria un resultado
     * plausible y equivocado, y quien lo mira creeria estar viendo solo los reclamados.
     */
    public static final class SinEquivalenteEnElDominio extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinEquivalenteEnElDominio() {
            super(
                    "El filtro «RECLAMADO» todavia no se puede responder: la reclamacion de un"
                            + " valor no existe en el dominio, y no hay ninguna fila que mirar."
                            + " Se rechaza en vez de devolver todos los valores sin filtrar");
        }
    }
}
