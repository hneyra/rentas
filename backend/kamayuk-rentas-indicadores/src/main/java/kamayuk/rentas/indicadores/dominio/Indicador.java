package kamayuk.rentas.indicadores.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Una de las cifras grandes del panel (#56, RF-130).
 *
 * <p>Lleva la cifra <b>dos veces y a proposito</b>: {@link #cifra} es el texto que la pantalla
 * dibuja —redactado aqui, RNF-080— y {@link #importe} es el mismo numero sin redactar, para quien
 * tenga que sumarlo o compararlo. No todas las cifras del panel son importes: un recuento o un
 * porcentaje solo tienen texto, y entonces {@link #importe} va nulo.
 *
 * <p>{@link #actualizadoA} es obligatorio y va en <b>cada</b> indicador, no una sola vez en el
 * panel. No es redundancia: la cartera se lee de un cache que puede llevar dias sin recalcularse
 * mientras lo recaudado del dia es de hace un segundo, y una sola fecha para las dos convertiria la
 * mas vieja en mentira (regla 9, RNF-075).
 *
 * @param concepto lo que se mide: «Recaudado 2026»
 * @param cifra el texto que se dibuja: «S/ 18,415,232.40», «77 %», «—»
 * @param nota la linea pequeña que la explica
 * @param importe la misma cifra sin redactar, cuando es un importe; nulo cuando no lo es
 * @param actualizadoA a que fecha esta esa cifra
 */
public record Indicador(
        String concepto,
        String cifra,
        String nota,
        @Nullable Dinero importe,
        LocalDate actualizadoA) {

    public Indicador {
        Objects.requireNonNull(concepto, "El indicador dice que mide");
        Objects.requireNonNull(cifra, "El indicador trae su cifra, aunque sea «—»");
        Objects.requireNonNull(nota, "La nota es vacia, no nula");
        Objects.requireNonNull(
                actualizadoA, "Toda cifra indica a que fecha esta actualizada (RNF-075, regla 9)");
    }
}
