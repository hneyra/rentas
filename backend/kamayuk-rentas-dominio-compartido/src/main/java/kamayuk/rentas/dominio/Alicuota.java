package kamayuk.rentas.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Porcentaje con el que se grava una base imponible.
 *
 * <p>Se llama <b>alicuota</b> y jamas «tasa» (regla 8): en el dominio tributario peruano una tasa
 * es un <i>tipo de tributo</i> —arbitrios, derechos, licencias—, no un porcentaje. Llamar «tasa» a
 * un 0,2 % produce conversaciones donde nadie sabe de que se habla.
 *
 * <p>El rango es el del dominio {@code alicuota} de PostgreSQL: de 0 a 100 inclusive, cuatro
 * decimales. Se admite el 0 porque una alicuota puede ser nula por beneficio; lo que no se admite
 * es que la restriccion dependa de cual de las dos capas se revise.
 *
 * <p><b>No sabe aplicarse a nada.</b> Multiplicar una base por una alicuota, con su redondeo y su
 * orden dentro del calculo, es una regla tributaria: vive en su contexto y esta bloqueada por D-02a
 * y por D-03.
 */
public record Alicuota(BigDecimal valor) implements Comparable<Alicuota> {

    private static final BigDecimal MINIMO = BigDecimal.ZERO;
    private static final BigDecimal MAXIMO = new BigDecimal("100");

    public Alicuota {
        Objects.requireNonNull(valor, "Una alicuota no puede ser nula");
        if (valor.compareTo(MINIMO) < 0 || valor.compareTo(MAXIMO) > 0) {
            throw new IllegalArgumentException(
                    "Alicuota fuera de rango: " + valor.toPlainString() + ". Se admite de 0 a 100");
        }
    }

    /** Alicuota a partir de su representacion decimal en texto, en tanto por ciento. */
    public static Alicuota de(String texto) {
        return new Alicuota(new BigDecimal(texto));
    }

    public boolean esCero() {
        return valor.signum() == 0;
    }

    @Override
    public int compareTo(Alicuota otra) {
        return valor.compareTo(otra.valor);
    }

    @Override
    public boolean equals(Object otra) {
        return otra instanceof Alicuota alicuota && valor.compareTo(alicuota.valor) == 0;
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
