package kamayuk.rentas.contribuyentes;

import java.util.Objects;

/**
 * Lo que otro contexto necesita saber de un contribuyente: quien es y como se le identifica.
 *
 * <p>No lleva domicilio, ni contactos, ni condicion especial, y eso es una decision de coste, no de
 * pudor. Una grilla de veinte fichas resuelve veinte titulares de una vez; anadir aqui el domicilio
 * —que es «el vigente a una fecha», una consulta por contribuyente— convertiria esa consulta unica
 * en veintiuna. Quien necesite la direccion la pide aparte, y entonces se le cobra una sola.
 *
 * <p>La condicion especial no sale nunca: cuanto deduce un pensionista es D-02a, y quien consulta
 * desde catastro no tiene por que enterarse de que lo es.
 *
 * @param documento tipo y numero juntos, ya formateados: {@code "DNI 12345678"}
 */
public record ResumenDeContribuyente(long id, String codigo, String nombre, String documento) {

    public ResumenDeContribuyente {
        Objects.requireNonNull(codigo, "El resumen necesita el codigo del contribuyente");
        Objects.requireNonNull(nombre, "El resumen necesita el nombre");
        Objects.requireNonNull(documento, "El resumen necesita el documento");
    }
}
