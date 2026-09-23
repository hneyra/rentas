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
                .as(
                        "es el del original: la reversion es de su misma obligacion (#424). Dentro"
                                + " de 2026 no distingue nada; lo distingue la muestra de abajo")
                .isEqualTo(cargo.ejercicio());
        assertThat(cargo.tipo())
                .as("el original no cambia: la reversion es un asiento nuevo, no una edicion")
                .isEqualTo(TipoAsiento.CARGO);
    }

    /**
     * La muestra que faltaba (#424): una reversion que <b>cruza el ano</b>. Con el original y la
     * reversion dentro de 2026 —la unica muestra que habia— «el ejercicio del original» y «el de la
     * fecha de la reversion» son el mismo numero, y la prueba pasa igual con cualquiera de los dos.
     *
     * <p>Es el escenario del issue: ARBITRIO 2026, cuota 12, del predio 7, cobrado por caja el
     * 2027-01-05 y anulado ese mismo dia. El abono se asento en el ejercicio de la cuota; su
     * reversion tiene que caer en la misma obligacion, o la deuda no vuelve a la que se pago y
     * aparece otra en 2027 que nadie emitio.
     */
    @Test
    @DisplayName(
            "#424 — la reversion de enero de 2027 de un abono de 2026 es de la obligacion 2026")
    void laReversionQueCruzaElAnoEsDeLaMismaObligacion() {
        Asiento abono = abonoDeCaja(new Ejercicio(2026), 12, LocalDate.of(2027, 1, 5));

        Asiento reversion =
                Asiento.reversionDe(
                        abono,
                        LocalDate.of(2027, 1, 5),
                        "ANULACION 001-123",
                        "recibo mal cobrado, anulado en el dia");

        assertThat(reversion.ejercicio())
                .as("el de la obligacion que se cobro, no el de la fecha de la anulacion")
                .isEqualTo(new Ejercicio(2026));
        assertThat(ClaveDeSaldo.de(reversion))
                .as("la reversion deshace el abono en SU obligacion: misma clave de saldo")
                .isEqualTo(ClaveDeSaldo.de(abono));
        assertThat(reversion.fechaValor())
                .as("y la fecha valor sigue siendo la de la anulacion (C-1, regla 9)")
                .isEqualTo(LocalDate.of(2027, 1, 5));
    }

    @Test
    @DisplayName("#424 — y al reves: anular en diciembre de 2026 un adelanto de 2027 vuelve a 2027")
    void laReversionDeUnAdelantoVuelveAlEjercicioSiguiente() {
        Asiento adelanto = abonoDeCaja(new Ejercicio(2027), 1, LocalDate.of(2026, 12, 15));

        Asiento reversion =
                Asiento.reversionDe(
                        adelanto,
                        LocalDate.of(2026, 12, 15),
                        "ANULACION 001-124",
                        "adelanto cobrado al contribuyente equivocado");

        assertThat(reversion.ejercicio()).isEqualTo(new Ejercicio(2027));
        assertThat(ClaveDeSaldo.de(reversion)).isEqualTo(ClaveDeSaldo.de(adelanto));
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

    /**
     * El abono de insoluto que la cobranza escribe (#33): con el ejercicio <b>de la cuota</b> y la
     * fecha <b>de pago</b>, como hace {@code RegistroDeAbonosCuentaCorriente}. Ya guardado, que es
     * lo unico que se puede reversar.
     */
    private static Asiento abonoDeCaja(Ejercicio ejercicio, int cuota, LocalDate fechaDePago) {
        return new Asiento(
                20L,
                ejercicio,
                1L,
                "ARBITRIO",
                Concepto.INSOLUTO,
                TipoAsiento.ABONO,
                Fase.ORDINARIA,
                cuota,
                7L,
                null,
                null,
                Dinero.de("20.00"),
                fechaDePago,
                "RECIBO 001-123",
                null,
                "cajera.ventanilla",
                "cobro en ventanilla",
                null);
    }
}
