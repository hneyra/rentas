package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Observacion (regla 10, ADR-0008)")
class ObservacionTest {

    @Test
    @DisplayName("guarda el porque, recortado")
    void guardaElPorqueRecortado() {
        assertThat(Observacion.de("  Rectifica el area declarada segun ficha 2026-114  ").texto())
                .isEqualTo("Rectifica el area declarada segun ficha 2026-114");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "        ", "ok", "abcd"})
    @DisplayName("no se puede cumplir con la nada")
    void noSePuedeCumplirConLaNada(String texto) {
        // Es la razon de ser del tipo: un parametro `String observacion` se cumple
        // pasando "" el dia que corre prisa, y entonces la pista de auditoria guarda
        // el que y pierde el porque, que es lo unico que no se puede reconstruir.
        assertThatThrownBy(() -> Observacion.de(texto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("al menos 5 caracteres");
    }

    @Test
    @DisplayName("el minimo es el mismo que el CHECK de la tabla de auditoria")
    void elMinimoEsElDeLaTabla() {
        assertThat(Observacion.de("12345").texto()).isEqualTo("12345");
    }

    @Test
    @DisplayName("no excede el ancho de la columna")
    void noExcedeElAnchoDeLaColumna() {
        assertThat(Observacion.de("a".repeat(500)).texto()).hasSize(500);
        assertThatThrownBy(() -> Observacion.de("a".repeat(501)))
                .as("mejor fallar aqui que a mitad de un INSERT")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("no admite un texto nulo")
    void noAdmiteUnTextoNulo() {
        assertThatThrownBy(() -> new Observacion(null)).isInstanceOf(NullPointerException.class);
    }
}
