package kamayuk.rentas.nucleo.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El beneficio se prueba sin Spring y sin base de datos (regla 7): registro puro, sin ninguna regla
 * de calculo (D-02a).
 */
@DisplayName("RF-029 — Beneficios y exoneraciones")
class BeneficioTest {

    @Test
    @DisplayName("un beneficio sin porcentaje ni monto se rechaza (beneficio_valor_ck)")
    void sinPorcentajeNiMontoSeRechaza() {
        assertThatThrownBy(() -> beneficio(null, null, LocalDate.of(2026, 1, 1), null, "Ley 27157"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un beneficio sin base legal no se guarda")
    void sinBaseLegalNoSeGuarda() {
        assertThatThrownBy(
                        () ->
                                beneficio(
                                        Alicuota.de("50"),
                                        null,
                                        LocalDate.of(2026, 1, 1),
                                        null,
                                        "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un monto negativo se rechaza")
    void unMontoNegativoSeRechaza() {
        assertThatThrownBy(
                        () ->
                                beneficio(
                                        null,
                                        new Dinero(new java.math.BigDecimal("-1")),
                                        LocalDate.of(2026, 1, 1),
                                        null,
                                        "Ley 27157"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("vigencia_hasta no puede ser anterior a vigencia_desde")
    void vigenciaHastaAntesDeVigenciaDesdeSeRechaza() {
        assertThatThrownBy(
                        () ->
                                beneficio(
                                        Alicuota.de("50"),
                                        null,
                                        LocalDate.of(2026, 6, 1),
                                        LocalDate.of(2026, 1, 1),
                                        "Ley 27157"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rigeEn: los dos extremos entran")
    void rigeEnLosDosExtremos() {
        Beneficio b =
                beneficio(
                        Alicuota.de("50"),
                        null,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        "Ley 27157");

        assertThat(b.rigeEn(LocalDate.of(2026, 1, 1))).isTrue();
        assertThat(b.rigeEn(LocalDate.of(2026, 12, 31))).isTrue();
        assertThat(b.rigeEn(LocalDate.of(2025, 12, 31))).isFalse();
        assertThat(b.rigeEn(LocalDate.of(2027, 1, 1))).isFalse();
    }

    @Test
    @DisplayName("dos rangos que se cruzan solapan")
    void dosRangosQueSeCruzanSolapan() {
        Beneficio a =
                beneficio(
                        Alicuota.de("50"),
                        null,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 6, 30),
                        "Ley 27157");
        Beneficio b =
                beneficio(
                        Alicuota.de("50"),
                        null,
                        LocalDate.of(2026, 6, 1),
                        LocalDate.of(2026, 12, 31),
                        "Ley 27157");

        assertThat(a.solapaCon(b)).isTrue();
        assertThat(b.solapaCon(a)).isTrue();
    }

    @Test
    @DisplayName("dos rangos consecutivos, sin cruzarse, no solapan")
    void dosRangosConsecutivosNoSolapan() {
        Beneficio a =
                beneficio(
                        Alicuota.de("50"),
                        null,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 6, 30));
        Beneficio b =
                beneficio(
                        Alicuota.de("50"),
                        null,
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 12, 31));

        assertThat(a.solapaCon(b)).isFalse();
    }

    @Test
    @DisplayName("un beneficio vigente (sin hasta) solapa con cualquiera que empiece despues")
    void unVigenteSolapaConCualquieraQueEmpieceDespues() {
        Beneficio vigente = beneficio(Alicuota.de("50"), null, LocalDate.of(2026, 1, 1), null);
        Beneficio futuro =
                beneficio(
                        Alicuota.de("50"),
                        null,
                        LocalDate.of(2030, 1, 1),
                        LocalDate.of(2031, 1, 1));

        assertThat(vigente.solapaCon(futuro)).isTrue();
    }

    @Test
    @DisplayName("cesar dos veces el mismo beneficio falla")
    void cesarDosVecesFalla() {
        Beneficio cesado =
                beneficio(Alicuota.de("50"), null, LocalDate.of(2026, 1, 1), null)
                        .cesadoEl(LocalDate.of(2026, 6, 30));

        assertThatThrownBy(() -> cesado.cesadoEl(LocalDate.of(2026, 7, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("no se puede cesar antes de que empiece a regir")
    void noSePuedeCesarAntesDeQueEmpieceARegir() {
        Beneficio b = beneficio(Alicuota.de("50"), null, LocalDate.of(2026, 6, 1), null);

        assertThatThrownBy(() -> b.cesadoEl(LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cesar deja vigenciaHasta puesta y el resto de la fila intacto")
    void cesarDejaVigenciaHastaPuesta() {
        Beneficio original = beneficio(Alicuota.de("50"), null, LocalDate.of(2026, 1, 1), null);

        Beneficio cesado = original.cesadoEl(LocalDate.of(2026, 6, 30));

        assertThat(cesado.estaVigente()).isFalse();
        assertThat(cesado.vigenciaHasta()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(cesado.tipo()).isEqualTo(original.tipo());
        assertThat(cesado.baseLegal()).isEqualTo(original.baseLegal());
    }

    private static Beneficio beneficio(
            @Nullable Alicuota porcentaje,
            @Nullable Dinero monto,
            LocalDate desde,
            @Nullable LocalDate hasta) {
        return beneficio(porcentaje, monto, desde, hasta, "Ley 27157");
    }

    private static Beneficio beneficio(
            @Nullable Alicuota porcentaje,
            @Nullable Dinero monto,
            LocalDate desde,
            @Nullable LocalDate hasta,
            String baseLegal) {
        Beneficio nuevo =
                Beneficio.nuevo(
                        1L,
                        null,
                        null,
                        "PENSIONISTA",
                        "PREDIAL",
                        Clase.DEDUCCION,
                        porcentaje,
                        monto,
                        desde,
                        baseLegal,
                        "RESOLUCION-2026-0001",
                        Observacion.de("Se registra el beneficio del pensionista"));
        if (hasta == null) {
            return nuevo;
        }
        // El constructor de nuevo() no admite vigenciaHasta; se cierra aparte para las
        // pruebas que necesitan un rango ya cerrado.
        return new Beneficio(
                nuevo.id(),
                nuevo.contribuyenteId(),
                nuevo.predioId(),
                nuevo.vehiculoId(),
                nuevo.tipo(),
                nuevo.tributo(),
                nuevo.clase(),
                nuevo.porcentaje(),
                nuevo.monto(),
                nuevo.vigenciaDesde(),
                hasta,
                nuevo.baseLegal(),
                nuevo.documentoOrigen(),
                nuevo.observacion());
    }
}
