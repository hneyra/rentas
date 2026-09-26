package kamayuk.rentas.nucleo.dominio.alcabala;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.RoundingMode;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#32 — ImpuestoDeAlcabala: alicuota sobre el exceso del tramo inafecto")
class ImpuestoDeAlcabalaTest {

    /** La politica de ADR-0018: escala 2, HALF_UP. Aqui es un dato de la prueba. */
    private static final PoliticaDeRedondeo ADR_0018 =
            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

    @Test
    @DisplayName("grava solo el excedente del tramo inafecto")
    void gravaSoloElExcedente() {
        Dinero resultado =
                ImpuestoDeAlcabala.calcular(
                        Dinero.de("50000.00"), Dinero.de("46000.00"), Alicuota.de("3.0"), ADR_0018);
        // (50000 - 46000) * 3% = 4000 * 0.03 = 120.00
        assertThat(resultado).isEqualTo(Dinero.de("120.00"));
    }

    @Test
    @DisplayName("una base que no supera el tramo inafecto no genera impuesto")
    void noGeneraImpuestoBajoElTramo() {
        Dinero resultado =
                ImpuestoDeAlcabala.calcular(
                        Dinero.de("40000.00"), Dinero.de("46000.00"), Alicuota.de("3.0"), ADR_0018);
        assertThat(resultado).isEqualTo(Dinero.CERO);
    }

    @Test
    @DisplayName("una base exactamente igual al tramo inafecto no genera impuesto")
    void unaBaseIgualAlTramoNoGeneraImpuesto() {
        Dinero resultado =
                ImpuestoDeAlcabala.calcular(
                        Dinero.de("46000.00"), Dinero.de("46000.00"), Alicuota.de("3.0"), ADR_0018);
        assertThat(resultado).isEqualTo(Dinero.CERO);
    }

    /**
     * #378 — El escenario del issue: base 123 456,78 con UIT 5 500, tramo de 10 UIT.
     *
     * <p>Excedente 68 456,78 × 3 % = 2 053,7034. Hasta #378 esa era la cifra que salia en el 201 y
     * en la auditoria, y la columna guardaba 2 053,70.
     */
    @Test
    @DisplayName("#378 — 68 456,78 × 3 % = 2053.7034 sale como 2053.70")
    void redondeaAlCierre() {
        Dinero resultado =
                ImpuestoDeAlcabala.calcular(
                        Dinero.de("123456.78"),
                        Dinero.de("55000.00"),
                        Alicuota.de("3.0"),
                        ADR_0018);
        assertThat(resultado.valor().toPlainString()).isEqualTo("2053.70");
    }
}
