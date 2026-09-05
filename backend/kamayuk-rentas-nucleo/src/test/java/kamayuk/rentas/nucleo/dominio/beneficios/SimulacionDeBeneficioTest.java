package kamayuk.rentas.nucleo.dominio.beneficios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import java.util.List;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #72 — La simulacion del acogimiento, <b>sin base de datos y sin reloj</b> (regla 6).
 *
 * <p>Lo que aqui se verifica no es que la multiplicacion este bien hecha —eso lo hace {@code
 * BigDecimal}—, sino las tres decisiones que la rodean y que sin prueba se pierden:
 *
 * <ul>
 *   <li>que el descuento corre sobre <b>la parte que dice la ordenanza</b>, no sobre el total por
 *       omision;
 *   <li>que se redondea con <b>la politica de la campana</b>, no con una escrita en el codigo;
 *   <li>que un descuento no puede dejar la deuda en negativo.
 * </ul>
 *
 * <p>Ninguna de las cifras de esta clase es normativa: son datos de prueba, y estan en {@code
 * src/test} justamente porque la ordenanza que traera las de verdad es D-02b.
 */
@DisplayName("#72 — Simulacion del acogimiento a una campana")
class SimulacionDeBeneficioTest {

    private static final PoliticaDeRedondeo DOS_DECIMALES =
            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

    /** Insoluto 800, reajuste 20, interes 160, gasto 20. Total 1 000. */
    private static final DesgloseAcogido UNA =
            new DesgloseAcogido(
                    Dinero.de("800.00"),
                    Dinero.de("20.00"),
                    Dinero.de("160.00"),
                    Dinero.de("20.00"));

    /** Insoluto 100, reajuste 5, interes 40, gasto 5. Total 150. */
    private static final DesgloseAcogido OTRA =
            new DesgloseAcogido(
                    Dinero.de("100.00"), Dinero.de("5.00"), Dinero.de("40.00"), Dinero.de("5.00"));

    @Test
    @DisplayName("la base es la que dice la ordenanza: sobre el total")
    void sobreElTotal() {
        AcogimientoSimulado acogimiento =
                SimulacionDeBeneficio.de(
                        List.of(UNA, OTRA), campania("50", BaseDelBeneficio.TOTAL));

        assertThat(acogimiento.baseDelBeneficio()).isEqualTo(Dinero.de("1150.00"));
        assertThat(acogimiento.ahorro()).isEqualTo(Dinero.de("575.00"));
        assertThat(acogimiento.deudaConBeneficio()).isEqualTo(Dinero.de("575.00"));
    }

    @Test
    @DisplayName("sobre el reajuste y el interes: la amnistia que condona la mora")
    void sobreLaMora() {
        AcogimientoSimulado acogimiento =
                SimulacionDeBeneficio.de(
                        List.of(UNA, OTRA), campania("100", BaseDelBeneficio.REAJUSTE_E_INTERES));

        assertThat(acogimiento.baseDelBeneficio())
                .as("20 + 160 de una, 5 + 40 de la otra")
                .isEqualTo(Dinero.de("225.00"));
        assertThat(acogimiento.ahorro()).isEqualTo(Dinero.de("225.00"));
        assertThat(acogimiento.deudaConBeneficio())
                .as("queda el insoluto y los gastos, que la ordenanza no condona")
                .isEqualTo(Dinero.de("925.00"));
    }

    @Test
    @DisplayName("sobre el insoluto: el descuento por pronto pago")
    void sobreElInsoluto() {
        AcogimientoSimulado acogimiento =
                SimulacionDeBeneficio.de(List.of(UNA), campania("10", BaseDelBeneficio.INSOLUTO));

        assertThat(acogimiento.baseDelBeneficio()).isEqualTo(Dinero.de("800.00"));
        assertThat(acogimiento.ahorro()).isEqualTo(Dinero.de("80.00"));
        assertThat(acogimiento.deudaConBeneficio()).isEqualTo(Dinero.de("920.00"));
    }

