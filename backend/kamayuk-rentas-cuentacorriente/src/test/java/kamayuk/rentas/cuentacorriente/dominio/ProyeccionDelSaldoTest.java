package kamayuk.rentas.cuentacorriente.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La proyeccion del saldo, sin Spring, sin Docker y sin reloj (#23, regla 6).
 *
 * <p>Aqui no hay ninguna cifra tributaria: los importes son de relleno y lo que se prueba es como
 * se agrupa y se netea, no cuanto vale (D-02).
 */
@DisplayName("#23 — Proyeccion del saldo")
class ProyeccionDelSaldoTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Instant CALCULO = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    @DisplayName("netea cargos contra abonos de insoluto")
    void neteaInsoluto() {
        List<Asiento> libro =
                List.of(
                        asiento(1L, Concepto.INSOLUTO, TipoAsiento.CARGO, Dinero.de(1000), 1),
                        asiento(2L, Concepto.INSOLUTO, TipoAsiento.ABONO, Dinero.de(400), 1));

        assertThat(ProyeccionDelSaldo.de(libro, CALCULO))
                .singleElement()
                .extracting(SaldoProyectado::insolutoSaldo)
                .isEqualTo(Dinero.de(600));
    }

    @Test
    @DisplayName("solo el insoluto mueve el saldo: el interes y el gasto no se precalculan")
    void soloElInsolutoMueveElSaldo() {
        List<Asiento> libro =
                List.of(
                        asiento(1L, Concepto.INSOLUTO, TipoAsiento.CARGO, Dinero.de(1000), 1),
                        asiento(2L, Concepto.INTERES, TipoAsiento.CARGO, Dinero.de(50), 1),
                        asiento(3L, Concepto.GASTO, TipoAsiento.CARGO, Dinero.de(30), 1));

        assertThat(ProyeccionDelSaldo.de(libro, CALCULO))
                .singleElement()
                .extracting(SaldoProyectado::insolutoSaldo)
                .as("el interes depende de la fecha en que se pregunte: no se puede cachear")
                .isEqualTo(Dinero.de(1000));
    }

    @Test
    @DisplayName("cada obligacion es una fila: dos cuotas no se suman entre si")
    void cadaObligacionEsUnaFila() {
        List<Asiento> libro =
                List.of(
                        asiento(1L, Concepto.INSOLUTO, TipoAsiento.CARGO, Dinero.de(100), 1),
                        asiento(2L, Concepto.INSOLUTO, TipoAsiento.CARGO, Dinero.de(200), 2));

        assertThat(ProyeccionDelSaldo.de(libro, CALCULO))
                .hasSize(2)
                .extracting(SaldoProyectado::insolutoSaldo)
                .containsExactlyInAnyOrder(Dinero.de(100), Dinero.de(200));
    }

    @Test
    @DisplayName("el ultimo asiento y la fase salen del identificador mayor, no de la fecha valor")
    void elUltimoAsientoSaleDelIdentificadorMayor() {
        // Dos asientos con la MISMA fecha valor: por fecha no habria forma de decidir
        // cual es el ultimo, y la fase quedaria a merced del orden de lectura.
        List<Asiento> libro =
                List.of(
                        asiento(7L, Concepto.INSOLUTO, TipoAsiento.CARGO, Dinero.de(100), 1),
                        conFase(
                                asiento(9L, Concepto.INSOLUTO, TipoAsiento.CARGO, Dinero.de(50), 1),
                                Fase.COACTIVA));

        assertThat(ProyeccionDelSaldo.de(libro, CALCULO))
                .singleElement()
                .satisfies(
                        saldo -> {
                            assertThat(saldo.ultimoAsientoId()).isEqualTo(9L);
                            assertThat(saldo.fase()).isEqualTo(Fase.COACTIVA);
                        });
    }

    @Test
    @DisplayName("es pura: el mismo libro y el mismo instante dan la misma proyeccion")
    void esPura() {
        List<Asiento> libro =
                List.of(asiento(1L, Concepto.INSOLUTO, TipoAsiento.CARGO, Dinero.de(1234), 1));

        assertThat(ProyeccionDelSaldo.de(libro, CALCULO))
                .isEqualTo(ProyeccionDelSaldo.de(libro, CALCULO));
    }

    @Test
    @DisplayName("un libro vacio no proyecta ninguna fila")
    void unLibroVacioNoProyectaNada() {
        assertThat(ProyeccionDelSaldo.de(List.of(), CALCULO)).isEmpty();
    }

    @Test
    @DisplayName("el periodo nulo del asiento se proyecta como 0, no como una obligacion aparte")
    void elPeriodoNuloSeProyectaComoCero() {
        Asiento sinPeriodo =
                new Asiento(
                        1L,
                        EJERCICIO,
                        1L,
                        "PREDIAL",
                        Concepto.INSOLUTO,
                        TipoAsiento.CARGO,
                        Fase.ORDINARIA,
                        null,
                        null,
                        null,
                        null,
                        Dinero.de(100),
                        LocalDate.of(2026, 3, 1),
                        "EM-2026-0001",
                        null,
                        null,
                        null,
                        null);

        assertThat(ProyeccionDelSaldo.de(List.of(sinPeriodo), CALCULO))
                .singleElement()
                .extracting(saldo -> saldo.clave().periodo())
                .isEqualTo(0);
    }

    // ------------------------------------------------------------------

    private static Asiento asiento(
            long id, Concepto concepto, TipoAsiento tipo, Dinero monto, int periodo) {
        return new Asiento(
                id,
                EJERCICIO,
                1L,
                "PREDIAL",
                concepto,
                tipo,
                Fase.ORDINARIA,
                periodo,
                null,
                null,
                null,
                monto,
                LocalDate.of(2026, 3, 1),
                "EM-2026-0001",
                null,
                null,
                concepto.exigeMotivo() ? "motivo de la prueba" : null,
                null);
    }

    private static Asiento conFase(Asiento asiento, Fase fase) {
        return new Asiento(
                asiento.id(),
                asiento.ejercicio(),
                asiento.contribuyenteId(),
                asiento.tributo(),
                asiento.concepto(),
                asiento.tipo(),
                fase,
                asiento.periodo(),
                asiento.predioId(),
                asiento.vehiculoId(),
                asiento.referenciaExterna(),
                asiento.monto(),
                asiento.fechaValor(),
                asiento.documentoOrigen(),
                asiento.asientoReversadoId(),
                asiento.usuarioId(),
                asiento.motivo(),
                asiento.acto());
    }
}
