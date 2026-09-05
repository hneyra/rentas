package kamayuk.rentas.nucleo.dominio.predial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MinimoImponible (RT-014)")
class MinimoImponibleTest {

    @Test
    @DisplayName("si el impuesto calculado no llega al minimo, se paga el minimo")
    void seSustituyePorElMinimoSiElCalculoEsMenor() {
        Dinero resultado = MinimoImponible.aplicar(Dinero.de(5), Dinero.de(30));

        assertThat(resultado).isEqualTo(Dinero.de(30));
    }

    @Test
    @DisplayName("si el impuesto calculado supera el minimo, se paga lo calculado")
    void seRespetaElCalculoSiSuperaElMinimo() {
        Dinero resultado = MinimoImponible.aplicar(Dinero.de(100), Dinero.de(30));

        assertThat(resultado).isEqualTo(Dinero.de(100));
    }

    @Test
    @DisplayName("un impuesto exactamente igual al minimo no se sustituye")
    void unImpuestoIgualAlMinimoSeMantiene() {
        Dinero resultado = MinimoImponible.aplicar(Dinero.de(30), Dinero.de(30));

        assertThat(resultado).isEqualTo(Dinero.de(30));
    }

    @Test
    @DisplayName("sin el impuesto calculado no hay que comparar")
    void sinElImpuestoCalculadoSeRechaza() {
        assertThatThrownBy(() -> MinimoImponible.aplicar(null, Dinero.de(30)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("sin el minimo del ejercicio no hay que comparar")
    void sinElMinimoSeRechaza() {
        assertThatThrownBy(() -> MinimoImponible.aplicar(Dinero.de(100), null))
                .isInstanceOf(NullPointerException.class);
    }
}
