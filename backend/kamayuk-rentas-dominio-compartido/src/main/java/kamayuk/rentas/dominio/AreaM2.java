package kamayuk.rentas.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Superficie en metros cuadrados.
 *
 * <p>Aparece en toda ficha catastral: area de terreno, area construida por piso, area de bienes
 * comunes. Es un tipo propio y no un decimal suelto porque en la secuencia del calculo de la
 * construccion —valor unitario, mas 5 %, menos depreciacion, <b>por area</b>— el area es el ultimo
 * factor, y confundirla con un importe produce una cifra que parece razonable y no lo es.
 *
 * <p>Rango del dominio {@code area_m2} de PostgreSQL: no negativa, dos decimales. El cero se
 * admite: un predio sin construir tiene area construida cero.
 *
 * <p>Nombre con digito a proposito: {@code AreaM2} es ASCII y no lleva el superindice, que
 * Checkstyle rechazaria en un identificador (ARQ-04 §3).
 */
public record AreaM2(BigDecimal valor) implements Comparable<AreaM2> {

    public static final AreaM2 CERO = new AreaM2(BigDecimal.ZERO);

    public AreaM2 {
        Objects.requireNonNull(valor, "Un area no puede ser nula");
        if (valor.signum() < 0) {
            throw new IllegalArgumentException(
                    "Un area no puede ser negativa: " + valor.toPlainString());
        }
    }

    public static AreaM2 de(String texto) {
        return new AreaM2(new BigDecimal(texto));
    }

    public AreaM2 mas(AreaM2 otra) {
        return new AreaM2(valor.add(otra.valor));
    }

    public boolean esCero() {
        return valor.signum() == 0;
    }

    @Override
    public int compareTo(AreaM2 otra) {
        return valor.compareTo(otra.valor);
    }

    @Override
    public boolean equals(Object otra) {
        return otra instanceof AreaM2 area && valor.compareTo(area.valor) == 0;
    }

    @Override
    public int hashCode() {
        return valor.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return valor.toPlainString() + " m2";
    }
}
