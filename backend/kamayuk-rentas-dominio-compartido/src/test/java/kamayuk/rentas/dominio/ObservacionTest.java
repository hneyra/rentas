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

    /**
     * #30. Hasta este issue esto era un {@code NullPointerException}, y esa eleccion decidia el
     * estado HTTP sin que nadie lo hubiera decidido: el borde no lo caza y sale <b>500 con un
     * identificador de incidencia</b>, donde un obligatorio ausente contesta 422 en el resto del
     * sistema.
     *
     * <p>Se afirma <b>el tipo y el mensaje</b>, y las dos cosas hacen falta: el tipo es lo que
     * decide el estado —el manejador traduce {@link IllegalArgumentException} a 422— y el mensaje
     * es lo unico que le dice al cliente que campo le falta. Con solo el tipo, un mensaje que no
     * nombrara el campo pasaria en verde, que es la mitad del defecto.
     */
    @Test
    @DisplayName("un texto nulo se rechaza como lo que es: un campo que falta (#30)")
    void unTextoNuloSeRechazaComoUnCampoQueFalta() {
        assertThatThrownBy(() -> new Observacion(null))
                .as("un NullPointerException aqui sale del borde como 500 con incidencia")
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(NullPointerException.class)
                .hasMessageContaining("'observacion'")
                .hasMessageContaining("regla 10");
    }

    /**
     * El contraste: que falte no es lo mismo que no valga, y los dos mensajes son distintos. Sin
     * esto, un arreglo que contestara «falta el campo» a todo pasaria en verde y le diria a quien
     * escribio cuatro caracteres que no escribio ninguno.
     */
    @Test
    @DisplayName("y una demasiado corta sigue diciendo que es corta, no que falta")
    void unaDemasiadoCortaSigueDiciendoQueEsCorta() {
        assertThatThrownBy(() -> Observacion.de("ab"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("al menos 5 caracteres");
        assertThatThrownBy(() -> Observacion.de("ab")).hasMessageNotContaining("Falta el campo");
    }
}
