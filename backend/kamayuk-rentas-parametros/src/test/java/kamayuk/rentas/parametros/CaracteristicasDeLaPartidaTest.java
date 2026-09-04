package kamayuk.rentas.parametros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lo que la partida es y no cuanto vale: la llave con que una regla de valuacion busca su
 * parametro.
 *
 * <p>Sin esto ninguna regla de valuacion tiene forma: el arancel es por via, el valor unitario por
 * categoria y ano, la depreciacion por material y estado. Con una clave constante en el codigo solo
 * se pueden escribir las reglas cuyo parametro es uno por ejercicio.
 */
@DisplayName("CaracteristicasDeLaPartida")
class CaracteristicasDeLaPartidaTest {

    @Test
    @DisplayName("el nombre no distingue mayusculas ni espacios de sobra")
    void elNombreSeNormaliza() {
        CaracteristicasDeLaPartida caracteristicas =
                CaracteristicasDeLaPartida.de("  Via ", "AV-GRAU").construir();

        assertThat(caracteristicas.exigir("via")).isEqualTo("AV-GRAU");
        assertThat(caracteristicas.exigir("VIA")).isEqualTo("AV-GRAU");
        assertThat(caracteristicas.nombres()).containsExactly("via");
    }

    @Test
    @DisplayName("una caracteristica ausente falla nombrando la que falta y las que hay")
    void unaCaracteristicaAusenteFalla() {
        CaracteristicasDeLaPartida soloLaVia =
                CaracteristicasDeLaPartida.de("via", "AV-GRAU").construir();

        assertThatThrownBy(() -> soloLaVia.exigir("categoria"))
                .isInstanceOf(CaracteristicasDeLaPartida.CaracteristicaAusente.class)
                .hasMessageContaining("categoria")
                .hasMessageContaining("via");
    }

    @Test
    @DisplayName("preguntar por una caracteristica no la inventa")
    void preguntarNoInventa() {
        CaracteristicasDeLaPartida caracteristicas =
                CaracteristicasDeLaPartida.de("via", "AV-GRAU").construir();

        assertThat(caracteristicas.valor("via")).contains("AV-GRAU");
        assertThat(caracteristicas.valor("material")).isEmpty();
    }

    @Test
    @DisplayName("una partida puede no tener ninguna: la regla que no las necesita no las pide")
    void puedeNoTenerNinguna() {
        assertThat(CaracteristicasDeLaPartida.ninguna().nombres()).isEmpty();
        assertThatThrownBy(() -> CaracteristicasDeLaPartida.ninguna().exigir("via"))
                .isInstanceOf(CaracteristicasDeLaPartida.CaracteristicaAusente.class);
    }

    @Test
    @DisplayName("una caracteristica en blanco es no traerla, y se rechaza al construirla")
    void enBlancoEsNoTraerla() {
        assertThatThrownBy(() -> CaracteristicasDeLaPartida.de("via", "   ").construir())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("via");
    }

    @Test
    @DisplayName("son iguales por valor: dos partidas con las mismas caracteristicas son la misma")
    void igualdadPorValor() {
        assertThat(CaracteristicasDeLaPartida.de("via", "AV-GRAU").y("categoria", "A").construir())
                .isEqualTo(
                        CaracteristicasDeLaPartida.de("categoria", "A")
                                .y("via", "AV-GRAU")
                                .construir());
    }
}
