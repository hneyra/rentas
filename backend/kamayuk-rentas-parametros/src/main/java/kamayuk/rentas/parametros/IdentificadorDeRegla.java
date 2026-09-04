package kamayuk.rentas.parametros;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * El identificador de una regla tributaria: {@code RT-001}, {@code RT-016}.
 *
 * <p>Existe para que {@code determinacion.reglas_aplicadas} tenga contenido reproducible
 * (ADR-0007): dos anios despues, ante una impugnacion, la pregunta no es «cuanto salio» sino «que
 * se aplico para que saliera eso», y la respuesta tiene que ser una lista de identificadores que
 * alguien pueda buscar en NEG-05.
 *
 * <p>Un {@code String} suelto no sirve: se escribe {@code rt-1} en un sitio y {@code RT-001} en
 * otro, y la consulta que agrupa por regla devuelve dos.
 */
public record IdentificadorDeRegla(String valor) implements Comparable<IdentificadorDeRegla> {

    private static final Pattern FORMA = Pattern.compile("^RT-[0-9]{3}$");

    public IdentificadorDeRegla {
        Objects.requireNonNull(valor, "Toda regla tiene identificador");
        valor = valor.strip().toUpperCase(java.util.Locale.ROOT);
        if (!FORMA.matcher(valor).matches()) {
            throw new IllegalArgumentException(
                    "El identificador de una regla es RT- y tres digitos: '" + valor + "'");
        }
    }

    public static IdentificadorDeRegla de(String valor) {
        return new IdentificadorDeRegla(valor);
    }

    @Override
    public int compareTo(IdentificadorDeRegla otro) {
        return valor.compareTo(otro.valor);
    }

    @Override
    public String toString() {
        return valor;
    }
}
