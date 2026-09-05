package kamayuk.rentas.nucleo.dominio.predial;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * RT-014 — Minimo imponible (TUO Ley de Tributacion Municipal, D.S. 156-2004-EF, art. 13; NEG-05
 * §RT-014): si el impuesto calculado es menor que el minimo, se aplica el minimo.
 *
 * <p>Como {@link TramosProgresivosAcumulativos}, corre sobre un valor ya agregado y no encaja en
 * {@code ReglaTributaria} ni {@code ReglaDeAgregacion}; vive como funcion pura aparte. El minimo en
 * si —{@code ‹VERIFICAR›} en NEG-05, expresado como porcentaje de la UIT— es D-02 y llega como
 * argumento, nunca como literal (regla 5): esta clase no sabe cuanto vale, solo compara.
 */
public final class MinimoImponible {

    private MinimoImponible() {}

    /** El mayor entre lo calculado y el minimo. */
    public static Dinero aplicar(Dinero impuestoCalculado, Dinero minimoImponible) {
        Objects.requireNonNull(impuestoCalculado, "Hace falta el impuesto ya calculado");
        Objects.requireNonNull(minimoImponible, "Hace falta el minimo imponible del ejercicio");
        return impuestoCalculado.esMenorQue(minimoImponible) ? minimoImponible : impuestoCalculado;
    }
}
