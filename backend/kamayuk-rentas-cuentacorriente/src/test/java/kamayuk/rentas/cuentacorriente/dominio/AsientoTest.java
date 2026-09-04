package kamayuk.rentas.cuentacorriente.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El libro se prueba sin Spring y sin base de datos (regla 7): lo que defiende esta clase son las
 * invariantes que la base tambien exige, para que fallen aqui primero.
 */
@DisplayName("ADR-0006 — El asiento")
class AsientoTest {

    private static final Ejercicio EJERCICIO_2026 = new Ejercicio(2026);

    @Test
    @DisplayName("un monto negativo o cero se rechaza: el signo lo pone el tipo, no el importe")
    void unMontoNoPositivoSeRechaza() {
        assertThatThrownBy(() -> asientoDe(Concepto.INSOLUTO, Dinero.CERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ANULACION sin motivo se rechaza (asiento_motivo_ck)")
    void anulacionSinMotivoSeRechaza() {
        assertThatThrownBy(() -> asientoDe(Concepto.ANULACION, Dinero.de(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("motivo");
    }

    @Test
    @DisplayName("INSOLUTO sin motivo se admite: solo lo exigen los tres conceptos de la lista")
    void insolutoSinMotivoSeAdmite() {
        Asiento asiento = asientoDe(Concepto.INSOLUTO, Dinero.de(100));
        assertThat(asiento.motivo()).isNull();
    }

    @Test
    @DisplayName("un asiento nuevo no tiene id, usuario ni asiento reversado")
    void unAsientoNuevoNoTieneId() {
        Asiento asiento = asientoDe(Concepto.PAGO, Dinero.de(50));

        assertThat(asiento.esNuevo()).isTrue();
        assertThat(asiento.id()).isNull();
        assertThat(asiento.usuarioId()).isNull();
        assertThat(asiento.asientoReversadoId()).isNull();
    }

    @Test
    @DisplayName("la reversion lleva el tipo opuesto y apunta al original")
    void laReversionLlevaElTipoOpuesto() {
        Asiento cargo =
                new Asiento(
                        10L,
                        EJERCICIO_2026,
                        1L,
                        "PREDIAL",
                        Concepto.INSOLUTO,
                        TipoAsiento.CARGO,
                        Fase.ORDINARIA,
                        1,
                        5L,
                        null,
                        null,
                        Dinero.de(100),
                        LocalDate.of(2026, 3, 1),
                        "EM-2026-0001",
                        null,
                        "cajera.ventanilla",
                        "insoluto de la primera cuota",
                        null);

        Asiento reversion =
                Asiento.reversionDe(
                        cargo,
                        LocalDate.of(2026, 4, 15),
                        "NC-2026-0001",
                        "se emitio con el predio equivocado");

        assertThat(reversion.tipo()).isEqualTo(TipoAsiento.ABONO);
        assertThat(reversion.asientoReversadoId()).isEqualTo(10L);
        assertThat(reversion.monto()).isEqualTo(cargo.monto());
        assertThat(reversion.contribuyenteId()).isEqualTo(cargo.contribuyenteId());
        assertThat(reversion.ejercicio())
                .as("cae en la particion de su propia fecha, no en la del original")
                .isEqualTo(Ejercicio.de(LocalDate.of(2026, 4, 15)));
        assertThat(cargo.tipo())
                .as("el original no cambia: la reversion es un asiento nuevo, no una edicion")
                .isEqualTo(TipoAsiento.CARGO);
    }

    @Test
    @DisplayName("no se reversa un asiento que todavia no tiene id")
    void noSeReversaUnAsientoSinId() {
        Asiento sinGuardar = asientoDe(Concepto.PAGO, Dinero.de(50));

        assertThatThrownBy(
                        () ->
                                Asiento.reversionDe(
                                        sinGuardar,
                                        LocalDate.of(2026, 4, 1),
                                        "NC-0001",
                                        "correccion"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("el tributo se recorta y se guarda en mayusculas")
    void elTributoSeNormaliza() {
        Asiento asiento =
                Asiento.nuevo(
                        EJERCICIO_2026,
                        1L,
                        "  predial  ",
                        Concepto.PAGO,
                        TipoAsiento.ABONO,
                        Fase.ORDINARIA,
                        null,
                        null,
                        null,
                        null,
                        Dinero.de(50),
                        LocalDate.of(2026, 3, 1),
                        "REC-2026-0001");

        assertThat(asiento.tributo()).isEqualTo("PREDIAL");
    }

    private static Asiento asientoDe(Concepto concepto, Dinero monto) {
        return Asiento.nuevo(
                EJERCICIO_2026,
                1L,
                "PREDIAL",
                concepto,
                TipoAsiento.CARGO,
                Fase.ORDINARIA,
                1,
                5L,
                null,
                null,
                monto,
                LocalDate.of(2026, 3, 1),
                "EM-2026-0001");
    }
}
