package kamayuk.rentas.rentas.dominio.alcabala;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * La base imponible de la alcabala: el mayor valor entre el de transferencia y el autovalúo
 * ajustado (TUO Ley de Tributación Municipal, D.S. 156-2004-EF, art. 24; #32).
 *
 * <p><b>No calcula el ajuste del autovalúo por el IPM.</b> Ese factor —el «% actualización»— es uno
 * de los cuatro que NEG-05 §0.1 marca sin fuente identificada (D-11), y CLAUDE.md prohíbe
 * implementarlo ni estructuralmente hasta verificar su origen: {@code autovaluoAjustado} llega ya
 * resuelto por quien invoca.
 *
 * <p>La comparación en sí <b>no es una cifra</b>: es estructura —«el mayor de los dos»— igual que
 * {@link kamayuk.rentas.rentas.dominio.predial.MinimoImponible#aplicar} compara sin saber cuánto
 * vale ninguno de los dos importes.
 */
public final class BaseImponibleDeAlcabala {

    private BaseImponibleDeAlcabala() {}

    /**
     * Elige la base, con el fundamento de la elección (criterio de aceptación de #32).
     *
     * @param valorDeTransferencia el valor declarado en la transferencia
     * @param autovaluoAjustado el autovalúo del predio, ya ajustado por el IPM
     */
    public static EleccionDeBase elegir(Dinero valorDeTransferencia, Dinero autovaluoAjustado) {
        Objects.requireNonNull(valorDeTransferencia, "Hace falta el valor de transferencia");
        Objects.requireNonNull(autovaluoAjustado, "Hace falta el autovaluo ya ajustado");

        if (autovaluoAjustado.esMayorQue(valorDeTransferencia)) {
            return new EleccionDeBase(
                    autovaluoAjustado,
                    OrigenDeLaBase.AUTOAVALUO_AJUSTADO,
                    "El autovaluo ajustado ("
                            + autovaluoAjustado
                            + ") supera al valor de transferencia ("
                            + valorDeTransferencia
                            + "): TUO LTM art. 24 exige tomar el mayor de los dos");
        }
        return new EleccionDeBase(
                valorDeTransferencia,
                OrigenDeLaBase.VALOR_DE_TRANSFERENCIA,
                "El valor de transferencia ("
                        + valorDeTransferencia
                        + ") es igual o mayor que el autovaluo ajustado ("
                        + autovaluoAjustado
                        + "): TUO LTM art. 24 exige tomar el mayor de los dos");
    }
}
