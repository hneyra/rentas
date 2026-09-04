package kamayuk.rentas.fiscalizacion.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El reparto del padrón examinado, sin base y sin reloj (#586).
 *
 * <p>Lo que estas pruebas fijan es que <b>un recuento que no cuadra no se puede construir</b>: es
 * la diferencia entre un número plausible y equivocado —el modo de fallo que este issue denuncia— y
 * un fallo que se ve.
 */
@DisplayName("#586 — El reparto del padron que un sorteo examino")
class ResultadoDelSorteoTest {

    private static final LocalDate SORTEO = LocalDate.of(2026, 3, 16);

    @Test
    @DisplayName("cada predio detectado cae en exactamente una casilla, y la suma da")
    void laSumaDa() {
        ResultadoDelSorteo resultado = new ResultadoDelSorteo(SORTEO, 10, 6, 2, 3, 1);

        assertThat(resultado.excluidos()).isEqualTo(4);
        assertThat(resultado.sorteados() + resultado.excluidos()).isEqualTo(resultado.detectados());
    }

    @Test
    @DisplayName("un recuento que no cuadra no se construye, y el mensaje dice las dos cifras")
    void unRecuentoQueNoCuadraNoSeConstruye() {
        // Es lo que produciria contar los excluidos sobre la ULTIMA pagina del recorrido en vez de
        // acumularlos: 100 detectados, 6 sorteados y «1 excluido», que es un numero plausible.
        assertThatThrownBy(() -> new ResultadoDelSorteo(SORTEO, 100, 6, 0, 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100 detectados")
                .hasMessageContaining("suman 7");
    }

    @Test
    @DisplayName("no pueden entrar mas predios sin titular que predios sorteados")
    void masSinTitularQueSorteadosNoSeConstruye() {
        assertThatThrownBy(() -> new ResultadoDelSorteo(SORTEO, 3, 3, 4, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sin titular");
    }

    @Test
    @DisplayName("y lleva su fecha: la muestra es una foto y estas cifras son las de ese dia")
    void llevaSuFecha() {
        assertThat(new ResultadoDelSorteo(SORTEO, 0, 0, 0, 0, 0).fechaSorteo()).isEqualTo(SORTEO);
    }
}
