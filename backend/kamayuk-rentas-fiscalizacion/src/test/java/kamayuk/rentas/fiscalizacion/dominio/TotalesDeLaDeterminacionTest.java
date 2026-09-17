package kamayuk.rentas.fiscalizacion.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #193 — los totales de la resolucion de determinacion los suma el backend.
 *
 * <p>Sumarlos en el navegador daria tres cifras al centimo indistinguibles de unas liquidadas,
 * sobre el papel que vuelve una diferencia deuda exigible. Aqui se comprueba lo que hace el sumador
 * y, sobre todo, lo que NO hace: rellenar con cero lo que falta.
 */
@DisplayName("#193 — Los totales de la determinacion")
class TotalesDeLaDeterminacionTest {

    @Test
    @DisplayName("suma el insoluto y la multa de todas las lineas, y el total de los dos")
    void sumaLasDosColumnas() {
        TotalesDeLaDeterminacion totales =
                TotalesDeLaDeterminacion.de(
                        List.of(linea(2024, "450.00", "225.00"), linea(2025, "310.50", "155.25")));

        assertThat(totales.insolutoOmitido()).isEqualTo(Dinero.de("760.50"));
        assertThat(totales.multaTributaria()).isEqualTo(Dinero.de("380.25"));
        assertThat(totales.total()).isEqualTo(Dinero.de("1140.75"));
        assertThat(totales.esperaSusCifras()).isFalse();
    }

    @Test
    @DisplayName("una linea sin insoluto deja el insoluto NULO, no la suma de las demas")
    void unaLineaSinInsolutoAnulaEsaColumna() {
        TotalesDeLaDeterminacion totales =
                TotalesDeLaDeterminacion.de(
                        List.of(linea(2024, "450.00", "225.00"), linea(2025, null, "155.25")));

        assertThat(totales.insolutoOmitido())
                .as("450.00 se leeria como el insoluto de la resolucion entera, y no lo es")
                .isNull();
        assertThat(totales.multaTributaria()).isEqualTo(Dinero.de("380.25"));
        assertThat(totales.total()).isNull();
        assertThat(totales.esperaSusCifras()).isTrue();
    }

    @Test
    @DisplayName("las dos columnas se deciden por separado: D-02a y D-02c son dos decisiones")
    void lasColumnasSeDecidenPorSeparado() {
        TotalesDeLaDeterminacion totales =
                TotalesDeLaDeterminacion.de(
                        List.of(linea(2024, "450.00", null), linea(2025, "310.50", null)));

        assertThat(totales.insolutoOmitido())
                .as("el insoluto esta determinado; esconderlo porque falta la multa lo pierde")
                .isEqualTo(Dinero.de("760.50"));
        assertThat(totales.multaTributaria()).isNull();
        assertThat(totales.total()).isNull();
    }

    @Test
    @DisplayName("lo que este sistema emite hoy: las cuatro cifras nulas y los tres totales nulos")
    void hoyLosTresSalenNulos() {
        TotalesDeLaDeterminacion totales =
                TotalesDeLaDeterminacion.de(
                        List.of(linea(2024, null, null), linea(2025, null, null)));

        assertThat(totales.insolutoOmitido()).isNull();
        assertThat(totales.multaTributaria()).isNull();
        assertThat(totales.total()).isNull();
        assertThat(totales.esperaSusCifras()).isTrue();
    }

    @Test
    @DisplayName("sin ninguna linea no se determino cero: se determino nada")
    void sinLineasNoHayCero() {
        TotalesDeLaDeterminacion totales = TotalesDeLaDeterminacion.de(List.of());

        assertThat(totales.total()).isNull();
        assertThat(totales.insolutoOmitido()).isNull();
        assertThat(totales.multaTributaria()).isNull();
    }

    @Test
    @DisplayName("un total con un sumando ausente no se deja construir")
    void unTotalConUnSumandoAusenteNoSeConstruye() {
        assertThatThrownBy(
                        () ->
                                new TotalesDeLaDeterminacion(
                                        Dinero.de("450.00"), null, Dinero.de("450.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no incluye");
    }

    @Test
    @DisplayName("«Base omitida S/» es la resta, hecha aqui y nunca negativa")
    void laBaseOmitidaEsLaResta() {
        assertThat(conBases("48000.00", "30000.00").baseOmitida()).isEqualTo(Dinero.de("18000.00"));
        assertThat(conBases("30000.00", "48000.00").baseOmitida())
                .as("quien declaro de mas no omitio base ninguna")
                .isEqualTo(Dinero.CERO);
        assertThat(linea(2024, null, null).baseOmitida())
                .as("sin las dos bases no hay resta: nula, nunca cero")
                .isNull();
    }

    // ------------------------------------------------------------------

    private static LineaDeLiquidacion linea(
            int ejercicio, @Nullable String insoluto, @Nullable String multa) {
        return new LineaDeLiquidacion(
                null,
                null,
                new Ejercicio(ejercicio),
                7L,
                11L,
                null,
                CondicionFiscalizada.SUBVALUADOR,
                AreaM2.de("120.00"),
                AreaM2.de("300.00"),
                "CASA HABITACION",
                "COMERCIO",
                null,
                null,
                insoluto == null ? null : Dinero.de(insoluto),
                multa == null ? null : Dinero.de(multa));
    }

    private static LineaDeLiquidacion conBases(String hallada, String declarada) {
        return new LineaDeLiquidacion(
                null,
                null,
                new Ejercicio(2024),
                7L,
                11L,
                null,
                CondicionFiscalizada.SUBVALUADOR,
                AreaM2.de("120.00"),
                AreaM2.de("300.00"),
                "CASA HABITACION",
                "COMERCIO",
                Dinero.de(declarada),
                Dinero.de(hallada),
                null,
                null);
    }
}
