package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Porcentaje")
class PorcentajeTest {

    @ParameterizedTest
    @ValueSource(strings = {"0.0001", "33.3333", "50", "100"})
    @DisplayName("admite el rango del dominio porcentaje de PostgreSQL")
    void admiteElRangoDelDominio(String valor) {
        assertThat(Porcentaje.de(valor).valor()).isEqualByComparingTo(valor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "100.0001"})
    @DisplayName("rechaza el cero y lo que pasa de 100")
    void rechazaElCeroYLoQuePasaDeCien(String valor) {
        assertThatThrownBy(() -> Porcentaje.de(valor))
                .as(
                        "un titular con 0 %% de propiedad no es titular; ahi esta la diferencia"
                                + " con Alicuota, que si admite el cero")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el total es el caso del titular unico")
    void elTotalEsElTitularUnico() {
        assertThat(Porcentaje.total().esTotal()).isTrue();
        assertThat(Porcentaje.total()).isEqualTo(Porcentaje.de("100.00"));
    }
}
