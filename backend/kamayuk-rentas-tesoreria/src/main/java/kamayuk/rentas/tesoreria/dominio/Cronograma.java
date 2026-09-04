package kamayuk.rentas.tesoreria.dominio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;

/**
 * El cronograma de un convenio: la cuota inicial y las N cuotas, con su capital y su interes (#35,
 * RF-084).
 *
 * <h2>Funcion pura</h2>
 *
 * <p>Regla 6: todo lo que necesita entra como argumento —lo acogido, las {@link
 * CondicionesDelConvenio}, cuantas cuotas, cuando vence la primera y la {@link PoliticaDeRedondeo}
 * del punto—. Sin base de datos, sin reloj y sin configuracion global: los mismos argumentos dan el
 * mismo centimo hoy y dentro de diez anios, que es lo que permite reimprimir un compromiso de pago
 * de 2027 en 2037 y que salga igual.
 *
 * <p>Y sin ninguna cifra dentro. El interes y el maximo de cuotas llegan en {@code condiciones}
 * (regla 5, D-02b); la escala y el modo del redondeo llegan en {@code redondeo}, resueltos del
 * conjunto sellado por el punto {@link kamayuk.rentas.dominio.PuntoDeRedondeo#CUOTA} (D-03, E-7
 * §3).
 *
 * <h2>Como se reparte</h2>
 *
 * <p>Sobre <b>saldo insoluto decreciente</b>, que es lo que significa un «interes de
 * fraccionamiento <i>mensual</i>»: el capital se divide en partes iguales y cada cuota devenga el
 * interes del saldo que quedaba antes de pagarla. La primera paga interes sobre todo el capital
 * fraccionado y la ultima sobre una sola parte.
 *
 * <p><b>El metodo es una decision, no una cifra, y esta anotada.</b> Lo que este issue tenia
 * desbloqueado es el mecanismo con las cifras como parametro; que la amortizacion sea de saldo
 * decreciente —y no francesa, ni con el interes calculado de una vez sobre el capital total— sale
 * de como la ordenanza tipo describe el interes, y es lo que #191 tiene que confirmar junto con su
 * valor. Se anota aqui, en el sitio donde se aplicaria el cambio, y no en un documento aparte.
 *
 * <p>El <b>descuadre del reparto</b> lo absorbe la ultima cuota. Dividir 100,00 en tres deja 33,33
 * tres veces y un centimo huerfano; repartirlo «a prorrata» lo esconde, y dejarlo fuera hace que la
 * suma del cronograma no sea la deuda acogida —y entonces el convenio cobra un centimo de menos, en
 * cada convenio, para siempre—.
 */
public final class Cronograma {

    private static final BigDecimal CIEN = new BigDecimal("100");

    /**
     * La division intermedia se hace con precision de sobra y se redondea despues, con la politica
     * que entro: dividir ya con la escala final acumularia el error en cada cuota en vez de dejarlo
     * en la ultima.
     *
     * <p>No es una decision de redondeo disfrazada —D-03 sigue viviendo en {@code redondeo}—: es el
     * ancho del calculo intermedio, el mismo criterio con el que el esquema define el dominio
     * {@code monto_calc numeric(18,6)} frente a {@code dinero numeric(15,2)}.
     */
    private static final java.math.MathContext INTERMEDIO = java.math.MathContext.DECIMAL64;

    private Cronograma() {}

