package kamayuk.rentas.cuentacorriente.dominio;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Que la deuda de una obligacion la origino <b>un solo</b> acto de quien pide operar sobre ella
 * (#371): la Specification de la contencion.
 *
 * <h2>Por que hace falta</h2>
 *
 * <p>La clave del libro —{@link ClaveDeObligacion}— es contribuyente, tributo, ejercicio y unidad,
 * y no tiene sitio para el acto que origino cada cargo. Dos papeletas del mismo obligado, del mismo
 * ejercicio y sin vehiculo del padron caen en <b>la misma</b> obligacion, y lo unico que las
 * distingue en el libro es la {@code referencia_externa} de su cargo. Quien da de baja o formaliza
 * «la obligacion de la papeleta» opera en realidad sobre la de todas: anular T-001 extinguia
 * tambien T-002, y la corrida emitia una RM por las dos.
 *
 * <p>Esto no arregla el modelo —que la papeleta sea la unidad de su obligacion es #465, y toca el
 * libro entero—: lo <b>contiene</b>. Antes de operar se comprueba que todos los cargos que
 * originaron deuda lleven la referencia de quien pide, y si aparece otra se dice cual, en vez de
 * extinguirla o formalizarla sin ningun acto que lo sustente.
 *
 * <h2>Que cuenta como cargo de origen</h2>
 *
 * <p>Un {@code CARGO} de concepto {@code INSOLUTO} que no reversa a otro asiento. Asi quedan fuera,
 * sin nombrarlos uno por uno, los tres cargos que no originan deuda: el del movimiento de fase
 * ({@code AJUSTE}), la reversion de un abono (lleva {@code asiento_reversado_id}) y el que la
 * cobranza escribe al cristalizar el devengo, que no es de insoluto.
 *
 * <p>Un cargo de origen <b>sin</b> referencia —un padron migrado, un alta manual— tambien es de
 * otro origen: no se sabe de quien es, y adivinar que es de quien pide es exactamente el defecto.
 * Se nombra por su documento de origen, que es lo unico que lo identifica.
 *
 * <p>Pura (regla 6): recibe los asientos y la referencia, y no lee nada.
 */
public final class CargosDeUnSoloOrigen {

    private CargosDeUnSoloOrigen() {}

    /**
     * Los otros origenes de la deuda de esa obligacion, sin repetir y en el orden del libro.
     *
     * @param asientos todos los asientos de la obligacion, de todos sus periodos
     * @param referenciaExterna la referencia con que quien pide marco su cargo
     * @return vacia si todos los cargos de origen son suyos; si no, la referencia de cada otro
     *     origen, o {@code "documento <x>"} si su cargo no lleva referencia
     */
    public static List<String> otrosOrigenes(List<Asiento> asientos, String referenciaExterna) {
        Objects.requireNonNull(asientos, "Sin asientos no hay obligacion que mirar");
        Objects.requireNonNull(referenciaExterna, "Hay que decir de que origen se pregunta");
        Set<String> otros = new LinkedHashSet<>();
        for (Asiento asiento : asientos) {
            if (!esCargoDeOrigen(asiento)) {
                continue;
            }
            String referencia = asiento.referenciaExterna();
            if (referencia == null) {
                otros.add("documento " + asiento.documentoOrigen());
            } else if (!referencia.equals(referenciaExterna)) {
                otros.add(referencia);
            }
        }
        return List.copyOf(otros);
    }

    /**
     * Si el asiento es un cargo que <b>origina</b> deuda: la definicion del javadoc de la clase.
     *
     * <p>Visible en el paquete porque {@link LoOriginadoPor} pregunta lo mismo con otra llave —el
     * documento de origen y no la referencia externa— (#342), y dos copias de «que cargo origina
     * deuda» son dos sitios donde se puede corregir uno solo.
     */
    static boolean esCargoDeOrigen(Asiento asiento) {
        return asiento.tipo() == TipoAsiento.CARGO
                && asiento.concepto() == Concepto.INSOLUTO
                && asiento.asientoReversadoId() == null;
    }
}
