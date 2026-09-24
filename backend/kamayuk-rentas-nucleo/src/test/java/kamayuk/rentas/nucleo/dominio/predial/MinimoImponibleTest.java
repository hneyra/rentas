package kamayuk.rentas.nucleo.dominio.predial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    /**
     * El minimo del predial sobre su base afecta (#332).
     *
     * <p>La siembra que distingue es <b>una base exactamente cero</b>: con cualquier base positiva,
     * por chica que sea, cobrar el minimo y negarse a decidir dan lo mismo, y por eso los cinco
     * casos de arriba —todos con impuesto positivo— no veian el defecto. El minimo es el de 2026,
     * 0,6 % de una UIT de 5 500 ({@code parametros-2026.csv:41}), para que la cifra sea la del
     * issue.
     */
    @Nested
    @DisplayName("#332 — sobre la base afecta del predial")
    class SobreLaBaseAfecta {

        private final Dinero minimo = Dinero.de("33.00");

        @Test
        @DisplayName(
                "con la base afecta exactamente cero no se decide en silencio: se nombran"
                        + " RT-014-c02 y c03")
        void conLaBaseCeroNoSeCobraElMinimoEnSilencio() {
            assertThatThrownBy(
                            () ->
                                    MinimoImponible.aplicarSobreBase(
                                            Dinero.CERO, Dinero.CERO, minimo))
                    .as("hasta #332 esto devolvia 33.00: el minimo sobre una base inafecta entera")
                    .isInstanceOf(MinimoImponible.BaseAfectaCero.class)
                    .hasMessageContaining("RT-014-c02")
                    .hasMessageContaining("RT-014-c03");
        }

        @Test
        @DisplayName("el borde del otro lado: una base de S/ 1,00 sigue pagando el minimo")
        void unaBaseDeUnSolSiguePagandoElMinimo() {
            Dinero resultado =
                    MinimoImponible.aplicarSobreBase(Dinero.CERO, Dinero.de("1.00"), minimo);

            assertThat(resultado).isEqualTo(Dinero.de("33.00"));
        }

        @Test
        @DisplayName("con una base que supera el minimo, se paga lo calculado, como en aplicar")
        void conUnaBaseGrandeSePagaLoCalculado() {
            Dinero resultado =
                    MinimoImponible.aplicarSobreBase(
                            Dinero.de("160.00"), Dinero.de("80000.00"), minimo);

            assertThat(resultado).isEqualTo(Dinero.de("160.00"));
        }

        @Test
        @DisplayName("sin la base afecta no se sabe si el caso esta decidido")
        void sinLaBaseSeRechaza() {
            assertThatThrownBy(() -> MinimoImponible.aplicarSobreBase(Dinero.CERO, null, minimo))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
