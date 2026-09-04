package kamayuk.rentas.indicadores.dominio;

import java.util.List;
import java.util.Objects;

/**
 * Un bloque de filas del panel, con su titulo y la base contra la que se miden (#56, RF-130).
 *
 * <p>La {@link #nota} no es decoracion: dice <b>contra que</b> se calcularon las barras de este
 * bloque. Dos bloques cuyas barras significaran cosas distintas sin decirlo son peores que ningun
 * bloque, porque invitan a compararlas.
 *
 * @param titulo lo que agrupa: «Recaudacion por tributo»
 * @param nota la base de las barras y el periodo que cubren
 * @param lineas una por grupo con movimiento; vacia si no hubo ninguno
 */
public record Cartera(String titulo, String nota, List<LineaDeCartera> lineas) {

    public Cartera {
        Objects.requireNonNull(titulo, "El bloque dice que agrupa");
        Objects.requireNonNull(nota, "El bloque dice contra que mide sus barras");
        Objects.requireNonNull(lineas, "La lista es vacia, no nula");
        lineas = List.copyOf(lineas);
    }
}
