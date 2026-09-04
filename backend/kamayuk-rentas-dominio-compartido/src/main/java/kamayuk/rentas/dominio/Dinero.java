package kamayuk.rentas.dominio;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Un importe.
 *
 * <p>Sobre {@link BigDecimal} y nunca sobre {@code double} ni {@code float} (regla 1, RNF-055): en
 * coma flotante {@code 0.1 + 0.2} no es {@code 0.3}, y un padron entero de recibos con un centimo
 * de diferencia es una conciliacion que no cuadra nunca.
 *
 * <h2>Lo que este tipo NO decide</h2>
 *
 * <p><b>Su escala ni su modo de redondeo.</b> Los recibe en {@link #redondeadoCon}, porque D-03
 * sigue abierta en sus tres partes: con cuantos decimales se trabaja (D-03a), con que modo (D-03b)
 * y en que puntos del calculo se redondea (D-03c). Un {@code setScale(2, HALF_UP)} escrito hoy
 * dentro de este tipo seria una decision tomada por descuido y repartida por todo el sistema.
 *
 * <p><b>Cuanto se debe.</b> Aqui hay aritmetica —sumar, restar, comparar—, no reglas tributarias.
 * Toda operacion que devuelva un importe <i>determinado</i> (una alicuota aplicada a una base, un
 * tramo progresivo, un interes moratorio) es una regla de calculo, vive en su contexto acotado y
 * esta bloqueada por D-02a.
 *
 * <h2>Igualdad</h2>
 *
 * <p>{@code equals} compara <b>valor</b>, no representacion: {@code 1.0} y {@code 1.00} son el
 * mismo importe. {@link BigDecimal#equals(Object)} dice que no, y esa diferencia se cuela en un
 * {@code Set} o en un {@code assertEquals} y se descubre tarde.
 */
public record Dinero(BigDecimal valor) implements Comparable<Dinero> {

    /** El unico importe que no depende de ninguna decision abierta. */
    public static final Dinero CERO = new Dinero(BigDecimal.ZERO);

    public Dinero {
        Objects.requireNonNull(valor, "Un importe no puede ser nulo");
    }

    /**
     * Importe a partir de su representacion decimal en texto.
     *
     * <p>Texto y no {@code double} a proposito: {@code new BigDecimal(0.1)} guarda {@code
     * 0.1000000000000000055511151231257827021181583404541015625}.
     */
    public static Dinero de(String texto) {
        return new Dinero(new BigDecimal(texto));
    }

    /** Importe en unidades enteras. */
    public static Dinero de(long unidades) {
        return new Dinero(BigDecimal.valueOf(unidades));
    }

    public Dinero mas(Dinero otro) {
        return new Dinero(valor.add(otro.valor));
    }

    public Dinero menos(Dinero otro) {
        return new Dinero(valor.subtract(otro.valor));
    }

    public Dinero negado() {
        return new Dinero(valor.negate());
    }

    /**
     * El importe multiplicado por un factor, <b>sin redondear</b>.
     *
     * <p>Casi todo el calculo tributario es una multiplicacion —area por arancel, base por
     * alicuota, valor unitario por metrado— y el producto trae mas decimales que los dos operandos.
     * Devolverlo redondeado obligaria a esta clase a elegir escala y modo, que es justo lo que
     * D-03a y D-03b no han decidido, y a redondear en cada operacion intermedia en vez de donde
     * toque, que es D-03c (ARQ-09 §1.4). Quien multiplica decide cuando redondear, con {@link
     * #redondeadoCon(PoliticaDeRedondeo)} y la politica que recibio.
     */
    public Dinero por(BigDecimal factor) {
        Objects.requireNonNull(factor, "Multiplicar exige su factor");
        return new Dinero(valor.multiply(factor));
    }

    /** Valor absoluto. Util para presentar un abono, que en el libro va en negativo. */
    public Dinero absoluto() {
        return new Dinero(valor.abs());
    }

    /**
     * El mismo importe con la escala y el modo que indique la politica.
     *
     * <p>La politica entra como argumento: ver {@link PoliticaDeRedondeo} y D-03a/D-03b.
     */
    public Dinero redondeadoCon(PoliticaDeRedondeo politica) {
        return new Dinero(politica.aplicarA(valor));
    }

    /**
     * El mismo importe, redondeado con la politica que corresponde a <b>ese punto</b> del calculo.
     *
     * <p>Es la forma que pide D-03c: no «redondea», sino «redondea aqui, porque aqui el SRTM del
     * MEF redondea». Si el punto no tiene politica no se devuelve el importe sin tocar —eso seria
     * una cifra plausible y equivocada—: falla con {@code PuntoSinPolitica}.
     */
    public Dinero redondeadoEn(PuntoDeRedondeo punto, PoliticasDeRedondeo politicas) {
        Objects.requireNonNull(politicas, "El redondeo por punto necesita su parametrizacion");
        return redondeadoCon(politicas.en(punto));
    }

    public boolean esCero() {
        return valor.signum() == 0;
    }

    public boolean esPositivo() {
        return valor.signum() > 0;
    }

    public boolean esNegativo() {
        return valor.signum() < 0;
    }

    public boolean esMayorQue(Dinero otro) {
        return compareTo(otro) > 0;
    }

    public boolean esMenorQue(Dinero otro) {
        return compareTo(otro) < 0;
    }

    @Override
    public int compareTo(Dinero otro) {
        return valor.compareTo(otro.valor);
    }

    @Override
    public boolean equals(Object otro) {
        return otro instanceof Dinero dinero && valor.compareTo(dinero.valor) == 0;
    }

    @Override
    public int hashCode() {
        // stripTrailingZeros para que 1.0 y 1.00 caigan en el mismo cubo, como exige
        // el equals de arriba. Sin esto, un HashSet los trata como distintos.
        return valor.stripTrailingZeros().hashCode();
    }

    @Override
    public String toString() {
        return valor.toPlainString();
    }
}
