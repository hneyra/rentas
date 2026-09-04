package kamayuk.rentas.dominio;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * Una magnitud medida, con su unidad: metros lineales de frontis, metros cuadrados de cerco,
 * unidades de tanque.
 *
 * <p>Existe por el mismo motivo que {@link Dinero}: <b>de aqui sale un importe</b>. NEG-05 §RT-005
 * calcula la obra complementaria como {@code valor_depreciado × total_metrado × factor}, asi que un
 * metrado con la escala equivocada mueve dinero. Un {@code BigDecimal} suelto en una firma no dice
 * de que es la cifra ni quien decide su escala; la regla de ArchUnit que lo prohibe en el dominio
 * existe justamente para que aparezca este tipo en vez de aquel.
 *
 * <p><b>No decide su escala ni su redondeo</b>, igual que {@code Dinero}: eso es D-03, y M02 revelo
 * que el sistema del MEF tiene un «metrado redondeado» —hay redondeo intermedio, que es D-03c—, asi
 * que fijarlo aqui seria decidir por adelantado algo que todavia se esta midiendo.
 *
 * <p>La unidad es texto y no una enumeracion a proposito: el manual admite las que traiga la
 * ordenanza, y una enumeracion cerrada obligaria a desplegar para admitir una unidad nueva.
 *
 * @param magnitud cuanto; nunca negativo
 * @param unidad de que: {@code M2}, {@code ML}, {@code UND}
 */
public record Medida(BigDecimal magnitud, String unidad) implements Comparable<Medida> {

    private static final int UNIDAD_MAXIMA = 20;

    public Medida {
        Objects.requireNonNull(magnitud, "Una medida necesita su magnitud");
        Objects.requireNonNull(unidad, "Una medida necesita su unidad");
        unidad = unidad.strip().toUpperCase(Locale.ROOT);
        if (unidad.isEmpty() || unidad.length() > UNIDAD_MAXIMA) {
            throw new IllegalArgumentException(
                    "La unidad va de 1 a " + UNIDAD_MAXIMA + " caracteres: '" + unidad + "'");
        }
        if (magnitud.signum() < 0) {
            throw new IllegalArgumentException("Una medida no puede ser negativa: " + magnitud);
        }
    }

    public static Medida de(String magnitud, String unidad) {
        return new Medida(new BigDecimal(magnitud), unidad);
    }

    /** Metros lineales: el frontis de un predio, el largo de un cerco. */
    public static Medida enMetrosLineales(String magnitud) {
        return de(magnitud, "ML");
    }

    public static Medida enMetrosCuadrados(String magnitud) {
        return de(magnitud, "M2");
    }

    public static Medida enUnidades(String cuantas) {
        return de(cuantas, "UND");
    }

    public boolean esCero() {
        return magnitud.signum() == 0;
    }

    /**
     * Suma dos medidas de la <b>misma unidad</b>. Sumar metros con unidades daria un numero que no
     * significa nada y que despues alguien multiplicaria por un valor unitario.
     */
    public Medida mas(Medida otra) {
        exigirMismaUnidad(otra);
        return new Medida(magnitud.add(otra.magnitud), unidad);
    }

    private void exigirMismaUnidad(Medida otra) {
        if (!unidad.equals(otra.unidad)) {
            throw new IllegalArgumentException(
                    "No se suman " + unidad + " con " + otra.unidad + ": no son la misma magnitud");
        }
    }

    @Override
    public int compareTo(Medida otra) {
        exigirMismaUnidad(otra);
        return magnitud.compareTo(otra.magnitud);
    }

    /** Compara por valor, no por representacion: {@code 1.0 M2} y {@code 1.00 M2} son la misma. */
    @Override
    public boolean equals(Object otra) {
        return otra instanceof Medida medida
                && unidad.equals(medida.unidad)
                && magnitud.compareTo(medida.magnitud) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(magnitud.stripTrailingZeros(), unidad);
    }

    @Override
    public String toString() {
        return magnitud.toPlainString() + " " + unidad;
    }
}
