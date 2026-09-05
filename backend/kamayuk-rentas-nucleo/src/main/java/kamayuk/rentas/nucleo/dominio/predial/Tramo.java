package kamayuk.rentas.nucleo.dominio.predial;

import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Un tramo del cuadro progresivo del predial (RT-013, TUO Ley de Tributacion Municipal, D.S.
 * 156-2004-EF, art. 13).
 *
 * <p><b>Estructura, no valores.</b> Ni el limite ni la alicuota son literales del codigo (regla 5):
 * los construye quien resuelve el cuadro de tramos del conjunto sellado —hoy, en las pruebas, un
 * valor ficticio marcado como tal; el dia que D-02 se cierre, un valor leido de {@code
 * parametro_tributario}—. El limite llega ya convertido a soles: convertir UIT a soles es
 * responsabilidad de quien arma el cuadro, no de este tipo ni de {@link
 * TramosProgresivosAcumulativos}.
 *
 * @param limiteSuperior hasta cuanto llega el tramo; vacio si es el ultimo tramo, sin tope
 * @param alicuota la que se aplica a la porcion de base que cae en este tramo
 */
public record Tramo(@Nullable Dinero limiteSuperior, Alicuota alicuota) {

    public Tramo {
        Objects.requireNonNull(alicuota, "Todo tramo tiene su alicuota");
        if (limiteSuperior != null && !limiteSuperior.esPositivo()) {
            throw new IllegalArgumentException(
                    "El limite superior de un tramo, si existe, tiene que ser positivo: "
                            + limiteSuperior);
        }
    }

    /** Un tramo con tope: rige hasta {@code limiteSuperior} inclusive. */
    public static Tramo hasta(Dinero limiteSuperior, Alicuota alicuota) {
        return new Tramo(Objects.requireNonNull(limiteSuperior), alicuota);
    }

    /** El ultimo tramo, sin tope: todo lo que exceda el limite del tramo anterior. */
    public static Tramo sinTope(Alicuota alicuota) {
        return new Tramo(null, alicuota);
    }

    public boolean tieneTope() {
        return limiteSuperior != null;
    }
}
