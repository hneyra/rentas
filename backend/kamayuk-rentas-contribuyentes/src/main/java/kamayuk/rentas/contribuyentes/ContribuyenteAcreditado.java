package kamayuk.rentas.contribuyentes;

import java.util.Objects;

/**
 * Quien es, en <b>esta</b> municipalidad, quien presenta un documento acreditado (ADR-0020, #57).
 *
 * <h2>Por que no es {@link ResumenDeContribuyente}</h2>
 *
 * <p>Por un solo campo, y el campo es el motivo: {@code activo}. El resumen que este contexto
 * publica a los demas no lo lleva —una grilla de titulares no pregunta si el titular sigue de alta,
 * porque el predio se muestra igual—, y aqui hace falta porque el portal tiene que <b>decirlo</b>.
 *
 * <p>Un contribuyente dado de baja del padron queda <b>dentro</b> del recorrido y <b>marcado</b>:
 * la deuda sobrevive a la baja —el libro no la borra, RNF-051— y ocultarla seria decirle a alguien
 * que no debe nada. La baja del padron es un hecho administrativo de la municipalidad, no una
 * extincion de la obligacion, y las dos cosas no se pueden decir con la misma pantalla.
 *
 * @param id el identificador interno, para cruzar con el libro y con la titularidad. No sale por
 *     HTTP: lo que el ciudadano ve de si mismo es su codigo de contribuyente
 * @param codigo el codigo con el que esta municipalidad lo identifica; el que figura en su recibo
 * @param nombre nombre o razon social, tal como esta en el padron
 * @param documento tipo y numero juntos, ya formateados: {@code "DNI 12345678"}
 * @param activo si sigue de alta en el padron de esta municipalidad
 */
public record ContribuyenteAcreditado(
        long id, String codigo, String nombre, String documento, boolean activo) {

    public ContribuyenteAcreditado {
        Objects.requireNonNull(codigo, "El contribuyente acreditado necesita su codigo");
        Objects.requireNonNull(nombre, "El contribuyente acreditado necesita su nombre");
        Objects.requireNonNull(documento, "El contribuyente acreditado necesita su documento");
    }
}
