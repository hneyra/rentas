package kamayuk.rentas.compartido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El marco con que se acota una lectura espacial (#536).
 *
 * <p>Sin base y sin reloj: lo que se prueba es que un marco imposible se rechaza <b>antes</b> de
 * llegar al motor, y sobre todo que el <b>orden</b> de las cuatro coordenadas es el que toda
 * biblioteca de mapas usa. Un marco leido en otro orden no falla: dibuja otro sitio.
 */
@DisplayName("#536 — El marco geografico de una lectura espacial")
class MarcoGeograficoTest {

    @Test
    @DisplayName("se lee en el orden oeste, sur, este, norte")
    void seLeeEnElOrdenDeGeoJson() {
        MarcoGeografico marco = MarcoGeografico.de("-80.71,-4.92,-80.66,-4.87");

        assertThat(marco.oeste()).isEqualByComparingTo(new BigDecimal("-80.71"));
        assertThat(marco.sur()).isEqualByComparingTo(new BigDecimal("-4.92"));
        assertThat(marco.este()).isEqualByComparingTo(new BigDecimal("-80.66"));
        assertThat(marco.norte()).isEqualByComparingTo(new BigDecimal("-4.87"));
    }

    @Test
    @DisplayName("un marco del reves se rechaza: no es un rectangulo vacio, es uno imposible")
    void unMarcoDelRevesSeRechaza() {
        assertThatThrownBy(() -> MarcoGeografico.de("-80.66,-4.92,-80.71,-4.87"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oeste");

        assertThatThrownBy(() -> MarcoGeografico.de("-80.71,-4.87,-80.66,-4.92"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sur");
    }

    @Test
    @DisplayName("un marco degenerado tampoco: un rectangulo sin area no acota, no encuentra")
    void unMarcoDegeneradoSeRechaza() {
        assertThatThrownBy(() -> MarcoGeografico.de("-80.71,-4.92,-80.71,-4.87"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fuera del rango de coordenadas se rechaza, en las cuatro")
    void fueraDeRangoSeRechaza() {
        assertThatThrownBy(() -> MarcoGeografico.de("-181,-4.92,-80.66,-4.87"))
                .hasMessageContaining("longitud oeste");
        assertThatThrownBy(() -> MarcoGeografico.de("-80.71,-91,-80.66,-4.87"))
                .hasMessageContaining("latitud sur");
        assertThatThrownBy(() -> MarcoGeografico.de("-80.71,-4.92,181,-4.87"))
                .hasMessageContaining("longitud este");
        assertThatThrownBy(() -> MarcoGeografico.de("-80.71,-4.92,-80.66,91"))
                .hasMessageContaining("latitud norte");
    }

    @Test
    @DisplayName("lo que no son cuatro numeros se rechaza nombrando el parametro")
    void loQueNoEsUnMarcoSeRechazaNombrandolo() {
        assertThatThrownBy(() -> MarcoGeografico.de("-80.71,-4.92,-80.66"))
                .hasMessageContaining("bbox");
        assertThatThrownBy(() -> MarcoGeografico.de("-80.71,-4.92,-80.66,-4.87,0"))
                .hasMessageContaining("bbox");
        assertThatThrownBy(() -> MarcoGeografico.de("-80.71,-4.92,-80.66,norte"))
                .hasMessageContaining("norte");
    }
}
