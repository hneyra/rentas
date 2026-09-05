package kamayuk.rentas.nucleo.dominio.predial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Tramo (RT-013)")
class TramoTest {

    @Test
    @DisplayName("hasta() construye un tramo con tope")
    void hastaConstruyeUnTramoConTope() {
        Tramo tramo = Tramo.hasta(Dinero.de(1000), Alicuota.de("0.2"));

        assertThat(tramo.tieneTope()).isTrue();
        assertThat(tramo.limiteSuperior()).isEqualTo(Dinero.de(1000));
    }

    @Test
    @DisplayName("sinTope() construye el ultimo tramo, sin limite superior")
    void sinTopeConstruyeElUltimoTramo() {
        Tramo tramo = Tramo.sinTope(Alicuota.de("1.0"));

        assertThat(tramo.tieneTope()).isFalse();
        assertThat(tramo.limiteSuperior()).isNull();
    }

    @Test
    @DisplayName("un limite superior negativo o cero se rechaza")
    void unLimiteSuperiorNoPositivoSeRechaza() {
        assertThatThrownBy(() -> Tramo.hasta(Dinero.CERO, Alicuota.de("0.2")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Tramo.hasta(Dinero.de(-1), Alicuota.de("0.2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("una alicuota nula se rechaza")
    void unaAlicuotaNulaSeRechaza() {
        assertThatThrownBy(() -> new Tramo(Dinero.de(1000), null))
                .isInstanceOf(NullPointerException.class);
    }
}
