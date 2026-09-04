package kamayuk.rentas.tesoreria.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #33 — La tarifa del TUPA: su vigencia y su multiplicacion. Sin base y sin reloj. */
@DisplayName("#33 — La tasa y su vigencia")
class TasaTest {

    private static final LocalDate ENERO = LocalDate.of(2026, 1, 1);
    private static final LocalDate JUNIO = LocalDate.of(2026, 6, 30);
    private static final LocalDate JULIO = LocalDate.of(2026, 7, 1);

    @Test
    @DisplayName("una tarifa cerrada rige hasta su ultimo dia, incluido")
    void laVigenciaIncluyeSusExtremos() {
        Tasa tarifa = tasa(Dinero.de("12.50"), ENERO, JUNIO);

        assertThat(tarifa.vigenteA(ENERO)).isTrue();
        assertThat(tarifa.vigenteA(JUNIO)).isTrue();
        assertThat(tarifa.vigenteA(JULIO)).isFalse();
        assertThat(tarifa.vigenteA(ENERO.minusDays(1))).isFalse();
    }

    @Test
    @DisplayName("una tarifa abierta rige desde su inicio y sin final")
    void laTarifaAbiertaNoCaduca() {
        Tasa tarifa = tasa(Dinero.de("12.50"), ENERO, null);

        assertThat(tarifa.vigenteA(LocalDate.of(2030, 12, 31))).isTrue();
    }

    @Test
    @DisplayName("cobrarla tres veces cuesta tres veces su importe, exacto")
    void multiplicaSinPerderCentimos() {
        assertThat(tasa(Dinero.de("12.50"), ENERO, null).por(3)).isEqualTo(Dinero.de("37.50"));
    }

    @Test
    @DisplayName("una vigencia que termina antes de empezar se rechaza")
    void laVigenciaNoVaAlReves() {
        assertThatThrownBy(() -> tasa(Dinero.de("1.00"), JULIO, ENERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("termina antes de empezar");
    }

    @Test
    @DisplayName("sin documento fuente no se construye: una tarifa sin norma no se defiende")
    void exigeSuDocumentoFuente() {
        assertThatThrownBy(
                        () ->
                                new Tasa(
                                        1L,
                                        "T-001",
                                        "Constancia de no adeudo",
                                        9L,
                                        "1.3.1.1.1.1",
                                        Dinero.de("12.50"),
                                        ENERO,
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("regla 5");
    }

    private static Tasa tasa(Dinero importe, LocalDate desde, LocalDate hasta) {
        return new Tasa(
                1L,
                "T-001",
                "Constancia de no adeudo",
                9L,
                "1.3.1.1.1.1",
                importe,
                desde,
                hasta,
                "TUPA 2026, ordenanza de la prueba");
    }
}
