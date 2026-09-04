package kamayuk.rentas.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Proporcion de algo, expresada en tanto por ciento.
 *
 * <p>No es una {@link Alicuota}: una alicuota grava una base, un porcentaje reparte una cosa. El
 * caso que obliga a distinguirlos es el <b>% de propiedad</b> de la titularidad de un predio, que
 * pondera la base de cada predio dentro del calculo por contribuyente.
 *
 * <p>Rango del dominio {@code porcentaje} de PostgreSQL: mayor que 0 y hasta 100. El 0 queda fuera
 * a proposito —un titular con 0 % de propiedad no es titular—, y ahi esta la diferencia con {@code
 * Alicuota}, que si admite el cero.
 */
public record Porcentaje(BigDecimal valor) implements Comparable<Porcentaje> {

    private static final BigDecimal MINIMO_EXCLUIDO = BigDecimal.ZERO;
    private static final BigDecimal MAXIMO = new BigDecimal("100");

    public Porcentaje {
        Objects.requireNonNull(valor, "Un porcentaje no puede ser nulo");
        if (valor.compareTo(MINIMO_EXCLUIDO) <= 0 || valor.compareTo(MAXIMO) > 0) {
            throw new IllegalArgumentException(
                    "Porcentaje fuera de rango: "
                            + valor.toPlainString()
                            + ". Se admite mayor que 0 y hasta 100");
        }
    }

    public static Porcentaje de(String texto) {
        return new Porcentaje(new BigDecimal(texto));
    }

    /** Un porcentaje completo. Es el caso del titular unico. */
    public static Porcentaje total() {
        return new Porcentaje(MAXIMO);
    }

    public boolean esTotal() {
        return valor.compareTo(MAXIMO) == 0;
    }

    @Override
    public int compareTo(Porcentaje otro) {
        return valor.compareTo(otro.valor);
    }

    @Override
    public boolean equals(Object otro) {
        return otro instanceof Porcentaje porcentaje && valor.compareTo(porcentaje.valor) == 0;
    }

    @Override
    public int hashCode() {
        return valor.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return valor.toPlainString() + " %";
    }
}
