package kamayuk.rentas.dominio;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * Que dias cuentan para un plazo en dias habiles.
 *
 * <p><b>Los feriados entran como argumento, no se consultan.</b> Es lo que mantiene el computo como
 * funcion pura (regla 6): recalcular en 2037 el plazo de un valor notificado en 2027 tiene que dar
 * el mismo dia, y lo daria mal si la lista de feriados saliera de una consulta a "hoy".
 *
 * <p><b>Que sabado y domingo no sean habiles es estructura; cuales son feriados, es dato.</b> Lo
 * primero lo fija el art. 144 de la Ley 27444 y no cambia por ejercicio ni por municipalidad; lo
 * segundo cambia cada anio y viaja en los parametros sellados. Un calendario {@link #sinFeriados()}
 * no es un valor por omision disfrazado: es la afirmacion, explicita, de que para ese ejercicio no
 * se declaro ningun feriado —y si esa afirmacion es falsa, el plazo sale corto y la constancia de
 * con que calendario se calculo esta en el conjunto sellado que la fila guarda—.
 *
 * <p>En el dominio compartido desde #41: lo usan el plazo de reclamacion de un valor (#39) y el de
 * los siete dias de la REC-1 (#41), que se cuentan con el mismo calendario.
 */
public record CalendarioHabil(Set<LocalDate> feriados) {

    public CalendarioHabil {
        Objects.requireNonNull(feriados, "El calendario necesita su conjunto de feriados");
        feriados = Set.copyOf(feriados);
    }

    /** Solo sabados y domingos son inhabiles: ningun feriado declarado para el ejercicio. */
    public static CalendarioHabil sinFeriados() {
        return new CalendarioHabil(Set.of());
    }

    public boolean esHabil(LocalDate fecha) {
        Objects.requireNonNull(fecha, "No hay dia habil sin dia");
        DayOfWeek dia = fecha.getDayOfWeek();
        return dia != DayOfWeek.SATURDAY && dia != DayOfWeek.SUNDAY && !feriados.contains(fecha);
    }

    /** El primer dia habil estrictamente posterior a {@code fecha}. */
    public LocalDate siguienteHabil(LocalDate fecha) {
        LocalDate siguiente = Objects.requireNonNull(fecha).plusDays(1);
        while (!esHabil(siguiente)) {
            siguiente = siguiente.plusDays(1);
        }
        return siguiente;
    }

    /**
     * El dia que resulta de contar {@code dias} habiles a partir de {@code desde}, sin contarlo a
     * el.
     *
     * @param dias cuantos dias habiles; cero devuelve {@code desde}
     */
    public LocalDate sumarHabiles(LocalDate desde, int dias) {
        if (dias < 0) {
            throw new IllegalArgumentException("Un plazo no se cuenta hacia atras: " + dias);
        }
        LocalDate fecha = Objects.requireNonNull(desde);
        for (int contados = 0; contados < dias; contados++) {
            fecha = siguienteHabil(fecha);
        }
        return fecha;
    }
}
