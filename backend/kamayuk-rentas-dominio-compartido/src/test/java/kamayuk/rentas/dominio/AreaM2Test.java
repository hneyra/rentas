package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Area en metros cuadrados")
class AreaM2Test {

    @Test
    @DisplayName("el cero se admite: un predio sin construir tiene area construida cero")
    void elCeroSeAdmite() {
        assertThat(AreaM2.CERO.esCero()).isTrue();
        assertThat(AreaM2.de("0.00")).isEqualTo(AreaM2.CERO);
    }

    @Test
    @DisplayName("una superficie negativa se rechaza")
    void unaSuperficieNegativaSeRechaza() {
        assertThatThrownBy(() -> AreaM2.de("-0.01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negativa");
    }

    @Test
    @DisplayName("las areas se suman: el area construida es la de sus pisos")
    void lasAreasSeSuman() {
        assertThat(AreaM2.de("50.25").mas(AreaM2.de("49.75"))).isEqualTo(AreaM2.de("100"));
    }

    @Test
    @DisplayName("igualdad por valor, no por escala")
    void igualdadPorValor() {
        assertThat(AreaM2.de("120.5")).isEqualTo(AreaM2.de("120.50"));
        assertThat(AreaM2.de("120.5").hashCode()).isEqualTo(AreaM2.de("120.50").hashCode());
    }
}
