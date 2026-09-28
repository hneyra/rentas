package kamayuk.rentas.web;

import java.math.BigDecimal;
import kamayuk.rentas.compartido.CifraTecleada;

/**
 * La lectura de un importe o un area TECLEADOS en un parametro del borde (#395).
 *
 * <p>Un importe o un area que entra por el borde lleva, como mucho, <b>dos decimales</b>: es la
 * escala de {@code dinero numeric(15,2)} y {@code area_m2 numeric(12,2)} (V1), y la del esquema
 * {@code Importe} del contrato. Hasta #395 cada controlador tenia su parser y ninguno la miraba: el
 * calculo usaba lo tecleado, la columna redondeaba al guardar, y la nota sellada, el libro y el
 * recalculo daban cifras distintas —un alta de {@code "33.333"} en cuatro cuotas dejaba 133,32 en
 * el libro y «Total: 133.332» en el papel—.
 *
 * <p>Se <b>rechaza</b> con 422, no se redondea. <b>La regla no vive aqui</b> (#629): es {@link
 * CifraTecleada}, del dominio compartido, porque la aplican tambien el importador de archivos —que
 * es de {@code aplicacion} y no puede depender del borde— y el deserializador del cuerpo JSON. Lo
 * que esta clase anade es la traduccion a {@link ProblemaDeNegocio}: 422 {@code VALIDACION} con la
 * misma frase.
 */
public final class EntradaNumerica {

    /** Los decimales que admite un importe o un area tecleados: los de {@link CifraTecleada}. */
    public static final int DECIMALES = CifraTecleada.DECIMALES;

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
        try {
            return CifraTecleada.leer(texto, campo, siNoEsCifra);
        } catch (CifraTecleada.DecimalesDeMas deMas) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, deMas.motivo());
        } catch (IllegalArgumentException noEsCifra) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, siNoEsCifra);
        }
    }
}
