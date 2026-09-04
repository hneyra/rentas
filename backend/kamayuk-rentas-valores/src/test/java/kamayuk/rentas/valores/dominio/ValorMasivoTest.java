package kamayuk.rentas.valores.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #38 — el criterio de una corrida masiva, sin base de datos. */
@DisplayName("#38 — ValorMasivo")
class ValorMasivoTest {

    private static final Observacion OBSERVACION = Observacion.de("Corrida de prueba");
    private static final LocalDate FECHA = LocalDate.of(2026, 3, 15);

    @Test
    @DisplayName("una RM no se genera masivamente: nace de un acta, no de un padron")
    void rechazaResolucionDeMulta() {
        assertThatThrownBy(
                        () ->
                                new ValorMasivo(
                                        null,
                                        TipoValor.RESOLUCION_DE_MULTA,
                                        null,
                                        new Ejercicio(2024),
                                        new Ejercicio(2026),
                                        FECHA,
                                        OrigenDeCriterio.SELECCION,
                                        1,
                                        null,
                                        null,
                                        OBSERVACION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RM");
    }

    @Test
    @DisplayName("el ejercicio desde no puede ser posterior al hasta")
    void rechazaRangoDeEjerciciosInvertido() {
        assertThatThrownBy(
                        () ->
                                new ValorMasivo(
                                        null,
                                        TipoValor.ORDEN_DE_PAGO,
                                        null,
                                        new Ejercicio(2026),
                                        new Ejercicio(2024),
                                        FECHA,
                                        OrigenDeCriterio.SELECCION,
                                        1,
                                        null,
                                        null,
                                        OBSERVACION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sin tributo, coincideTributo acepta cualquiera")
    void sinTributoAceptaCualquiera() {
        ValorMasivo corrida = corridaDe(null);
        assertThat(corrida.coincideTributo("PREDIAL")).isTrue();
        assertThat(corrida.coincideTributo("ARBITRIO")).isTrue();
    }

    @Test
    @DisplayName("con tributo, coincideTributo solo acepta el mismo, sin distinguir mayusculas")
    void conTributoSoloAceptaElMismo() {
        ValorMasivo corrida = corridaDe("predial");
        assertThat(corrida.coincideTributo("PREDIAL")).isTrue();
        assertThat(corrida.coincideTributo("ARBITRIO")).isFalse();
    }

    @Test
    @DisplayName("coincideEjercicio respeta los dos extremos del rango, inclusive")
    void coincideEjercicioRespetaLosExtremos() {
        ValorMasivo corrida = corridaDe(null);
        assertThat(corrida.coincideEjercicio(new Ejercicio(2024))).isTrue();
        assertThat(corrida.coincideEjercicio(new Ejercicio(2026))).isTrue();
        assertThat(corrida.coincideEjercicio(new Ejercicio(2023))).isFalse();
        assertThat(corrida.coincideEjercicio(new Ejercicio(2027))).isFalse();
    }

    private static ValorMasivo corridaDe(String tributo) {
        return new ValorMasivo(
                1L,
                TipoValor.ORDEN_DE_PAGO,
                tributo,
                new Ejercicio(2024),
                new Ejercicio(2026),
                FECHA,
                OrigenDeCriterio.SELECCION,
                1,
                "prueba",
                null,
                OBSERVACION);
    }
}
