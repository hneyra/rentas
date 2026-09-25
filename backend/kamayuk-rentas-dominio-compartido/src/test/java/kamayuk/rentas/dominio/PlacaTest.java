package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Placa de rodaje")
class PlacaTest {

    @ParameterizedTest
    @ValueSource(strings = {"ABC-123", "A1B-234", "ABC123", "1234-5A", "M1A-456"})
    @DisplayName("admite los formatos que conviven en el parque")
    void admiteLosFormatosQueConviven(String texto) {
        assertThat(Placa.de(texto).valor()).isEqualTo(texto);
    }

    @Test
    @DisplayName("se normaliza: una placa tecleada en la calle llega de cualquier forma")
    void seNormaliza() {
        assertThat(Placa.de("  abc 123 ").valor()).isEqualTo("ABC123");
    }

    @Test
    @DisplayName("el guion es de lectura: con y sin el es la misma placa")
    void elGuionEsDeLectura() {
        assertThat(Placa.de("ABC-123")).isEqualTo(Placa.de("abc123")).hasToString("ABC-123");
        assertThat(Placa.de("ABC-123").hashCode()).isEqualTo(Placa.de("ABC123").hashCode());
        assertThat(Placa.de("ABC-123").sinSeparador()).isEqualTo("ABC123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABCDEF", "123456", "AB-1", "ABC--123", "AB@123", "ABC-123-456"})
    @DisplayName("rechaza lo que no puede ser una placa")
    void rechazaLoQueNoPuedeSerUnaPlaca(String texto) {
        assertThatThrownBy(() -> Placa.de(texto)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("no excede el ancho de la columna")
    void noExcedeElAnchoDeLaColumna() {
        assertThatThrownBy(() -> Placa.de("ABC-1234567"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longitud");
    }

    @Test
    @DisplayName("#423 — la forma de busqueda: sin espacios ni guion, y sin validar")
    void laFormaDeBusqueda() {
        assertThat(Placa.formaDeBusqueda(" zlg-701 ")).isEqualTo("ZLG701");
        assertThat(Placa.formaDeBusqueda("ZLG 701")).isEqualTo("ZLG701");
        assertThat(Placa.formaDeBusqueda("ZLG701")).isEqualTo("ZLG701");
        assertThat(Placa.formaDeBusqueda("a-1"))
                .as(
                        "sanciones admite placas cargadas de 1 caracter que Placa no aceptaria:"
                                + " buscarlas no puede lanzar")
                .isEqualTo("A1");
        assertThat(Placa.de("ZLG-701").sinSeparador())
                .as("y la de una placa valida es un caso de la misma regla")
                .isEqualTo(Placa.formaDeBusqueda("zlg 701"));
    }

    @Test
    @DisplayName("#423 — la forma escrita: sin espacios, el guion se queda, y sin validar")
    void laFormaEscrita() {
        assertThat(Placa.formaEscrita(" zlg 701 ")).isEqualTo("ZLG701");
        assertThat(Placa.formaEscrita("zlg-701")).isEqualTo("ZLG-701");
        assertThat(Placa.formaEscrita("a"))
                .as("una placa cargada de 1 caracter se escribe; el largo lo exige quien la guarda")
                .isEqualTo("A");
        assertThat(Placa.de(" zlg 701 ").valor())
                .as("y la de una placa valida es la misma regla")
                .isEqualTo(Placa.formaEscrita(" zlg 701 "));
    }
}
