package kamayuk.rentas.cuentacorriente;

/**
 * Lo que el libro sabe de <b>quien</b> origino la deuda de una obligacion, y en que fase esta
 * (#371).
 *
 * <p>Es la mitad de la contencion de #371 que no escribe: la usa {@code valores} antes de
 * formalizar la multa de una papeleta, para no emitir una resolucion de multa por la deuda de otra
 * ni una segunda por la que ya se formalizo. La otra mitad, la que da de baja, es {@link
 * ExtincionDeDeuda#extinguirLoOriginadoPor}, y las dos aplican la misma Specification del dominio.
 *
 * <p>Vive en el paquete raiz por lo mismo que las demas APIs publicas de este modulo: Spring
 * Modulith trata como interno todo lo que esta en un subpaquete.
 */
public interface OrigenDeLaObligacion {

    /**
     * Comprueba que toda la deuda de esa obligacion la origino {@code referenciaExterna}.
     *
     * @param contribuyenteId el obligado
     * @param obligacion el tributo, ejercicio y unidad
     * @param referenciaExterna con que referencia marco su cargo quien pregunta
     * @throws ObligacionCompartida si algun cargo que origino deuda es de otro origen, nombrandolo
     */
    void exigirQueSoloLaOrigine(
            long contribuyenteId, SeleccionDeObligacion obligacion, String referenciaExterna);

    /**
     * Si la obligacion <b>sigue</b> entera en fase {@code ORDINARIA}.
     *
     * <p>{@code false} en cuanto alguna de sus cuotas paso a {@code VALOR} o a {@code COACTIVA}: su
     * deuda ya la formalizo un valor, y otra resolucion por lo mismo seria un segundo titulo por la
     * misma deuda —{@code moverAValor} dejaria ademas {@code ORDINARIA} en negativo—.
     *
     * @param contribuyenteId el obligado
     * @param obligacion el tributo, ejercicio y unidad
     */
    boolean sigueEnOrdinaria(long contribuyenteId, SeleccionDeObligacion obligacion);
}
