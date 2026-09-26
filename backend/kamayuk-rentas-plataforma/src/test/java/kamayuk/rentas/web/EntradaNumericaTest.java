package kamayuk.rentas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** La lectura del importe tecleado (#395): dos decimales como mucho, y rechazar, no redondear. */
@DisplayName("#395 — un importe o un area tecleados llevan dos decimales como mucho")
class EntradaNumericaTest {

    @Test
    @DisplayName("un tercer decimal es 422 nombrando el campo, no un redondeo")
    void unTercerDecimalSeRechaza() {
        assertThatThrownBy(() -> EntradaNumerica.leer("33.333", "insoluto", "no es un importe"))
                .isInstanceOf(ProblemaDeNegocio.class)
                .hasMessageContaining("insoluto")
                .hasMessageContaining("3 decimales");
        assertThatThrownBy(() -> EntradaNumerica.leer("0.004", "insoluto", "no es un importe"))
                .isInstanceOf(ProblemaDeNegocio.class);
    }

    @Test
    @DisplayName("los ceros de la derecha no son un decimal mas, y dos decimales pasan")
    void losCerosDeLaDerechaNoCuentan() {
        assertThat(EntradaNumerica.leer("10.500", "insoluto", "x")).isEqualByComparingTo("10.5");
        assertThat(EntradaNumerica.leer("33.33", "insoluto", "x")).isEqualByComparingTo("33.33");
        assertThat(EntradaNumerica.leer("1200", "insoluto", "x")).isEqualByComparingTo("1200");
        assertThat(EntradaNumerica.leer("1E+3", "insoluto", "x")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("lo que no es una cifra dice la frase de su ruta")
    void loQueNoEsCifraDiceSuFrase() {
        assertThatThrownBy(() -> EntradaNumerica.leer("doce", "insoluto", "no es un importe"))
                .isInstanceOf(ProblemaDeNegocio.class)
                .hasMessage("no es un importe");
    }
}
