package kamayuk.rentas.indicadores.dominio;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.OptionalInt;
import kamayuk.rentas.dominio.Dinero;

/**
 * Que parte de una base ya esta cobrada, en porcentaje entero. <b>Funcion pura</b> (#56, RF-130).
 *
 * <h2>Una sola definicion para todo el panel</h2>
 *
 * <p>{@code avance = parte / base}, donde la base es siempre <b>lo cargado en el libro</b>. Todas
 * las barras del panel se calculan asi, y por eso se pueden comparar entre si. La alternativa —una
 * definicion por panel— produce dos barras que dicen «73 %» y significan cosas distintas, que es
 * peor que no tener barras.
 *
 * <h2>Sin base no hay porcentaje: se dice, no se inventa</h2>
 *
 * <p>Con la base en cero devuelve <b>vacio</b>, y quien lo reciba escribe «—». No devuelve 0, y esa
 * es la diferencia que este tipo existe para sostener: un tributo sin cargos asentados en el
 * ejercicio no lleva un 0 % de avance —eso se lee como «no se ha cobrado nada», que es un juicio
 * sobre la gestion—, lleva un hueco. Es la regla 5 aplicada donde suele escaparse: la cifra que no
 * existe no se rellena con la que quede mas a mano.
 *
 * <p>La misma razon vale para la <b>meta</b> de recaudacion que el prototipo dibuja: no hay ninguna
 * tabla de metas en el esquema, una meta es un acto de gestion que se aprueba y se firma, y darle
 * como valor «lo cargado» produciria un cumplimiento que nadie firmo. Por eso el panel publica el
 * avance <b>de cobranza</b> —contra lo que se puso a cobrar, que si consta— y ningun avance contra
 * meta.
 *
 * <h2>Trunca, no redondea</h2>
 *
 * <p>{@link BigDecimal#divideToIntegralValue} da el cociente entero sin pedir modo de redondeo: no
 * hay aqui ninguna {@code PoliticaDeRedondeo} que tomar por descuido (D-03). Truncar es ademas la
 * eleccion prudente para una barra: el 99,7 % se dibuja como 99, y una barra que dijera 100 sobre
 * una deuda que sigue viva es peor que una que se queda corta.
 */
public final class AvanceDeCobranza {

    private static final BigDecimal CIEN = new BigDecimal("100");

    /** El maximo que puede dibujar una barra. */
    public static final int COMPLETO = 100;

    private AvanceDeCobranza() {}

    /**
     * Que porcentaje de {@code base} representa {@code parte}, entero y truncado.
     *
     * @param parte lo que ya esta cobrado, o lo que se quiere medir contra la base
     * @param base lo cargado en el libro
     * @return vacio si la base no es positiva —no hay contra que medir—; si no, de 0 a 100
     */
    public static OptionalInt de(Dinero parte, Dinero base) {
        Objects.requireNonNull(parte, "El avance necesita su parte");
        Objects.requireNonNull(base, "El avance necesita su base");
        if (!base.esPositivo()) {
            return OptionalInt.empty();
        }
        if (parte.esNegativo()) {
            // Una parte negativa es un abono en exceso o una reversion que dejo el grupo
            // por debajo de cero. No hay avance negativo que dibujar; la cifra exacta la
            // sigue diciendo el importe de la linea.
            return OptionalInt.of(0);
        }
        int avance =
                parte.valor()
                        .multiply(CIEN)
                        .divideToIntegralValue(base.valor())
                        .min(BigDecimal.valueOf(COMPLETO))
                        .intValue();
        return OptionalInt.of(avance);
    }
}
