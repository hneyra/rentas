package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.CalendarioHabil;
import kamayuk.rentas.dominio.Plazo;

/**
 * El computo de la prescripcion de un ejercicio, con sus interrupciones y suspensiones (#39,
 * RF-094; arts. 43 a 46 del TUO del Codigo Tributario).
 *
 * <h2>Funcion pura</h2>
 *
 * <p>Sin base de datos, sin reloj y sin configuracion global (regla 6). El inicio del computo, el
 * plazo, los hechos y la fecha a la que se resuelve entran los cuatro como argumentos: resolver en
 * 2037 la misma solicitud que se resolvio en 2027 tiene que dar el mismo dia, y lo daria distinto
 * si cualquiera de los cuatro saliera de "hoy".
 *
 * <h2>Que hace cada hecho</h2>
 *
 * <p>Los hechos se recorren en orden cronologico, y solo cuentan los que ocurren <b>antes</b> de
 * que el plazo venza: un acto posterior a la prescripcion no la deshace.
 *
 * <ul>
 *   <li><b>Interrupcion</b> (art. 45): el plazo "se cuenta de nuevo desde el dia siguiente al
 *       acaecimiento del acto interruptorio". El reloj vuelve a cero, y con el se van las
 *       suspensiones anteriores —ya no prorrogan nada, porque el plazo que prorrogaban no existe—.
 *   <li><b>Suspension</b> (art. 46): el plazo se detiene mientras dura, asi que el vencimiento se
 *       corre tantos dias como duro el intervalo.
 * </ul>
 */
public final class ComputoDePrescripcion {

    private ComputoDePrescripcion() {}

    /**
     * Resuelve el computo de un ejercicio.
     *
     * @param inicioComputo el dia 1 del plazo (art. 44); lo deriva quien llama del ejercicio y del
     *     desfase parametrizado, nunca de una constante
     * @param plazo el plazo del art. 43 que corresponde a la causal; leido del conjunto sellado
     * @param hechos las interrupciones y suspensiones alegadas; en cualquier orden
     * @param fechaDeResolucion a que fecha se decide si ya prescribio —la de presentacion de la
     *     solicitud—, y no "hoy"
     */
    public static Computo resolver(
            LocalDate inicioComputo,
            Plazo plazo,
            List<HechoDelComputo> hechos,
            LocalDate fechaDeResolucion) {

        Objects.requireNonNull(inicioComputo, "El computo empieza un dia (art. 44)");
        Objects.requireNonNull(plazo, "El plazo entra por parametro, no por constante (regla 5)");
        Objects.requireNonNull(hechos, "La lista de hechos puede estar vacia, pero no faltar");
        Objects.requireNonNull(fechaDeResolucion, "Toda cifra dice a que fecha se resolvio");

        List<HechoDelComputo> ordenados = new ArrayList<>(hechos);
        ordenados.sort(Comparator.comparing(HechoDelComputo::desde));

        LocalDate inicioVigente = inicioComputo;
        // El calendario solo lo usa DIAS_HABILES; un plazo de prescripcion es en anios. Se pasa
        // igualmente porque el tipo lo pide, y porque nada impide parametrizar un plazo en dias.
        CalendarioHabil calendario = CalendarioHabil.sinFeriados();
        LocalDate vencimiento = plazo.vencimientoDesde(inicioVigente, calendario);
        List<HechoDelComputo> aplicados = new ArrayList<>();

        for (HechoDelComputo hecho : ordenados) {
            if (hecho.desde().isAfter(vencimiento)) {
                // Ya habia prescrito cuando ocurrio: no lo deshace.
                break;
            }
            aplicados.add(hecho);
            switch (hecho.clase()) {
                case INTERRUPCION -> {
                    inicioVigente = hecho.desde().plusDays(1);
                    vencimiento = plazo.vencimientoDesde(inicioVigente, calendario);
                }
                case SUSPENSION -> {
                    LocalDate hasta = Objects.requireNonNull(hecho.hasta());
                    vencimiento =
                            vencimiento.plusDays(ChronoUnit.DAYS.between(hecho.desde(), hasta));
                }
                default ->
                        throw new IllegalStateException(
                                "Clase de hecho sin cubrir: " + hecho.clase());
            }
        }

        boolean prescrita = !fechaDeResolucion.isBefore(vencimiento);
        return new Computo(
                inicioComputo, inicioVigente, vencimiento, prescrita, List.copyOf(aplicados));
    }

    /**
     * El resultado del computo de un ejercicio.
     *
     * <p>Lleva {@link #inicioComputo} y {@link #inicioVigente} por separado porque la resolucion
     * tiene que poder explicar por que la fecha de prescripcion no es "el inicio mas el plazo":
     * entre los dos hay las interrupciones que {@link #hechosAplicados} enumera.
     *
     * @param inicioComputo el dia 1 original (art. 44)
     * @param inicioVigente el dia 1 que quedo tras la ultima interrupcion aplicada
     * @param fechaDePrescripcion el dia en que el plazo vence
     * @param prescrita si a la fecha de resolucion ya habia vencido
     * @param hechosAplicados los hechos que entraron al computo, en orden; los posteriores a la
     *     prescripcion no estan
     */
    public record Computo(
            LocalDate inicioComputo,
            LocalDate inicioVigente,
            LocalDate fechaDePrescripcion,
            boolean prescrita,
            List<HechoDelComputo> hechosAplicados) {

        public Computo {
            Objects.requireNonNull(inicioComputo);
            Objects.requireNonNull(inicioVigente);
            Objects.requireNonNull(fechaDePrescripcion);
            hechosAplicados = List.copyOf(Objects.requireNonNull(hechosAplicados));
        }
    }
}
