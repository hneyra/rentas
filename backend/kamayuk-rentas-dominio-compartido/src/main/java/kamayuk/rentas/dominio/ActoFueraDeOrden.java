package kamayuk.rentas.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * La fecha de un acto es anterior al acto que resuelve, o posterior a hoy (#402).
 *
 * <p>Es la <b>única</b> excepción de {@link OrdenDeLosActos}, y reemplaza a las cinco que la regla
 * tenía repartidas por {@code licencias}, {@code sanciones} y {@code valores}. Vive aquí, y no en
 * cada módulo, por lo mismo que {@link OperacionTodaviaNoCompletable}: {@code ManejadorDeErrores}
 * vive en {@code plataforma}, que no depende de ningún contexto, y un tipo común es lo que le deja
 * traducirla a {@code 422} en un solo {@code @ExceptionHandler} en vez de en un {@code catch} por
 * controlador —donde el que se olvida responde 500—.
 *
 * <p>{@code 422} y no {@code 409}: lo que está mal es un dato de la petición —la fecha—, y
 * corregirlo la hace pasar. No es el estado de la fila el que no admite el acto.
 */
public final class ActoFueraDeOrden extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private final String acto;
    private final LocalDate fecha;

    // Un record serializable, pero el analizador no lo sabe ver a traves del campo nulable.
    @SuppressWarnings("serial")
    private final OrdenDeLosActos.@Nullable ActoPrevio previo;

    private ActoFueraDeOrden(
            String acto,
            LocalDate fecha,
            OrdenDeLosActos.@Nullable ActoPrevio previo,
            String motivo) {
        super(mayuscula(acto) + " no puede fecharse el " + fecha + ": " + motivo);
        this.acto = acto;
        this.fecha = fecha;
        this.previo = previo;
    }

    static ActoFueraDeOrden anteriorA(
            String acto, LocalDate fecha, OrdenDeLosActos.ActoPrevio previo) {
        return new ActoFueraDeOrden(
                acto,
                fecha,
                previo,
                "es anterior a " + previo.nombre() + ", del " + previo.fecha());
    }

    static ActoFueraDeOrden posteriorAHoy(String acto, LocalDate fecha, LocalDate hoy) {
        return new ActoFueraDeOrden(acto, fecha, null, "es posterior a hoy, " + hoy);
    }

    /** Qué acto se quiso fechar. */
    public String acto() {
        return acto;
    }

    /** La fecha que se tecleó. */
    public LocalDate fecha() {
        return fecha;
    }

    /** El acto previo al que la fecha se adelanta; vacío si lo que incumple es hoy. */
    public Optional<OrdenDeLosActos.ActoPrevio> previo() {
        return Optional.ofNullable(previo);
    }

    private static String mayuscula(String texto) {
        return texto.isEmpty()
                ? texto
                : texto.substring(0, 1).toUpperCase(Locale.ROOT) + texto.substring(1);
    }
}
