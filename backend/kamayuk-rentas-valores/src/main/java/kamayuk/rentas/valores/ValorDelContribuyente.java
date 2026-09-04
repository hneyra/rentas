package kamayuk.rentas.valores;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Un valor emitido tal como cruza la frontera del modulo (#25, RF-046).
 *
 * <p>Es la proyeccion de {@code ValorEnConsulta} —que vive en {@code .dominio} y no cruza— reducida
 * a lo que la pestaña «Valores» de la consulta unificada pinta. Mismo criterio con que {@code
 * cuentacorriente} devuelve {@code ObligacionPublica} y no {@code ObligacionConDeuda}.
 *
 * <h2>El importe esta congelado, y su fecha lo dice</h2>
 *
 * <p>{@link #proyectadoA} es la fecha del desglose, y <b>no es la de hoy</b>: lo que un valor
 * notificado dice que se debe es lo que se congelo al emitirlo, no lo que el libro diria ahora (AC
 * de #37, regla 9). Un valor cuyo importe cambiara solo no seria notificable.
 *
 * <p>Por eso este puerto es el unico de los tres que la consulta unificada consume que <b>no</b>
 * lleva la fecha de corte de la consulta en sus cifras: llevarla seria afirmar que estan
 * actualizadas a hoy, que es justo lo contrario de lo que son.
 *
 * <h2>La situacion si depende de hoy</h2>
 *
 * <p>{@link #situacion} no es la columna {@code estado}: es una funcion de (estado, exigible desde,
 * pase a coactiva, <b>fecha</b>). Un valor notificado el 3 de abril con plazo hasta el 4 de mayo no
 * es exigible el 10 de abril y si lo es el 10 de mayo, sin que ninguna fila haya cambiado. Por eso
 * viaja con {@link #situacionA}.
 *
 * @param tipo OP, RD o RM
 * @param numero el numero impreso
 * @param ejercicio el ejercicio de emision de la cabecera
 * @param fechaEmision el dia en que se emitio
 * @param tributos los tributos del detalle, ya agregados por el servidor (RNF-083)
 * @param periodo el ejercicio o el rango que el valor formaliza, ya compuesto por el servidor
 * @param situacion en que punto de la cobranza esta, a {@code situacionA}
 * @param situacionA desde que dia se miro la situacion (regla 9)
 * @param insoluto el tributo formalizado, congelado
 * @param reajuste el reajuste congelado
 * @param interes el interes congelado
 * @param gasto los gastos congelados
 * @param total la suma de las cuatro partes, nunca una quinta cifra calculada aparte
 * @param proyectadoA la fecha a la que estan las cinco cifras (regla 9, RNF-075)
 */
public record ValorDelContribuyente(
        String tipo,
        String numero,
        Ejercicio ejercicio,
        LocalDate fechaEmision,
        @Nullable String tributos,
        @Nullable String periodo,
        String situacion,
        LocalDate situacionA,
        Dinero insoluto,
        Dinero reajuste,
        Dinero interes,
        Dinero gasto,
        Dinero total,
        LocalDate proyectadoA) {

    public ValorDelContribuyente {
        Objects.requireNonNull(tipo, "El valor necesita su tipo");
        Objects.requireNonNull(numero, "El valor necesita su numero");
        Objects.requireNonNull(ejercicio, "El valor necesita su ejercicio");
        Objects.requireNonNull(fechaEmision, "El valor necesita su fecha de emision");
        Objects.requireNonNull(situacion, "El valor necesita su situacion");
        Objects.requireNonNull(
                situacionA, "Toda situacion indica a que fecha se miro (regla 9, RNF-075)");
        Objects.requireNonNull(insoluto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(reajuste, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(interes, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(gasto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(total, "El valor necesita su total");
        Objects.requireNonNull(
                proyectadoA, "Toda cifra indica a que fecha esta (RNF-075, regla 9)");
    }
}
