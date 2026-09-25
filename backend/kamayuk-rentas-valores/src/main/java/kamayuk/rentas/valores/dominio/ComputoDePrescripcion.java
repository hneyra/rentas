package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * <p>Los hechos se recorren en orden cronologico, y solo actuan sobre el <b>tramo en que el plazo
 * corre</b>: del inicio vigente al vencimiento. Un acto posterior a la prescripcion no la deshace,
 * y uno anterior al inicio no tiene plazo que tocar (#334).
 *
 * <ul>
 *   <li><b>Interrupcion</b> (art. 45): el plazo "se cuenta de nuevo desde el dia siguiente al
 *       acaecimiento del acto interruptorio". El reloj vuelve a cero, y con el se van las
 *       suspensiones anteriores —ya no prorrogan nada, porque el plazo que prorrogaban no existe—.
 *       <b>Tambien la parte de una suspension que sigue en curso despues del acto</b>, y eso no es
 *       una conclusion sino una lectura sin escribir: el art. 46 dice que el plazo «se suspende
 *       durante» la tramitacion, y leido al pie de la letra alcanzaria tambien al plazo reiniciado.
 *       Esa regla no esta en {@code prescripcion-y-plazos.md} de {@code normativa} ni en NEG-19,
 *       que no existe, y aqui no se rediseña una regla tributaria: se conserva lo de antes, y
 *       {@code ComputoDePrescripcionTest.InterrupcionDentroDeUnaSuspension} lo fija (#335). Si el
 *       acto es <b>anterior</b> al inicio vigente no hace nada: no interrumpe un plazo que todavia
 *       no corria, y aplicarlo dejaria el inicio vigente antes del inicio del computo, que {@link
 *       ComputoDeEjercicio} rechaza —asi es como una solicitud legitima salia 422—.
 *   <li><b>Suspension</b> (art. 46): el plazo se detiene mientras dura, asi que el vencimiento se
 *       corre tantos dias como duro el intervalo <b>dentro del tramo</b> ({@link
 *       IntervaloSuspendido#interseccion}). De una reclamacion que termino antes del inicio no
 *       cuenta ningun dia; de una que lo cruza, solo los que caen desde el inicio. Los dias se
 *       cuentan con los dos extremos, y <b>una vez cada uno</b>: el vencimiento del tramo es el
 *       nominal mas los dias de la {@link IntervalosSuspendidos#unir union} de sus suspensiones, no
 *       la suma en bruto —una reclamacion y un fraccionamiento simultaneos detienen el plazo una
 *       vez— (#335).
 * </ul>
 *
 * <p><b>A que ejercicio pertenece cada hecho no lo decide esta funcion.</b> Recibe los hechos de UN
 * ejercicio y no aprende nada de rangos: el filtro por {@link AlcanceDelHecho} lo hace quien la
 * llama, antes ({@code DeclararPrescripcion}). El recorte de aqui es lo que queda cuando el hecho
 * si es del ejercicio —el pago de la cuota de mayo del predial 2021 es del 2021, y ocurre antes de
 * que su plazo empiece—.
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
        LocalDate nominal = plazo.vencimientoDesde(inicioVigente, calendario);
        LocalDate vencimiento = nominal;
        // Las suspensiones del tramo vigente, tal como caen dentro de el. No se suman una a una:
        // el vencimiento sale de su union, para que un dia suspendido dos veces cuente una (#335).
        List<IntervaloSuspendido> suspendidosDelTramo = new ArrayList<>();
        List<HechoDelComputo> aplicados = new ArrayList<>();

        for (HechoDelComputo hecho : ordenados) {
            if (hecho.desde().isAfter(vencimiento)) {
                // Ya habia prescrito cuando ocurrio: no lo deshace.
                break;
            }
            switch (hecho.clase()) {
                case INTERRUPCION -> {
                    if (hecho.desde().isBefore(inicioVigente)) {
                        // El plazo todavia no corria: no hay nada que interrumpir (#334).
                        continue;
                    }
                    aplicados.add(hecho);
                    inicioVigente = hecho.desde().plusDays(1);
                    nominal = plazo.vencimientoDesde(inicioVigente, calendario);
                    // El tramo nuevo empieza sin suspensiones: ver la cabecera y #335.
                    suspendidosDelTramo.clear();
                    vencimiento = nominal;
                }
                case SUSPENSION -> {
                    Optional<IntervaloSuspendido> dentro =
                            dentroDelTramo(hecho.intervaloSuspendido(), inicioVigente);
                    if (dentro.isEmpty()) {
                        // Termino antes de que el plazo empezara a correr (#334).
                        continue;
                    }
                    aplicados.add(hecho);
                    suspendidosDelTramo.add(dentro.get());
                    vencimiento =
                            nominal.plusDays(
                                    IntervalosSuspendidos.unir(suspendidosDelTramo).dias());
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
     * La parte de una suspension que cae en el tramo en que el plazo corre (#334).
     *
     * <p>El tramo empieza en el inicio vigente. <b>Por el final no se recorta</b>, y no es un
     * olvido: el vencimiento no es un limite fijo mientras dura una suspension, sino lo que ella
     * misma corre. Una suspension que empieza antes del vencimiento detiene el plazo hasta su
     * ultimo dia (art. 46: «se suspende durante» la tramitacion), asi que cortarla en el
     * vencimiento de antes de sumarla descontaria de menos. La que empieza despues del vencimiento
     * ya la descarto el recorrido —«un acto posterior a la prescripcion no la deshace»—. Cuando
     * haya varias que se solapen, el recorrido las une antes de contar (#335).
     */
    private static Optional<IntervaloSuspendido> dentroDelTramo(
            IntervaloSuspendido suspension, LocalDate inicioVigente) {
        return suspension.interseccion(inicioVigente, LocalDate.MAX);
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
     * @param hechosAplicados los hechos que entraron al computo, en orden; no estan los posteriores
     *     a la prescripcion ni los que cayeron enteros antes del inicio vigente (#334)
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
