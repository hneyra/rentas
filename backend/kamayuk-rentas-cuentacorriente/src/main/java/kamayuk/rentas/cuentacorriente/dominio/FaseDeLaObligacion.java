package kamayuk.rentas.cuentacorriente.dominio;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * En que fase esta una obligacion <b>a una fecha</b> (#363). <b>Funcion pura</b> (regla 6): entran
 * los asientos y la fecha de corte, sale la fase; sin base de datos y sin reloj.
 *
 * <p>Es <b>una</b> regla, y hasta #363 tenia dos escrituras: {@code consulta_deuda} la leia de la
 * proyeccion —la del ultimo asiento de cada cuota, pero <i>de hoy</i>— y la constancia de no adeudo
 * la calculaba sobre todos los asientos de la obligacion sin mirar su fecha valor. Una constancia a
 * una fecha anterior a la OP decia {@link Fase#VALOR}: un hecho que ese dia no habia ocurrido.
 *
 * <p>La definicion:
 *
 * <ul>
 *   <li><b>por cuota</b>, la fase del ultimo asiento con {@code fechaValor <= corte}. El ultimo por
 *       <b>identificador</b> y no por fecha valor, igual que {@link ProyeccionDelSaldo}: dos
 *       asientos pueden compartir fecha valor —el par de un movimiento de fase la comparte siempre—
 *       y el identificador no se repite. Un convenio quebrado deja sus asientos en {@link
 *       Fase#CONVENIO} en el libro, pero el ultimo ya no es uno de ellos;
 *   <li><b>entre cuotas</b>, la mas avanzada, en el orden en que {@link Fase} las declara: si un
 *       mes ya paso a valor y otro sigue ordinario, la fila exige la atencion del que paso (ve
 *       {@link ObligacionConDeuda}).
 * </ul>
 *
 * <p>Con el corte posterior a todo el libro da lo mismo que la fase mas avanzada de {@link
 * ProyeccionDelSaldo#de}, y {@code FaseDeLaObligacionTest} lo ata: si una de las dos cambiara de
 * definicion, la otra lo diria.
 *
 * <p>Una obligacion sin ningun asiento a esa fecha —la que se pregunta antes de su primer
 * movimiento, y que la constancia lista en 0,00— esta en {@link Fase#ORDINARIA}: es la fase con que
 * nace toda obligacion, y la misma de la que parte {@link ProyeccionDelSaldo}. Un asiento sin
 * identificador tampoco decide la fase, por lo mismo que alli: no se sabe si es el ultimo.
 */
public final class FaseDeLaObligacion {

    /** Como {@link Fase} declara sus valores en el orden de la cobranza: la mas avanzada gana. */
    private static final Comparator<Fase> MAS_AVANZADA = Comparator.naturalOrder();

    private FaseDeLaObligacion() {}

    /**
     * La fase de la obligacion a la fecha de corte.
     *
     * @param deLaObligacion los asientos de <b>una</b> obligacion, de todas sus cuotas y sin cortar
     *     por fecha: el corte lo hace esta funcion
     * @param corte la fecha a la que se pregunta (regla 6, RNF-075)
     */
    public static Fase a(List<Asiento> deLaObligacion, LocalDate corte) {
        Objects.requireNonNull(deLaObligacion, "La lista de asientos es vacia, no nula");
        Objects.requireNonNull(corte, "La fecha de corte entra como argumento (regla 6)");

        Map<ClaveDeSaldo, Asiento> ultimoPorCuota = new LinkedHashMap<>();
        for (Asiento asiento : deLaObligacion) {
            if (asiento.id() == null || asiento.fechaValor().isAfter(corte)) {
                continue;
            }
            ultimoPorCuota.merge(ClaveDeSaldo.de(asiento), asiento, FaseDeLaObligacion::elUltimo);
        }
        return ultimoPorCuota.values().stream()
                .map(Asiento::fase)
                .max(MAS_AVANZADA)
                .orElse(Fase.ORDINARIA);
    }

    /** El de mayor identificador: el ultimo que se asento. Los dos lo traen, ve {@link #a}. */
    private static Asiento elUltimo(Asiento uno, Asiento otro) {
        return Objects.requireNonNull(otro.id()) > Objects.requireNonNull(uno.id()) ? otro : uno;
    }
}