    /**
     * El cronograma completo: la cuota 0 (la inicial, si la hay) y las cuotas 1..N.
     *
     * @param acogido la deuda que se fracciona, a su fecha de corte
     * @param condiciones el interes, el maximo de cuotas y el porcentaje de inicial (regla 5)
     * @param cuotas cuantas cuotas se piden, sin contar la inicial
     * @param primeraCuotaVence el vencimiento de la cuota 1; las siguientes van mes a mes
     * @param redondeo la politica del punto {@code CUOTA}, resuelta del conjunto sellado (D-03)
     * @throws CondicionesDelConvenio.DemasiadasCuotas si se piden mas de las admitidas
     * @throws NadaQueFraccionar si lo acogido no es positivo
     */
    public static List<CuotaDeConvenio> de(
            Dinero acogido,
            CondicionesDelConvenio condiciones,
            int cuotas,
            LocalDate primeraCuotaVence,
            PoliticaDeRedondeo redondeo) {

        Objects.requireNonNull(acogido, "El cronograma fracciona un importe concreto");
        Objects.requireNonNull(condiciones, "El cronograma necesita sus condiciones (regla 5)");
        Objects.requireNonNull(primeraCuotaVence, "La primera cuota vence en una fecha concreta");
        Objects.requireNonNull(redondeo, "La politica de redondeo se recibe, no se fija (D-03)");
        condiciones.exigirQueQuepa(cuotas);
        if (!acogido.esPositivo()) {
            throw new NadaQueFraccionar(acogido);
        }

        Dinero inicial =
                acogido.por(condiciones.porcentajeInicial().valor().divide(CIEN, INTERMEDIO))
                        .redondeadoCon(redondeo);
        // Una inicial que se comiera toda la deuda dejaria un convenio de cero cuotas,
        // que no es un convenio: es un pago. Se acota antes de repartir.
        if (inicial.esMayorQue(acogido)) {
            inicial = acogido;
        }
        Dinero aFraccionar = acogido.menos(inicial);
        if (!aFraccionar.esPositivo()) {
            throw new NadaQueFraccionar(aFraccionar);
        }

        List<CuotaDeConvenio> cronograma = new ArrayList<>(cuotas + 1);
        if (inicial.esPositivo()) {
            // La inicial vence el dia del convenio -se paga en el acto- y no devenga
            // interes: no financia nada.
            cronograma.add(
                    new CuotaDeConvenio(0, primeraCuotaVence, inicial, Dinero.CERO, Dinero.CERO));
        }

        Dinero capitalPorCuota =
                aFraccionar
                        .por(BigDecimal.ONE.divide(BigDecimal.valueOf(cuotas), INTERMEDIO))
                        .redondeadoCon(redondeo);
        BigDecimal tipo = condiciones.interesMensual().valor().divide(CIEN, INTERMEDIO);

        Dinero saldo = aFraccionar;
        Dinero repartido = Dinero.CERO;
        for (int numero = 1; numero <= cuotas; numero++) {
            boolean ultima = numero == cuotas;
            Dinero capital = ultima ? aFraccionar.menos(repartido) : capitalPorCuota;
            Dinero interes = saldo.por(tipo).redondeadoCon(redondeo);
            cronograma.add(
                    new CuotaDeConvenio(
                            numero,
                            primeraCuotaVence.plusMonths(numero - 1L),
                            capital,
                            interes,
                            Dinero.CERO));
            repartido = repartido.mas(capital);
            saldo = saldo.menos(capital);
        }
        return List.copyOf(cronograma);
    }

    /** La suma de las cuotas del cronograma, incluida la inicial. */
    public static Dinero total(List<CuotaDeConvenio> cronograma) {
        Dinero total = Dinero.CERO;
        for (CuotaDeConvenio cuota : cronograma) {
            total = total.mas(cuota.monto());
        }
        return total;
    }

    /** La cuota inicial del cronograma; cero si no la hay. */
    public static Dinero inicialDe(List<CuotaDeConvenio> cronograma) {
        for (CuotaDeConvenio cuota : cronograma) {
            if (cuota.esInicial()) {
                return cuota.monto();
            }
        }
        return Dinero.CERO;
    }

    /** No hay nada que fraccionar: la deuda acogida es cero o la inicial se la come entera. */
    public static final class NadaQueFraccionar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        NadaQueFraccionar(Dinero importe) {
            super(
                    "No hay deuda que fraccionar: "
                            + importe
                            + ". Un convenio sobre cero cuotas no es un convenio, es un pago");
        }
    }
}
