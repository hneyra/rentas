package kamayuk.rentas.valores.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #335 — Los dias suspendidos se cuentan con los dos extremos, y una vez cada uno.
 *
 * <p>La convencion inclusiva vive en {@link IntervaloSuspendido#dias()} y la union en {@link
 * IntervalosSuspendidos#unir}: estas pruebas miden los dos tipos sueltos, y {@code
 * ComputoDePrescripcionTest} mide lo que hacen con el vencimiento.
 */
@DisplayName("#335 — Intervalos suspendidos: inclusivos y unidos")
class IntervalosSuspendidosTest {

    private static IntervaloSuspendido de(String desde, String hasta) {
        return new IntervaloSuspendido(LocalDate.parse(desde), LocalDate.parse(hasta));
    }

    @Test
    @DisplayName("un intervalo cuenta su primer y su ultimo dia")
    void cuentaLosDosExtremos() {
        assertThat(de("2017-01-01", "2017-07-01").dias()).isEqualTo(182);
        assertThat(de("2018-05-10", "2018-05-10").dias()).isEqualTo(1);
    }

    @Test
    @DisplayName("un intervalo no termina antes de empezar")
    void noTerminaAntesDeEmpezar() {
        assertThatThrownBy(() -> de("2017-07-01", "2017-06-30"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("los que se solapan quedan en uno, lleguen en el orden que lleguen")
    void losQueSeSolapanQuedanEnUno() {
        IntervalosSuspendidos unidos =
                IntervalosSuspendidos.unir(
                        List.of(de("2017-06-01", "2018-06-01"), de("2017-01-01", "2017-12-31")));

        assertThat(unidos.intervalos()).containsExactly(de("2017-01-01", "2018-06-01"));
        assertThat(unidos.dias()).isEqualTo(517);
    }

    @Test
    @DisplayName("el contenido en otro no suma nada")
    void elContenidoNoSuma() {
        IntervalosSuspendidos unidos =
                IntervalosSuspendidos.unir(
                        List.of(de("2017-01-01", "2017-07-01"), de("2017-03-01", "2017-04-30")));

        assertThat(unidos.intervalos()).containsExactly(de("2017-01-01", "2017-07-01"));
        assertThat(unidos.dias()).isEqualTo(182);
    }

    @Test
    @DisplayName("los que se tocan quedan en uno, y los dias no cambian")
    void losQueSeTocanQuedanEnUno() {
        IntervalosSuspendidos unidos =
                IntervalosSuspendidos.unir(
                        List.of(de("2017-02-01", "2017-02-28"), de("2017-01-01", "2017-01-31")));

        assertThat(unidos.intervalos()).containsExactly(de("2017-01-01", "2017-02-28"));
        assertThat(unidos.dias()).isEqualTo(31 + 28);
    }

    @Test
    @DisplayName("los disjuntos se quedan separados y suman lo mismo que sin unir")
    void losDisjuntosSeQuedanSeparados() {
        IntervaloSuspendido primero = de("2017-01-01", "2017-03-31");
        IntervaloSuspendido segundo = de("2017-06-01", "2017-06-30");

        IntervalosSuspendidos unidos = IntervalosSuspendidos.unir(List.of(segundo, primero));

        assertThat(unidos.intervalos()).containsExactly(primero, segundo);
        assertThat(unidos.dias()).isEqualTo(primero.dias() + segundo.dias()).isEqualTo(120);
    }

    @Test
    @DisplayName("una cadena que se solapa de dos en dos queda en uno")
    void unaCadenaQuedaEnUno() {
        IntervalosSuspendidos unidos =
                IntervalosSuspendidos.unir(
                        List.of(
                                de("2017-05-01", "2017-08-31"),
                                de("2017-01-01", "2017-03-31"),
                                de("2017-03-15", "2017-05-15"),
                                de("2018-01-01", "2018-01-01")));

        assertThat(unidos.intervalos())
                .containsExactly(de("2017-01-01", "2017-08-31"), de("2018-01-01", "2018-01-01"));
    }

    @Test
    @DisplayName("sin suspensiones no hay dias")
    void sinSuspensionesNoHayDias() {
        assertThat(IntervalosSuspendidos.unir(List.of()).dias()).isZero();
    }
}
