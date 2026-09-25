package kamayuk.rentas.valores.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #337 — La cobertura de un valor por lo prescrito, sin base ni reloj.
 *
 * <p>Cada prueba siembra un valor de <b>varias</b> lineas, que es la muestra que faltaba: con una
 * sola, «alguna linea esta» y «todas estan» coinciden y cualquier implementacion pasa.
 */
@DisplayName("#337 — CoberturaDeLaPrescripcion")
class CoberturaDeLaPrescripcionTest {

    private static final ObligacionPrescrita PREDIAL_2021 = prescrita("PREDIAL", 2021);
    private static final ObligacionPrescrita PREDIAL_2022 = prescrita("PREDIAL", 2022);

    @Test
    @DisplayName("TOTAL: todas las lineas estan prescritas, aunque sean de dos predios")
    void total() {
        List<ValorDetalle> lineas = List.of(linea("PREDIAL", 2021, 1L), linea("PREDIAL", 2021, 2L));

        assertThat(CoberturaDeLaPrescripcion.de(lineas, Set.of(PREDIAL_2021)))
                .isEqualTo(CoberturaDeLaPrescripcion.TOTAL);
    }

    @Test
    @DisplayName("PARCIAL: un ejercicio del mismo tributo que no prescribio")
    void parcialPorEjercicio() {
        List<ValorDetalle> lineas = List.of(linea("PREDIAL", 2021, 1L), linea("PREDIAL", 2022, 1L));

        assertThat(CoberturaDeLaPrescripcion.de(lineas, Set.of(PREDIAL_2021)))
                .isEqualTo(CoberturaDeLaPrescripcion.PARCIAL);
    }

    @Test
    @DisplayName("PARCIAL: otro tributo del mismo ejercicio, que la solicitud ni pidio")
    void parcialPorTributo() {
        List<ValorDetalle> lineas =
                List.of(linea("PREDIAL", 2021, 1L), linea("ARBITRIOS", 2021, 1L));

        assertThat(CoberturaDeLaPrescripcion.de(lineas, Set.of(PREDIAL_2021, PREDIAL_2022)))
                .isEqualTo(CoberturaDeLaPrescripcion.PARCIAL);
    }

    @Test
    @DisplayName("la cobertura se acumula: dos resoluciones completan lo que ninguna completaba")
    void seAcumula() {
        List<ValorDetalle> lineas = List.of(linea("PREDIAL", 2021, 1L), linea("PREDIAL", 2022, 1L));

        assertThat(CoberturaDeLaPrescripcion.de(lineas, Set.of(PREDIAL_2022)))
                .isEqualTo(CoberturaDeLaPrescripcion.PARCIAL);
        assertThat(CoberturaDeLaPrescripcion.de(lineas, Set.of(PREDIAL_2021, PREDIAL_2022)))
                .isEqualTo(CoberturaDeLaPrescripcion.TOTAL);
    }

    @Test
    @DisplayName("NINGUNA: ninguna linea esta, o el valor no tiene lineas")
    void ninguna() {
        assertThat(
                        CoberturaDeLaPrescripcion.de(
                                List.of(linea("ARBITRIOS", 2021, 1L)), Set.of(PREDIAL_2021)))
                .isEqualTo(CoberturaDeLaPrescripcion.NINGUNA);
        assertThat(CoberturaDeLaPrescripcion.de(List.of(), Set.of(PREDIAL_2021)))
                .as("un valor sin lineas no tiene nada que haya prescrito: vacio no es «todas»")
                .isEqualTo(CoberturaDeLaPrescripcion.NINGUNA);
    }

    @Test
    @DisplayName("el tributo se compara sin mayusculas, como el upper() del SQL")
    void elTributoSinMayusculas() {
        assertThat(
                        CoberturaDeLaPrescripcion.de(
                                List.of(linea("PREDIAL", 2021, 1L)),
                                Set.of(prescrita(" predial ", 2021))))
                .isEqualTo(CoberturaDeLaPrescripcion.TOTAL);
    }

    private static ObligacionPrescrita prescrita(String tributo, int ejercicio) {
        return new ObligacionPrescrita(tributo, new Ejercicio(ejercicio));
    }

    private static ValorDetalle linea(String tributo, int ejercicio, Long predioId) {
        return ValorDetalle.nuevo(
                tributo,
                new Ejercicio(ejercicio),
                null,
                predioId,
                null,
                null,
                Dinero.de("100.00"),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO);
    }
}
