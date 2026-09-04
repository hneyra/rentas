package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Alicuota")
class AlicuotaTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.2", "0.6", "1.0", "99.9999", "100"})
    @DisplayName("admite el rango del dominio alicuota de PostgreSQL")
    void admiteElRangoDelDominio(String valor) {
        assertThat(Alicuota.de(valor).valor()).isEqualByComparingTo(valor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.0001", "100.0001", "1000"})
    @DisplayName("rechaza lo que la columna rechazaria")
    void rechazaLoQueLaColumnaRechazaria(String valor) {
        assertThatThrownBy(() -> Alicuota.de(valor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fuera de rango");
    }

    @Test
    @DisplayName("el cero es una alicuota valida: puede serlo por beneficio")
    void elCeroEsValido() {
        assertThat(Alicuota.de("0").esCero()).isTrue();
    }

    @Test
    @DisplayName("igualdad por valor, no por escala")
    void igualdadPorValor() {
        assertThat(Alicuota.de("0.2")).isEqualTo(Alicuota.de("0.2000"));
        assertThat(Alicuota.de("0.2").hashCode()).isEqualTo(Alicuota.de("0.2000").hashCode());
    }

    @Test
    @DisplayName("no sabe aplicarse a una base: eso es regla de calculo (D-02a)")
    void noSabeAplicarseAUnaBase() {
        assertThat(Alicuota.class.getDeclaredMethods())
                .as(
                        "una alicuota que multiplica una base seria la primera regla de calculo,"
                                + " y las cifras siguen sin verificarse")
                .noneSatisfy(m -> assertThat(m.getReturnType()).isEqualTo(Dinero.class));
    }
}
