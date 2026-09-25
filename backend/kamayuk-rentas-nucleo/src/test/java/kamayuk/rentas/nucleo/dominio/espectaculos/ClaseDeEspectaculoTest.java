package kamayuk.rentas.nucleo.dominio.espectaculos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La clase de un espectaculo del art. 57, y la eleccion del taurino por el umbral (#376).
 *
 * <p>La UIT es la de 2026 —5 500—, escrita aqui como <b>dato de la prueba</b> y no leida del
 * derivado: esta es la funcion pura (regla 6), y lo que se prueba es la frontera del umbral, no que
 * cifra publica {@code normativa}. Que la clase se elija con la UIT del conjunto real lo prueban
 * {@code EspectaculoControllerTest} y {@code RegistrarEspectaculoTest}.
 */
@DisplayName("#376 — La clase de espectaculo del art. 57")
class ClaseDeEspectaculoTest {

    private static final Dinero UIT = Dinero.de("5500.00");

    @Test
    @DisplayName("una entrada de 27,51 supera el 0,5 % de 5 500 y elige la clave del 10 %")
    void porEncimaDelUmbral() {
        assertThat(ClaseDeEspectaculo.delTaurino(Dinero.de("27.51"), UIT))
                .isEqualTo(ClaseDeEspectaculo.TAURINO_SUPERIOR_AL_UMBRAL);
    }

    @Test
    @DisplayName("una entrada de 27,50 lo iguala, «no es superior», y elige la del 5 %")
    void enElUmbral() {
        assertThat(ClaseDeEspectaculo.delTaurino(Dinero.de("27.50"), UIT))
                .as("igualar el umbral no es superarlo: un >= aqui cobraria el doble")
                .isEqualTo(ClaseDeEspectaculo.TAURINO_RESTO);
    }

    @Test
    @DisplayName("el umbral se mueve con la UIT: con la de 2025, 5 350, queda en 26,75")
    void elUmbralSeMueveConLaUit() {
        // 5 350 × 0,5 % = 26,75: 26,76 lo supera y 26,75 no.
        assertThat(ClaseDeEspectaculo.delTaurino(Dinero.de("26.76"), Dinero.de("5350.00")))
                .isEqualTo(ClaseDeEspectaculo.TAURINO_SUPERIOR_AL_UMBRAL);
        assertThat(ClaseDeEspectaculo.delTaurino(Dinero.de("26.75"), Dinero.de("5350.00")))
                .isEqualTo(ClaseDeEspectaculo.TAURINO_RESTO);
    }

    @Test
    @DisplayName("el umbral escrito es el que nombra la clave publicada: TAURINO-SUPERIOR-0.5-UIT")
    void elUmbralEsElDeLaClave() {
        // Sobre una UIT de 100 soles, el umbral en soles es el mismo numero que el porcentaje.
        String porcentaje =
                ClaseDeEspectaculo.umbralDelTaurino(Dinero.de("100"))
                        .valor()
                        .stripTrailingZeros()
                        .toPlainString();

        assertThat(ClaseDeEspectaculo.TAURINO_SUPERIOR_AL_UMBRAL.clave())
                .as(
                        "si la ley cambia el umbral, el derivado cambia la clave; esta prueba ata el"
                                + " umbral escrito a esa clave para que no se separen")
                .isEqualTo("TAURINO-SUPERIOR-" + porcentaje + "-UIT");
    }

    @Test
    @DisplayName("el umbral con la UIT de 2026 es 27,50")
    void elUmbralDe2026() {
        assertThat(ClaseDeEspectaculo.umbralDelTaurino(UIT)).isEqualTo(Dinero.de("27.50"));
    }

    @Test
    @DisplayName("se declara TAURINO, en cualquier caja, y la clase la eligen la entrada y la UIT")
    void elTaurinoSeDeclaraAsi() {
        assertThat(ClaseDeEspectaculo.declaraUnTaurino(" taurino ")).isTrue();
        assertThat(ClaseDeEspectaculo.declarada("Taurino", Dinero.de("100.00"), UIT))
                .isEqualTo(ClaseDeEspectaculo.TAURINO_SUPERIOR_AL_UMBRAL);
        assertThat(ClaseDeEspectaculo.declarada("TAURINO", Dinero.de("10.00"), UIT))
                .isEqualTo(ClaseDeEspectaculo.TAURINO_RESTO);
    }

    @Test
    @DisplayName("las demas clases se declaran por su clave, y no piden la UIT")
    void lasDemasSeDeclaranPorSuClave() {
        assertThat(ClaseDeEspectaculo.declaraUnTaurino("cinematografico")).isFalse();
        assertThat(ClaseDeEspectaculo.declarada("cinematografico", null, null))
                .isEqualTo(ClaseDeEspectaculo.CINEMATOGRAFICO);
        assertThat(
                        ClaseDeEspectaculo.declarada(
                                "FOLCLOR-TEATRO-ZARZUELA-OPERA-BALLET-CIRCO", null, null))
                .isEqualTo(ClaseDeEspectaculo.FOLCLOR_TEATRO_ZARZUELA_OPERA_BALLET_CIRCO);
    }

    @Test
    @DisplayName("las dos claves del taurino no se declaran: esa eleccion no se teclea")
    void lasClavesDelTaurinoNoSeDeclaran() {
        assertThatThrownBy(
                        () -> ClaseDeEspectaculo.declarada("TAURINO-RESTO", Dinero.de("100"), UIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'TAURINO'");
        assertThatThrownBy(
                        () ->
                                ClaseDeEspectaculo.declarada(
                                        "TAURINO-SUPERIOR-0.5-UIT", Dinero.de("10"), UIT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ClaseDeEspectaculo.declarables())
                .contains(ClaseDeEspectaculo.TAURINO)
                .doesNotContain(
                        ClaseDeEspectaculo.TAURINO_RESTO.clave(),
                        ClaseDeEspectaculo.TAURINO_SUPERIOR_AL_UMBRAL.clave())
                .hasSize(6);
    }

    @Test
    @DisplayName("el taurino sin valor de entrada no se clasifica, y lo dice")
    void elTaurinoSinEntrada() {
        assertThatThrownBy(() -> ClaseDeEspectaculo.declarada("TAURINO", null, UIT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valorEntrada");
    }

    @Test
    @DisplayName("lo que no es del art. 57 se rechaza nombrando lo que se puede declarar")
    void loQueNoEsDelArticulo57() {
        assertThatThrownBy(() -> ClaseDeEspectaculo.declarada("CINE", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'CINE'")
                .hasMessageContaining("CINEMATOGRAFICO")
                .hasMessageContaining("TAURINO");
    }
}
