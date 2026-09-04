package kamayuk.rentas.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Una cifra que fija una norma: la UIT del ejercicio, un tramo, una alicuota, un valor unitario, un
 * factor de depreciacion.
 *
 * <p><b>No es {@link Dinero}</b>, aunque a veces lleve soles. Un valor normativo puede ser un
 * importe, un porcentaje o un factor sin unidad, y lo que tienen en comun no es la unidad sino el
 * origen: <b>ninguno se escribe en el codigo</b> (regla 5). Viven en datos versionados con su
 * documento fuente y su vigencia, porque cambian por ordenanza y no por despliegue.
 *
 * <p>La escala es la del dominio {@code monto_calc} de PostgreSQL —{@code numeric(18,6)}— y no la
 * de {@code dinero}: un valor unitario de edificacion o un factor de depreciacion necesitan mas
 * decimales que un importe, y redondearlos al guardarlos introduciria en la base un error que
 * despues se multiplica por el area de cada predio.
 *
 * <p>Igualdad por valor, no por representacion, como en el resto de los envoltorios de decimal.
 */
public record ValorNormativo(BigDecimal valor) implements Comparable<ValorNormativo> {

    public ValorNormativo {
        Objects.requireNonNull(valor, "Un valor normativo no puede ser nulo");
    }

    /** Valor a partir de su representacion decimal en texto. Nunca desde {@code double}. */
    public static ValorNormativo de(String texto) {
        return new ValorNormativo(new BigDecimal(texto));
    }

    @Override
    public int compareTo(ValorNormativo otro) {
        return valor.compareTo(otro.valor);
    }

    @Override
    public boolean equals(Object otro) {
        return otro instanceof ValorNormativo valorNormativo
                && valor.compareTo(valorNormativo.valor) == 0;
    }

    @Override
    public int hashCode() {
        return valor.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return valor.toPlainString();
    }
}
