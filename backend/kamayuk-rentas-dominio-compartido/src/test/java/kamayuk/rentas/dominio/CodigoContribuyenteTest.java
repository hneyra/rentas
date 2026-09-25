package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Codigo unico de contribuyente")
class CodigoContribuyenteTest {

    @Test
    @DisplayName("se normaliza: recortado y en mayusculas")
    void seNormaliza() {
        assertThat(CodigoContribuyente.de("  c-00123  "))
                .as("tres escrituras del mismo codigo no pueden producir tres contribuyentes")
                .isEqualTo(CodigoContribuyente.de("C-00123"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"C 00123", "C/00123", "C.00123", "", "   ", "ñ123"})
    @DisplayName("rechaza lo que no es un identificador compacto")
    void rechazaLoQueNoEsIdentificador(String texto) {
        assertThatThrownBy(() -> CodigoContribuyente.de(texto))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("no excede el ancho de la columna")
    void noExcedeElAnchoDeLaColumna() {
        assertThat(CodigoContribuyente.de("1".repeat(20)).valor()).hasSize(20);
        assertThatThrownBy(() -> CodigoContribuyente.de("1".repeat(21)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");
    }

    @Test
    @DisplayName("se ordena, porque los padrones se listan por codigo")
    void seOrdena() {
        assertThat(CodigoContribuyente.de("A1")).isLessThan(CodigoContribuyente.de("A2"));
    }

    @Test
    @DisplayName("#423 — la forma de busqueda es la de guardar, y no valida")
    void laFormaDeBusqueda() {
        assertThat(CodigoContribuyente.formaDeBusqueda(" c-000007 "))
                .isEqualTo(CodigoContribuyente.de("c-000007").valor())
                .isEqualTo("C-000007");
        assertThat(CodigoContribuyente.formaDeBusqueda("c/7"))
                .as(
                        "un caracter que un codigo nuevo no admite no es una consulta mal formada:"
                                + " es un codigo que no esta, y no puede convertirse en 422")
                .isEqualTo("C/7");
    }
}
