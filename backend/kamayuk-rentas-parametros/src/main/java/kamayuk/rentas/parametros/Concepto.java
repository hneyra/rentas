package kamayuk.rentas.parametros;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Nombra una magnitud del calculo: lo que una regla consume o produce.
 *
 * <p>Es lo que convierte el motor en un grafo en vez de una cadena. NEG-05 §1 no describe una
 * secuencia: {@code RT-001} (terreno), {@code RT-002} (edificacion) y {@code RT-005} (obras
 * complementarias) son <b>tres ramas independientes</b> que convergen en {@code RT-010} ({@code
 * autovaluo = terreno + construccion + obras}). Con un solo importe enhebrandose de regla en regla,
 * {@code RT-002} recibiria el valor del terreno como base y no habria donde expresar la
 * convergencia.
 *
 * <p>No es una enumeracion a proposito: cada contexto nombra los suyos sin tocar este modulo, y
 * agregar {@code RT-017} no obliga a recompilar el catalogo de nadie.
 *
 * <p>El nombre describe una <b>magnitud</b>, no un valor: nombrar un concepto no es escribir una
 * cifra normativa, y la regla 5 sigue intacta.
 */
public record Concepto(String nombre) implements Comparable<Concepto> {

    private static final Pattern FORMA = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    public Concepto {
        Objects.requireNonNull(nombre, "Todo concepto tiene nombre");
        nombre = nombre.strip().toUpperCase(Locale.ROOT);
        if (!FORMA.matcher(nombre).matches()) {
            throw new IllegalArgumentException(
                    "El nombre de un concepto va en mayusculas con guion bajo: '" + nombre + "'");
        }
    }

    public static Concepto de(String nombre) {
        return new Concepto(nombre);
    }

    @Override
    public int compareTo(Concepto otro) {
        return nombre.compareTo(otro.nombre);
    }

    @Override
    public String toString() {
        return nombre;
    }
}
