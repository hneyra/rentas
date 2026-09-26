package kamayuk.rentas.nucleo.dominio.vehicular;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#32 — ImpuestoVehicular: base imponible por alicuota, con el minimo")
class ImpuestoVehicularTest {

    /** La politica de ADR-0018: escala 2, HALF_UP. Aqui es un dato de la prueba. */
    private static final PoliticaDeRedondeo ADR_0018 =
            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

    @Test
    @DisplayName("aplica la alicuota sobre el valor referencial")
    void aplicaLaAlicuotaSobreElValorReferencial() {
        Dinero resultado =
                ImpuestoVehicular.calcular(
                        base("10000.00"), Alicuota.de("1.0"), Dinero.CERO, ADR_0018);
        assertThat(resultado).isEqualTo(Dinero.de("100.00"));
    }

    @Test
    @DisplayName("el minimo imponible sustituye el calculo cuando este no lo alcanza")
    void aplicaElMinimoCuandoElCalculoNoLoAlcanza() {
        Dinero resultado =
                ImpuestoVehicular.calcular(
                        base("100.00"), Alicuota.de("1.0"), Dinero.de("50.00"), ADR_0018);
        assertThat(resultado).isEqualTo(Dinero.de("50.00"));
    }

    @Test
    @DisplayName("el minimo nunca reduce un calculo que ya lo supera")
    void elMinimoNuncaReduceElCalculo() {
        Dinero resultado =
                ImpuestoVehicular.calcular(
                        base("10000.00"), Alicuota.de("1.0"), Dinero.de("1.00"), ADR_0018);
        assertThat(resultado).isEqualTo(Dinero.de("100.00"));
    }

    @Test
    @DisplayName("una alicuota nula no se admite: no hay valor por omision")
    void unaAlicuotaNulaNoSeAdmite() {
        assertThatThrownBy(
                        () -> ImpuestoVehicular.calcular(base("1000"), null, Dinero.CERO, ADR_0018))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * #378 — El escenario del issue: 112 845,50 × 1 % = 1 128,455, medio centimo exacto.
     *
     * <p>Hasta #378 la respuesta decia «1128.4550» y la fila guardaba 1128.46. Con {@code HALF_UP}
     * sellado la regla da 1128.46; con {@code HALF_EVEN}, 1128.46 tambien (el 5 no es par) —por eso
     * la segunda comprobacion usa {@code DOWN}, que da 1128.45 y demuestra que el modo es el que
     * llega—.
     */
    @Test
    @DisplayName("#378 — 112 845,50 al 1 % sale como 1128.46, con el modo que recibe")
    void redondeaAlCierreConElModoQueRecibe() {
        assertThat(
                        ImpuestoVehicular.calcular(
                                        base("112845.50"),
                                        Alicuota.de("1.0"),
                                        Dinero.CERO,
                                        ADR_0018)
                                .valor()
                                .toPlainString())
                .isEqualTo("1128.46");
        assertThat(
                        ImpuestoVehicular.calcular(
                                        base("112845.50"),
                                        Alicuota.de("1.0"),
                                        Dinero.CERO,
                                        new PoliticaDeRedondeo(2, RoundingMode.DOWN))
                                .valor()
                                .toPlainString())
                .isEqualTo("1128.45");
    }

    /** Una base del art. 32 sin adquisicion: la tabla, dicha como tal (#330). */
    private static BaseImponibleVehicular base(String tabla) {
        return BaseImponibleVehicular.segunArticulo32(null, Dinero.de(tabla));
    }
}
