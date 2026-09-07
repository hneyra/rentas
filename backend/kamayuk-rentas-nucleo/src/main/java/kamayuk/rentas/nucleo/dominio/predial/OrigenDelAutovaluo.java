package kamayuk.rentas.nucleo.dominio.predial;

/**
 * De donde salio el autovaluo con que se determino un predio (#38, AC-3; V14).
 *
 * <p>Son dos actos distintos y se impugnan de maneras distintas: una declaracion jurada la firma el
 * contribuyente y una valuacion la sella {@code catastro} con su conjunto y su huella (ADR-0027).
 * Sin esta columna, dentro de un ano nadie puede decir de cual de las dos salio un recibo — y la
 * cifra, a solas, es indistinguible.
 */
public enum OrigenDelAutovaluo {

    /** Lo fijo una declaracion jurada: lo teclearon, o venia de una determinacion anterior. */
    DECLARADO,

    /** Salio de la valuacion que {@code catastro} sello para ese predio y ese ejercicio. */
    SELLADO
}
