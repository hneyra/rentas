package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Politica de redondeo (D-03)")
class PoliticaDeRedondeoTest {

    @Test
    @DisplayName("no existe ninguna politica por omision")
    void noExisteNingunaPoliticaPorOmision() {
        // La asercion mira el propio tipo: si alguien agrega una constante publica
        // —POR_OMISION, CENTIMOS, HALF_UP— D-03 habria quedado decidida por descuido
        // y en el sitio equivocado. La respuesta va en los datos de parametrizacion.
        assertThat(Arrays.stream(PoliticaDeRedondeo.class.getDeclaredFields()).toList())
                .as("mientras D-03 siga abierta, la politica se recibe; no se conoce")
                .noneSatisfy(
                        campo -> assertThat(campo.getType()).isEqualTo(PoliticaDeRedondeo.class));
    }

    @Test
    @DisplayName("la escala no puede ser negativa")
    void laEscalaNoPuedeSerNegativa() {
        assertThatThrownBy(() -> new PoliticaDeRedondeo(-1, RoundingMode.HALF_UP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negativa");
    }

    @Test
    @DisplayName("UNNECESSARY no es una politica de redondeo")
    void unnecessaryNoEsUnaPolitica() {
        assertThatThrownBy(() -> new PoliticaDeRedondeo(2, RoundingMode.UNNECESSARY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNNECESSARY");
    }

    @Test
    @DisplayName("el modo es obligatorio")
    void elModoEsObligatorio() {
        assertThatThrownBy(() -> new PoliticaDeRedondeo(2, null))
                .isInstanceOf(NullPointerException.class);
    }
}
