package kamayuk.rentas.nucleo.dominio.predial;

import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Lo que un tramo del articulo 13 aporto al impuesto: hasta donde llega, con que alicuota, sobre
 * que porcion de la base y cuanto puso (#395).
 *
 * <p>Existe para que la memoria de calculo se pueda <b>publicar</b> en vez de recomponerse en la
 * interfaz. RNF-083: sumar los autovaluos de la tabla para «adelantar» la base, o multiplicar la
 * base por una alicuota para adivinar el aporte de un tramo, da una cifra parecida y el error no se
 * ve. Quien aplica los tramos es {@link TramosProgresivosAcumulativos} y es quien dice, tramo a
 * tramo, que hizo.
 *
 * <p><b>Los aportes no estan redondeados, y el impuesto si.</b> ADR-0018 fija que los intermedios
 * corren sin redondear y que el redondeo es del cierre de la regla, asi que la suma de los aportes
 * que aqui se publican puede diferir del impuesto en un centimo. La cifra que manda es la del
 * impuesto —una sola, redondeada una sola vez—; estos aportes explican de donde sale, no la
 * sustituyen.
 *
 * @param orden la posicion del tramo en el cuadro, empezando en 1
 * @param limiteSuperior hasta donde llega el tramo, en soles; nulo en el ultimo, que no tiene tope
 * @param alicuota la que se aplico a la porcion
 * @param porcionGravada cuanto de la base cayo dentro de este tramo
 * @param aporte lo que esa porcion puso en el impuesto, sin redondear
 */
public record AporteDeTramo(
        int orden,
        @Nullable Dinero limiteSuperior,
        Alicuota alicuota,
        Dinero porcionGravada,
        Dinero aporte) {

    public AporteDeTramo {
        if (orden <= 0) {
            throw new IllegalArgumentException("El orden de un tramo empieza en 1: " + orden);
        }
        Objects.requireNonNull(alicuota, "Todo aporte de tramo dice con que alicuota se calculo");
        Objects.requireNonNull(
                porcionGravada, "Todo aporte de tramo dice sobre que porcion corrio");
        Objects.requireNonNull(aporte, "Todo aporte de tramo dice cuanto puso");
    }

    /** Si el tramo tiene tope; el ultimo del cuadro no lo tiene. */
    public boolean tieneTope() {
        return limiteSuperior != null;
    }
}