    @Test
    @DisplayName("redondea con la politica de la campana, y con ninguna otra")
    void redondeaConLaDeLaCampania() {
        DesgloseAcogido impar =
                new DesgloseAcogido(Dinero.de("797.77"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

        // 797.77 x 12,5 % = 99.72125
        AcogimientoSimulado alAlza =
                SimulacionDeBeneficio.de(
                        List.of(impar),
                        new CampaniaDeBeneficio(
                                "PRUEBA",
                                Alicuota.de("12.5"),
                                BaseDelBeneficio.TOTAL,
                                new PoliticaDeRedondeo(2, RoundingMode.UP)));
        AcogimientoSimulado aLaBaja =
                SimulacionDeBeneficio.de(
                        List.of(impar),
                        new CampaniaDeBeneficio(
                                "PRUEBA",
                                Alicuota.de("12.5"),
                                BaseDelBeneficio.TOTAL,
                                new PoliticaDeRedondeo(2, RoundingMode.DOWN)));

        assertThat(alAlza.ahorro()).isEqualTo(Dinero.de("99.73"));
        assertThat(aLaBaja.ahorro())
                .as("la misma base y la misma alicuota con otro modo dan otro centimo")
                .isEqualTo(Dinero.de("99.72"));
        assertThat(alAlza.deudaConBeneficio())
                .as("y lo que queda sale de la resta, no de un segundo redondeo")
                .isEqualTo(Dinero.de("698.04"));
    }

    @Test
    @DisplayName("un redondeo al alza no deja la deuda en negativo")
    void nuncaNegativa() {
        DesgloseAcogido casiNada =
                new DesgloseAcogido(Dinero.de("0.01"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

        AcogimientoSimulado acogimiento =
                SimulacionDeBeneficio.de(
                        List.of(casiNada),
                        new CampaniaDeBeneficio(
                                "PRUEBA",
                                Alicuota.de("100"),
                                BaseDelBeneficio.TOTAL,
                                new PoliticaDeRedondeo(0, RoundingMode.UP)));

        assertThat(acogimiento.ahorro())
                .as("1 sol de descuento sobre 1 centimo de deuda se acota a lo que se debe")
                .isEqualTo(Dinero.de("0.01"));
        assertThat(acogimiento.deudaConBeneficio()).isEqualTo(Dinero.CERO);
    }

    @Test
    @DisplayName("sin obligaciones acogidas no hay nada que descontar")
    void sinObligaciones() {
        AcogimientoSimulado acogimiento =
                SimulacionDeBeneficio.de(List.of(), campania("50", BaseDelBeneficio.TOTAL));

        assertThat(acogimiento.ahorro()).isEqualTo(Dinero.CERO);
        assertThat(acogimiento.deudaConBeneficio()).isEqualTo(Dinero.CERO);
        assertThat(SimulacionDeBeneficio.acogida(List.of())).isEqualTo(Dinero.CERO);
    }

    @Test
    @DisplayName("la deuda acogida se suma igual con campana y sin ella")
    void laAcogidaEsLaMisma() {
        assertThat(SimulacionDeBeneficio.acogida(List.of(UNA, OTRA)))
                .isEqualTo(Dinero.de("1150.00"));
    }

    @Test
    @DisplayName("una campana sin nombre no se puede elegir")
    void sinNombre() {
        assertThatThrownBy(
                        () ->
                                new CampaniaDeBeneficio(
                                        "  ",
                                        Alicuota.de("50"),
                                        BaseDelBeneficio.TOTAL,
                                        DOS_DECIMALES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CampaniaDeBeneficio campania(String alicuota, BaseDelBeneficio base) {
        return new CampaniaDeBeneficio(
                "CAMPANIA DE PRUEBA", Alicuota.de(alicuota), base, DOS_DECIMALES);
    }
}
