package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Periodo")
class PeriodoTest {

    private static final Ejercicio EJERCICIO_2026 = new Ejercicio(2026);

    @Test
    @DisplayName("el periodo 0 es el anual")
    void elPeriodoCeroEsElAnual() {
        assertThat(Periodo.anual(EJERCICIO_2026).esAnual()).isTrue();
        assertThat(Periodo.anual(EJERCICIO_2026).numero()).isZero();
        assertThat(Periodo.anual(EJERCICIO_2026)).hasToString("2026");
    }

    @Test
    @DisplayName("una cuota lleva el ejercicio pegado")
    void unaCuotaLlevaElEjercicioPegado() {
        assertThat(Periodo.cuota(EJERCICIO_2026, 2))
                .as("la cuota 2 de 2026 y la de 2027 son deudas distintas")
                .isNotEqualTo(Periodo.cuota(new Ejercicio(2027), 2))
                .hasToString("2026-2");
    }

    @Test
    @DisplayName("la cuota 0 no existe: el anual se construye con su propio metodo")
    void laCuotaCeroNoExiste() {
        assertThatThrownBy(() -> Periodo.cuota(EJERCICIO_2026, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anual");
    }

    @Test
    @DisplayName("el numero cae en el rango que admite la columna")
    void elNumeroCaeEnElRangoDeLaColumna() {
        assertThat(Periodo.cuota(EJERCICIO_2026, 12).numero()).isEqualTo(12);
        assertThatThrownBy(() -> new Periodo(EJERCICIO_2026, 13))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Periodo(EJERCICIO_2026, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("se ordena por ejercicio y despues por numero")
    void seOrdenaPorEjercicioYDespuesPorNumero() {
        Periodo cuarta2025 = Periodo.cuota(new Ejercicio(2025), 4);
        Periodo primera2026 = Periodo.cuota(EJERCICIO_2026, 1);
        Periodo anual2026 = Periodo.anual(EJERCICIO_2026);

        assertThat(List.of(primera2026, anual2026, cuarta2025).stream().sorted().toList())
                .containsExactly(cuarta2025, anual2026, primera2026);
    }

    @Test
    @DisplayName("necesita su ejercicio")
    void necesitaSuEjercicio() {
        assertThatThrownBy(() -> new Periodo(null, 1)).isInstanceOf(NullPointerException.class);
    }
}
