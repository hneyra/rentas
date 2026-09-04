package kamayuk.rentas.indicadores.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * El trabajo parado de la municipalidad, por modulo (#549, RF-130).
 *
 * <p>Lleva las dos marcas de tiempo por lo mismo que {@link AvanceDeRecaudacion}: el dia al que
 * corresponden los recuentos y el instante en que se leyeron. Dos lecturas del mismo dia dan cifras
 * distintas, y sin la hora dos capturas del mismo panel no se distinguen.
 *
 * <h2>La lista puede venir corta, y eso no es un error</h2>
 *
 * <p>Solo trae los frentes que <b>quien pregunta puede ver</b>: un cajero que no tiene Coactiva no
 * recibe su frente, y no lo recibe vacio (#297). Una lista de tres frentes es una respuesta
 * completa para ese perfil.
 *
 * <p>Tambien puede venir vacia —un perfil sin ninguna de las cuatro opciones—, y tampoco es un
 * error: es la respuesta.
 *
 * @param ejercicio el ejercicio al que se refiere lo que depende de el
 * @param fechaCalculo el dia al que corresponden los recuentos
 * @param calculadoEn el instante en que se leyeron
 * @param frentes uno por cada frente que el perfil puede ver, en el orden del enumerado
 */
public record TrabajoParado(
        Ejercicio ejercicio,
        LocalDate fechaCalculo,
        Instant calculadoEn,
        List<FrenteParado> frentes) {

    public TrabajoParado {
        Objects.requireNonNull(ejercicio, "El trabajo parado se cuenta contra un ejercicio");
        Objects.requireNonNull(fechaCalculo, "Un recuento sin fecha miente al dia siguiente");
        Objects.requireNonNull(calculadoEn, "Dice tambien a que hora se leyo");
        Objects.requireNonNull(frentes, "La lista es vacia, no nula");
        frentes = List.copyOf(frentes);
    }
}
