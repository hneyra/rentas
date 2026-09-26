package kamayuk.rentas.web;

import java.math.BigDecimal;

/**
 * La unica lectura de un importe o un area TECLEADOS (#395).
 *
 * <p>Un importe o un area que entra por el borde lleva, como mucho, <b>dos decimales</b>: es la
 * escala de {@code dinero numeric(15,2)} y {@code area_m2 numeric(12,2)} (V1), y la del esquema
 * {@code Importe} del contrato. Hasta #395 cada controlador tenia su parser y ninguno la miraba: el
 * calculo usaba lo tecleado, la columna redondeaba al guardar, y la nota sellada, el libro y el
 * recalculo daban cifras distintas —un alta de {@code "33.333"} en cuatro cuotas dejaba 133,32 en
 * el libro y «Total: 133.332» en el papel—.
 *
 * <p>Se <b>rechaza</b> con 422, no se redondea: redondear lo tecleado seria decidir por quien lo
 * tecleo, y lo que se redondea con su politica sellada son los importes que produce el CALCULO
 * (ADR-0018), no los de entrada. {@code "10.500"} no se rechaza: los ceros de la derecha no son un
 * decimal mas.
 *
 * <p>{@code Dinero} y {@code AreaM2} siguen sin escala a proposito: transportan intermedios. La
 * regla es del borde, y por eso vive aqui y no en el objeto de valor.
 */
public final class EntradaNumerica {

    /** Los decimales que admite un importe o un area tecleados (ADR-0018). */
    public static final int DECIMALES = 2;

    private EntradaNumerica() {}

    /**
     * La cifra tecleada, con dos decimales como mucho.
     *
     * @param texto lo que llego, ya sin blancos y no vacio
     * @param campo el nombre del campo, para decir cual se rechaza
     * @param siNoEsCifra la frase del 422 cuando el texto no es un numero, la de cada ruta
     * @throws ProblemaDeNegocio 422 {@code VALIDACION} si no es una cifra o lleva mas de dos
     *     decimales
     */
    public static BigDecimal leer(String texto, String campo, String siNoEsCifra) {
        BigDecimal cifra;
        try {
            cifra = new BigDecimal(texto.strip());
        } catch (NumberFormatException noEsCifra) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, siNoEsCifra);
        }
        int decimales = Math.max(0, cifra.stripTrailingZeros().scale());
        if (decimales > DECIMALES) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '"
                            + campo
                            + "' lleva "
                            + decimales
                            + " decimales ('"
                            + texto.strip()
                            + "'): un importe o un area se teclea con "
                            + DECIMALES
                            + " como mucho, que es lo que se guarda. Redondearlo aqui seria"
                            + " decidir por quien lo tecleo");
        }
        return cifra;
    }
}
