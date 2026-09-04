package kamayuk.rentas.documentos;

import java.util.Objects;

/**
 * Un dato de la cabecera del documento: {@code "Contribuyente: SANTOS RIVERA, ELENA"}.
 *
 * <p>El valor es texto ya formateado. Aqui no se formatea nada: quien construye el modelo sabe si
 * una cifra lleva dos decimales o cuatro, y este paquete no tiene por que aprender a decidirlo
 * —seria decidir D-03 por la puerta de atras—.
 */
public record Campo(String etiqueta, String valor) {

    public Campo {
        Objects.requireNonNull(etiqueta, "Un campo necesita su etiqueta");
        Objects.requireNonNull(valor, "Un campo necesita su valor; si no hay, va la cadena vacia");
        etiqueta = etiqueta.strip();
        if (etiqueta.isEmpty()) {
            throw new IllegalArgumentException("La etiqueta de un campo no puede estar en blanco");
        }
    }

    public static Campo de(String etiqueta, String valor) {
        return new Campo(etiqueta, valor);
    }
}
