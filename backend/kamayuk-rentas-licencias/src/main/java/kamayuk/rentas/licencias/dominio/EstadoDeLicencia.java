package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * En que situacion esta una licencia. <b>No es una columna: se deriva</b> (#44, V37 §1).
 *
 * <p>V4 le habia puesto a {@code licencia_funcionamiento} un {@code estado varchar(15) DEFAULT
 * 'VIGENTE'}, y V37 se lo retira por lo mismo que V30 se lo retiro al recibo, V31 al convenio, V32
 * al turno y V33 al expediente: una columna con valor por omision dice VIGENTE desde el {@code
 * INSERT} y para siempre, porque nada la mueve, y moverla exigiria un {@code UPDATE} sobre un acto
 * administrativo que el titular tiene colgado en la pared.
 *
 * <p>Aqui se calcula a partir de dos cosas y de ninguna mas: los movimientos de la licencia y la
 * fecha a la que se pregunta.
 *
 * <h2>Por que la fecha entra como argumento (regla 6, regla 9)</h2>
 *
 * <p>«Vencida» no es un hecho de la licencia: es una relacion entre su vigencia y un dia. Una
 * licencia temporal que vencio ayer estaba vigente anteayer, y un padron impreso con fecha de corte
 * de anteayer tiene que decir VIGENTE. Resolverlo con {@code LocalDate.now()} haria que reimprimir
 * ese padron manana diera otra cosa.
 */
public enum EstadoDeLicencia {

    /** Emitida, no cancelada y dentro de su vigencia a la fecha preguntada. */
    VIGENTE,

    /** Emitida y no cancelada, pero su plazo ya paso a la fecha preguntada. */
    VENCIDA,

    /** Dejada sin efecto por resolucion (regla 4: no se borra, se cancela). */
    CANCELADA;

    /**
     * El estado que dicen los movimientos a esa fecha.
     *
     * <p>La cancelacion gana sobre el vencimiento: una licencia cancelada el 3 de marzo lo sigue
     * estando en diciembre, aunque su vigencia hubiera terminado igual. El orden importa porque las
     * consecuencias son distintas —una vencida se renueva, una cancelada no—.
     *
     * @param movimientos los de la licencia, en cualquier orden
     * @param vigenciaHasta hasta cuando rige; nulo en una licencia sin plazo
     * @param aLaFecha el dia al que se pregunta
     */
    public static EstadoDeLicencia derivarDe(
            List<MovimientoDeLicencia> movimientos,
            @Nullable LocalDate vigenciaHasta,
            LocalDate aLaFecha) {

        Objects.requireNonNull(movimientos, "La lista de movimientos es vacia, no nula");
        Objects.requireNonNull(aLaFecha, "El estado se pregunta a una fecha (regla 6, regla 9)");

        for (MovimientoDeLicencia movimiento : movimientos) {
            if (movimiento.tipo() == TipoDeMovimientoDeLicencia.CANCELACION
                    && !movimiento.fecha().isAfter(aLaFecha)) {
                return CANCELADA;
            }
        }
        if (vigenciaHasta != null && vigenciaHasta.isBefore(aLaFecha)) {
            return VENCIDA;
        }
        return VIGENTE;
    }

    /** La letra con que la grilla la pinta en su columna «Est.». */
    public String inicial() {
        return name().substring(0, 1);
    }
}
