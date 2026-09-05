package kamayuk.rentas.nucleo.dominio.alcabala;

import static org.assertj.core.api.Assertions.assertThat;

import kamayuk.rentas.dominio.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName(
        "#32 — BaseImponibleDeAlcabala: el mayor entre transferencia y autovaluo (TUO LTM art. 24)")
class BaseImponibleDeAlcabalaTest {

    @Test
    @DisplayName("elige el autovaluo ajustado cuando supera al valor de transferencia")
    void eligeElAutovaluoCuandoEsMayor() {
        EleccionDeBase eleccion =
                BaseImponibleDeAlcabala.elegir(Dinero.de("100000.00"), Dinero.de("150000.00"));

        assertThat(eleccion.base()).isEqualTo(Dinero.de("150000.00"));
        assertThat(eleccion.origen()).isEqualTo(OrigenDeLaBase.AUTOAVALUO_AJUSTADO);
        assertThat(eleccion.fundamento()).isNotBlank().contains("autovaluo");
    }

    @Test
    @DisplayName("elige el valor de transferencia cuando supera al autovaluo ajustado")
    void eligeElValorDeTransferenciaCuandoEsMayor() {
        EleccionDeBase eleccion =
                BaseImponibleDeAlcabala.elegir(Dinero.de("200000.00"), Dinero.de("150000.00"));

        assertThat(eleccion.base()).isEqualTo(Dinero.de("200000.00"));
        assertThat(eleccion.origen()).isEqualTo(OrigenDeLaBase.VALOR_DE_TRANSFERENCIA);
        assertThat(eleccion.fundamento()).isNotBlank();
    }

    @Test
    @DisplayName(
            "con los dos iguales, la eleccion es el valor de transferencia: no hay empate posible")
    void conLosDosIgualesEligeElValorDeTransferencia() {
        EleccionDeBase eleccion =
                BaseImponibleDeAlcabala.elegir(Dinero.de("150000.00"), Dinero.de("150000.00"));

        assertThat(eleccion.origen()).isEqualTo(OrigenDeLaBase.VALOR_DE_TRANSFERENCIA);
    }
}
