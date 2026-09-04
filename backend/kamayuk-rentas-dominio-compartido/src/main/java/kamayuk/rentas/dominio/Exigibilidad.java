package kamayuk.rentas.dominio;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Desde cuando la deuda formalizada en un valor notificado se puede exigir.
 *
 * <p><b>Es lo que hace posible el expediente coactivo: sin esta fecha, es nulo.</b> Y no es una
 * fecha suelta sino tres, porque una resolucion tiene que poder explicar cada salto:
 *
 * <ol>
 *   <li>{@link #surteEfectoDesde}: el dia habil siguiente al de la diligencia (art. 106 del TUO del
 *       Codigo Tributario). No es el mismo dia: el art. 106 lo dice expresamente.
 *   <li>{@link #venceElPlazo}: {@code surteEfectoDesde} mas el plazo <b>parametrizado</b> —el de
 *       reclamacion, si el valor lo admite—, nunca una constante (regla 5).
 *   <li>{@link #exigibleDesde}: el dia siguiente al vencimiento. Mientras el plazo corre, la deuda
 *       no es exigible; el dia en que vence, tampoco —ese dia todavia se puede reclamar—.
 * </ol>
 *
 * <p>Funcion pura (regla 6): sin reloj, sin base, sin configuracion. La fecha de la diligencia, el
 * plazo y el calendario entran como argumentos, de modo que recalcular en 2037 la exigibilidad de
 * un valor notificado en 2027 da el mismo dia.
 *
 * <p><b>Sirve a los dos actos que abren un plazo</b>, y por eso vive en el dominio compartido desde
 * #41: la notificacion de un valor —desde cuando su deuda se puede exigir (#39)— y la de una REC-1
 * —desde cuando, vencidos los siete dias del art. 14.1 de la Ley 26979, se puede dictar la medida
 * cautelar (#41)—. Es la misma cuenta y se escribe una sola vez.
 */
public record Exigibilidad(
        LocalDate fechaDeLaDiligencia,
        LocalDate surteEfectoDesde,
        LocalDate venceElPlazo,
        LocalDate exigibleDesde) {

    public Exigibilidad {
        Objects.requireNonNull(fechaDeLaDiligencia, "La exigibilidad parte de una diligencia");
        Objects.requireNonNull(surteEfectoDesde, "Falta desde cuando surte efecto");
        Objects.requireNonNull(venceElPlazo, "Falta cuando vence el plazo");
        Objects.requireNonNull(exigibleDesde, "Falta desde cuando es exigible");
    }

    /**
     * Deriva las tres fechas de una diligencia que surtio efecto.
     *
     * @param fechaDeLaDiligencia cuando se notifico
     * @param plazo el plazo parametrizado; leido del conjunto sellado, jamas compilado
     * @param calendario que dias cuentan si el plazo es en dias habiles
     */
    public static Exigibilidad derivarDe(
            LocalDate fechaDeLaDiligencia, Plazo plazo, CalendarioHabil calendario) {
        Objects.requireNonNull(fechaDeLaDiligencia, "La exigibilidad parte de una diligencia");
        Objects.requireNonNull(plazo, "El plazo entra por parametro, no por constante (regla 5)");
        Objects.requireNonNull(calendario, "El computo necesita su calendario");

        LocalDate surteEfecto = calendario.siguienteHabil(fechaDeLaDiligencia);
        LocalDate vence = plazo.vencimientoDesde(surteEfecto, calendario);
        return new Exigibilidad(fechaDeLaDiligencia, surteEfecto, vence, vence.plusDays(1));
    }

    /** Si a esa fecha el plazo ya vencio y la deuda se puede exigir. */
    public boolean exigibleA(LocalDate fecha) {
        return !Objects.requireNonNull(fecha).isBefore(exigibleDesde);
    }
}
