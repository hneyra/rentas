package kamayuk.rentas.sanciones.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Alicuota;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#43 — CodigoInfraccion")
class CodigoInfraccionTest {

    @Test
    @DisplayName("un codigo nuevo no tiene id ni vigencia_hasta")
    void unCodigoNuevoNoTieneIdNiVigenciaHasta() {
        CodigoInfraccion codigo = codigoDe(LocalDate.of(2026, 1, 1));

        assertThat(codigo.esNuevo()).isTrue();
        assertThat(codigo.estaVigente()).isTrue();
        assertThat(codigo.vigenciaHasta()).isNull();
    }

    @Test
    @DisplayName("sin base legal no se guarda (criterio de aceptacion)")
    void sinBaseLegalNoSeGuarda() {
        assertThatThrownBy(
                        () ->
                                CodigoInfraccion.nuevo(
                                        Familia.TRANSITO,
                                        "G-01",
                                        "Exceso de velocidad",
                                        Alicuota.de("8"),
                                        null,
                                        null,
                                        "  ",
                                        LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el codigo se normaliza a mayusculas")
    void elCodigoSeNormalizaAMayusculas() {
        CodigoInfraccion codigo =
                CodigoInfraccion.nuevo(
                        Familia.TRANSITO,
                        "  g-01  ",
                        "Exceso de velocidad",
                        Alicuota.de("8"),
                        null,
                        null,
                        "RNT art. 300",
                        LocalDate.of(2026, 1, 1));

        assertThat(codigo.codigo()).isEqualTo("G-01");
    }

    @Test
    @DisplayName("rigeEn incluye los dos extremos de la vigencia (regla 9)")
    void rigeEnIncluyeLosDosExtremos() {
        CodigoInfraccion vigente = codigoDe(LocalDate.of(2026, 1, 1));
        CodigoInfraccion cerrado = vigente.cerradoEl(LocalDate.of(2026, 6, 30));

        assertThat(cerrado.rigeEn(LocalDate.of(2026, 1, 1))).as("el primer dia rige").isTrue();
        assertThat(cerrado.rigeEn(LocalDate.of(2026, 6, 30))).as("el ultimo dia rige").isTrue();
        assertThat(cerrado.rigeEn(LocalDate.of(2026, 7, 1))).as("un dia despues no rige").isFalse();
        assertThat(cerrado.rigeEn(LocalDate.of(2025, 12, 31))).as("un dia antes no rige").isFalse();
    }

    @Test
    @DisplayName("cerrar deja el resto de la fila intacta, solo cambia vigencia_hasta")
    void cerrarDejaElRestoDeLaFilaIntacta() {
        CodigoInfraccion vigente = codigoDe(LocalDate.of(2026, 1, 1));

        CodigoInfraccion cerrado = vigente.cerradoEl(LocalDate.of(2026, 6, 30));

        assertThat(cerrado.estaVigente()).isFalse();
        assertThat(cerrado.vigenciaHasta()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(cerrado.codigo()).isEqualTo(vigente.codigo());
        assertThat(cerrado.descripcion()).isEqualTo(vigente.descripcion());
        assertThat(cerrado.porcentajeUit()).isEqualTo(vigente.porcentajeUit());
        assertThat(cerrado.baseLegal()).isEqualTo(vigente.baseLegal());
    }

    @Test
    @DisplayName("no se puede cerrar una version ya cerrada")
    void noSePuedeCerrarUnaVersionYaCerrada() {
        CodigoInfraccion cerrado =
                codigoDe(LocalDate.of(2026, 1, 1)).cerradoEl(LocalDate.of(2026, 3, 1));

        assertThatThrownBy(() -> cerrado.cerradoEl(LocalDate.of(2026, 4, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("no se puede cerrar antes de que empiece a regir")
    void noSePuedeCerrarAntesDeQueEmpieceARegir() {
        CodigoInfraccion vigente = codigoDe(LocalDate.of(2026, 6, 1));

        assertThatThrownBy(() -> vigente.cerradoEl(LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CodigoInfraccion codigoDe(LocalDate vigenciaDesde) {
        return CodigoInfraccion.nuevo(
                Familia.TRANSITO,
                "G-01",
                "Exceso de velocidad",
                Alicuota.de("8"),
                "Retencion de licencia",
                (short) 4,
                "RNT art. 300",
                vigenciaDesde);
    }
}
