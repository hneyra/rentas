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
 *
 * <h2>Dos entradas, y no una (#332)</h2>
 *
 * <p>{@link #aplicar} compara un impuesto con el minimo y nada mas: es la que usa el vehicular, que
 * no tiene una base afecta que una deduccion pueda dejar en cero. El predial <b>si</b> la tiene, y
 * entra por {@link #aplicarSobreBase}, que mira la base antes de comparar: con una base afecta
 * exactamente cero —el templo inafecto del art. 17 del TUO LTM, el predio exonerado entero— NEG-05
 * deja la pregunta abierta (§RT-014: «Base cero por deduccion — ¿Se cobra el minimo o nada?» y
 * «Predio inafecto — ¿Se cobra el minimo? Presumiblemente no»), y el corpus marca los dos casos
 * {@code SIN_CRITERIO:NEG-05} (RT-014-c02 y c03). Hasta #332 el codigo la contestaba en silencio y
 * en la direccion que cobra: base cero, impuesto cero y <b>monto determinado = el minimo</b>. Ahora
 * se niega nombrando los dos casos, con {@link BaseAfectaCero}.
 *
 * <p>Cuando NEG-05 decida, la negativa se convierte en la respuesta —cero o el minimo— <b>aqui
 * dentro</b>, en la funcion pura, y los dos casos del corpus pasan a llevar su esperado. Ni la
 * firma de {@link #aplicar} ni el vehicular se tocan para eso.
 */
public final class MinimoImponible {

    private MinimoImponible() {}

    /** El mayor entre lo calculado y el minimo. */
    public static Dinero aplicar(Dinero impuestoCalculado, Dinero minimoImponible) {
        Objects.requireNonNull(impuestoCalculado, "Hace falta el impuesto ya calculado");
        Objects.requireNonNull(minimoImponible, "Hace falta el minimo imponible del ejercicio");
        return impuestoCalculado.esMenorQue(minimoImponible) ? minimoImponible : impuestoCalculado;
    }

    /**
     * El minimo del predial, que conoce la base afecta sobre la que se calculo el impuesto (#332).
     *
     * <p>Con cualquier base positiva, por chica que sea, es exactamente {@link #aplicar}: una base
     * de S/ 1,00 sigue pagando el minimo. Lo unico que cambia es la base cero.
     *
     * @param impuestoCalculado el impuesto por tramos (RT-013)
     * @param baseAfecta la base imponible del contribuyente, ya agregada (RT-011)
     * @param minimoImponible el minimo del ejercicio (D-02b)
     * @throws BaseAfectaCero si la base es exactamente cero: NEG-05 no decide si se cobra el minimo
     */
    public static Dinero aplicarSobreBase(
            Dinero impuestoCalculado, Dinero baseAfecta, Dinero minimoImponible) {
        Objects.requireNonNull(baseAfecta, "Hace falta la base afecta del contribuyente");
        if (baseAfecta.esCero()) {
            throw new BaseAfectaCero();
        }
        return aplicar(impuestoCalculado, minimoImponible);
    }

    /**
     * La base afecta del contribuyente es exactamente cero, y NEG-05 no dice si con ella se cobra
     * el minimo o nada (RT-014-c02 y RT-014-c03, #332).
     *
     * <p>No se determina. Cobrar el minimo le emite deuda a quien la referencia del proyecto
     * presume que no paga —el inafecto del art. 17 ni siquiera es sujeto del impuesto—, y emitir
     * cero seria contestar la misma pregunta en la otra direccion. Negarse es lo unico que no la
     * contesta, y deja al contribuyente a la vista: 422 en el calculo individual y observado en la
     * corrida, igual que {@code DeterminarPredial.PredioSinAutovaluo}.
     */
    public static final class BaseAfectaCero extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        BaseAfectaCero() {
            super(
                    "La base afecta del contribuyente es cero —todo su autovaluo esta inafecto o"
                            + " exonerado— y NEG-05 no decide si con base cero se cobra el minimo"
                            + " imponible o nada (RT-014-c02: base cero por deduccion; RT-014-c03:"
                            + " predio inafecto, presumiblemente no se cobra). No se determina:"
                            + " cobrarle el minimo seria decidirlo en silencio (#332)");
        }
    }
}
