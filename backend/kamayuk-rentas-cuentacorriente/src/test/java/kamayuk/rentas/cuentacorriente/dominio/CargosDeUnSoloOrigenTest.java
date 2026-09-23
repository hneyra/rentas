package kamayuk.rentas.cuentacorriente.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #371 — la Specification de la contencion, sin base de datos (regla 6).
 *
 * <p>La siembra no es uniforme: junto a los cargos de la papeleta van los tres cargos que NO
 * originan deuda —el del movimiento de fase, la reversion de un abono y el interes que cristaliza
 * una cobranza—, con referencia ajena o sin ninguna. Con solo cargos de insoluto, una Specification
 * que contara todo {@code CARGO} pasaria igual.
 */
@DisplayName("#371 — CargosDeUnSoloOrigen: quien origino la deuda de una obligacion")
class CargosDeUnSoloOrigenTest {

    private static final String T001 = "PAPELETA-1";
    private static final String T002 = "PAPELETA-2";

    @Test
    @DisplayName("una obligacion que solo cargo T-001 no tiene otros origenes")
    void soloSuya() {
        assertThat(CargosDeUnSoloOrigen.otrosOrigenes(List.of(cargo(T001, "ACTA 1")), T001))
                .isEmpty();
    }

    @Test
    @DisplayName("con el cargo de T-002 en la misma obligacion, la nombra una sola vez")
    void compartidaConOtraPapeleta() {
        assertThat(
                        CargosDeUnSoloOrigen.otrosOrigenes(
                                List.of(
                                        cargo(T001, "ACTA 1"),
                                        cargo(T002, "ACTA 2"),
                                        cargo(T002, "ACTA 2 BIS")),
                                T001))
                .containsExactly(T002);
    }

    @Test
    @DisplayName("un cargo de insoluto sin referencia es de otro origen, nombrado por su documento")
    void sinReferenciaNoSeDaPorSuyo() {
        assertThat(
                        CargosDeUnSoloOrigen.otrosOrigenes(
                                List.of(cargo(T001, "ACTA 1"), cargo(null, "PADRON MIGRADO")),
                                T001))
                .containsExactly("documento PADRON MIGRADO");
    }

    @Test
    @DisplayName("el movimiento de fase, la reversion y el interes no originan deuda")
    void losCargosQueNoOriginanDeuda() {
        Asiento fase =
                Asiento.nuevoConMotivo(
                        new Ejercicio(2026),
                        501,
                        "MULTA_TRANSITO",
                        Concepto.AJUSTE,
                        TipoAsiento.CARGO,
                        Fase.VALOR,
                        null,
                        null,
                        null,
                        "VALOR-RM-2026-000010",
                        Dinero.de("660.00"),
                        LocalDate.of(2026, 4, 15),
                        "RM-2026-000010",
                        "pase a valor");
        Asiento reversion =
                new Asiento(
                        null,
                        new Ejercicio(2026),
                        501,
                        "MULTA_TRANSITO",
                        Concepto.INSOLUTO,
                        TipoAsiento.CARGO,
                        Fase.ORDINARIA,
                        null,
                        null,
                        null,
                        null,
                        Dinero.de("100.00"),
                        LocalDate.of(2026, 5, 2),
                        "ANULA RECIBO 001-0000042",
                        77L,
                        null,
                        "recibo anulado",
                        null,
                        false,
                        null);
        Asiento interes =
                Asiento.nuevo(
                        new Ejercicio(2026),
                        501,
                        "MULTA_TRANSITO",
                        Concepto.INTERES,
                        TipoAsiento.CARGO,
                        Fase.ORDINARIA,
                        null,
                        null,
                        null,
                        null,
                        Dinero.de("3.20"),
                        LocalDate.of(2026, 5, 1),
                        "RECIBO 001-0000042");

        assertThat(
                        CargosDeUnSoloOrigen.otrosOrigenes(
                                List.of(cargo(T001, "ACTA 1"), fase, reversion, interes), T001))
                .as(
                        "ninguno de los tres es otra papeleta: contarlos rechazaria la baja de una sola")
                .isEmpty();
    }

    private static Asiento cargo(@Nullable String referencia, String documento) {
        return Asiento.nuevo(
                new Ejercicio(2026),
                501,
                "MULTA_TRANSITO",
                Concepto.INSOLUTO,
                TipoAsiento.CARGO,
                Fase.ORDINARIA,
                null,
                null,
                null,
                referencia,
                Dinero.de("440.00"),
                LocalDate.of(2026, 3, 4),
                documento);
    }
}
