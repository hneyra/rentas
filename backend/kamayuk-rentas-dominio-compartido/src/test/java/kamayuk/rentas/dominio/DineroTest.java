package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Dinero")
class DineroTest {

    @Nested
    @DisplayName("no decide su escala ni su redondeo (D-03)")
    class NoDecideSuEscala {

        @Test
        @DisplayName("conserva la escala con la que se construyo")
        void conservaLaEscalaConLaQueSeConstruyo() {
            assertThat(Dinero.de("10.5").valor().scale()).isEqualTo(1);
            assertThat(Dinero.de("10.500").valor().scale()).isEqualTo(3);
            assertThat(Dinero.de(10).valor().scale()).isZero();
        }

        @Test
        @DisplayName("la suma no redondea por su cuenta")
        void laSumaNoRedondeaPorSuCuenta() {
            Dinero resultado = Dinero.de("0.005").mas(Dinero.de("0.005"));

            assertThat(resultado.valor())
                    .as(
                            "si Dinero redondeara solo, aqui saldria 0.01 o 0.00 segun una decision"
                                    + " que nadie ha tomado todavia")
                    .isEqualByComparingTo("0.010");
        }

        @Test
        @DisplayName("redondea solo cuando se le da la politica")
        void redondeaSoloCuandoSeLeDaLaPolitica() {
            Dinero importe = Dinero.de("10.555");

            assertThat(importe.redondeadoCon(new PoliticaDeRedondeo(2, RoundingMode.HALF_UP)))
                    .isEqualTo(Dinero.de("10.56"));
            assertThat(importe.redondeadoCon(new PoliticaDeRedondeo(2, RoundingMode.DOWN)))
                    .isEqualTo(Dinero.de("10.55"));
            assertThat(importe.redondeadoCon(new PoliticaDeRedondeo(0, RoundingMode.HALF_UP)))
                    .isEqualTo(Dinero.de("11"));
        }
    }

    @Nested
    @DisplayName("igualdad por valor, no por representacion")
    class IgualdadPorValor {

        @Test
        @DisplayName("1.0 y 1.00 son el mismo importe")
        void mismoImporteConDistintaEscala() {
            assertThat(Dinero.de("1.0")).isEqualTo(Dinero.de("1.00"));
            assertThat(Dinero.de("1.0").hashCode()).isEqualTo(Dinero.de("1.00").hashCode());
        }

        @Test
        @DisplayName("un conjunto no guarda dos veces el mismo importe")
        void unConjuntoNoGuardaDosVecesElMismoImporte() {
            assertThat(new HashSet<>(List.of(Dinero.de("1.0"), Dinero.de("1.00"))))
                    .as(
                            "con el equals de BigDecimal habria dos elementos, y ese es el defecto"
                                    + " que este equals evita")
                    .hasSize(1);
        }

        @Test
        @DisplayName("no es igual a otra cosa")
        void noEsIgualAOtraCosa() {
            assertThat(Dinero.de("1.00")).isNotEqualTo(new BigDecimal("1.00")).isNotEqualTo(null);
        }
    }

    @Nested
    @DisplayName("aritmetica")
    class Aritmetica {

        @Test
        @DisplayName("suma y resta con precision exacta")
        void sumaYRestaConPrecisionExacta() {
            assertThat(Dinero.de("0.1").mas(Dinero.de("0.2")))
                    .as("en double esto no da 0.3, y es el motivo de la regla 1")
                    .isEqualTo(Dinero.de("0.3"));
            assertThat(Dinero.de("10").menos(Dinero.de("3.5"))).isEqualTo(Dinero.de("6.5"));
        }

        @Test
        @DisplayName("negado y absoluto")
        void negadoYAbsoluto() {
            assertThat(Dinero.de("5").negado()).isEqualTo(Dinero.de("-5"));
            assertThat(Dinero.de("-5").absoluto()).isEqualTo(Dinero.de("5"));
        }

        @Test
        @DisplayName("signo y comparacion")
        void signoYComparacion() {
            assertThat(Dinero.CERO.esCero()).isTrue();
            assertThat(Dinero.de("1").esPositivo()).isTrue();
            assertThat(Dinero.de("-1").esNegativo()).isTrue();
            assertThat(Dinero.de("2").esMayorQue(Dinero.de("1"))).isTrue();
            assertThat(Dinero.de("1").esMenorQue(Dinero.de("2"))).isTrue();
            assertThat(Dinero.de("1.0")).isEqualByComparingTo(Dinero.de("1.000"));
        }
    }

    @Test
    @DisplayName("no admite un valor nulo")
    void noAdmiteUnValorNulo() {
        assertThatThrownBy(() -> new Dinero(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("se imprime sin notacion cientifica")
    void seImprimeSinNotacionCientifica() {
        assertThat(Dinero.de("0.0000001").toString())
                .as("toString de BigDecimal usaria 1E-7, que en un recibo no significa nada")
                .isEqualTo("0.0000001");
    }
}
