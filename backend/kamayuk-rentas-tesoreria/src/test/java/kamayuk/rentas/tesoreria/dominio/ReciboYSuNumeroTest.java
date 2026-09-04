package kamayuk.rentas.tesoreria.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** #33 — El recibo y su numeracion, sin base de datos y sin reloj. */
@DisplayName("#33 — El recibo, su numero y su desglose")
class ReciboYSuNumeroTest {

    private static final LocalDate PAGO = LocalDate.of(2026, 3, 15);
    private static final Instant EMISION = Instant.parse("2026-03-15T14:30:00Z");

    @Nested
    @DisplayName("El numero")
    class DelNumero {

        @Test
        @DisplayName("se imprime con la serie de la caja y el correlativo con ceros")
        void seImprimeConSerieYCeros() {
            assertThat(new NumeroDeRecibo("001", 123).impreso()).isEqualTo("001-0000123");
        }

        @Test
        @DisplayName("la caja compone el numero con SU serie: dos cajas no comparten correlativo")
        void cadaCajaTieneSuSerie() {
            Caja tributaria = new Caja(1L, "C-1", "Caja tributaria", "001", null, true);
            Caja tasas = new Caja(2L, "C-2", "Caja de tasas", "002", 9L, true);

            assertThat(tributaria.numero(7).impreso()).isEqualTo("001-0000007");
            assertThat(tasas.numero(7).impreso()).isEqualTo("002-0000007");
        }

        @Test
        @DisplayName("un correlativo que empieza en cero se rechaza")
        void elCorrelativoEmpiezaEnUno() {
            assertThatThrownBy(() -> new NumeroDeRecibo("001", 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empieza en 1");
        }

        @Test
        @DisplayName("una serie mas larga que la columna se rechaza aqui, no en la base")
        void laSerieCabeEnLaColumna() {
            assertThatThrownBy(() -> new NumeroDeRecibo("DEMASIADO-LARGA", 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("de 1 a 5");
        }
    }

    @Nested
    @DisplayName("El desglose")
    class DelDesglose {

        @Test
        @DisplayName("el total de una linea es la suma de sus cuatro partes, nunca otra cifra")
        void elTotalEsLaSuma() {
            LineaDeRecibo linea =
                    deudaPredial(
                            Dinero.de("100.00"),
                            Dinero.de("5.00"),
                            Dinero.de("12.30"),
                            Dinero.de("2.70"));

            assertThat(linea.monto()).isEqualTo(Dinero.de("120.00"));
        }

        @Test
        @DisplayName("el total del recibo es la suma de sus lineas")
        void elTotalDelReciboEsLaSuma() {
            Recibo recibo =
                    reciboCon(
                            List.of(
                                    deudaPredial(
                                            Dinero.de("100.00"),
                                            Dinero.CERO,
                                            Dinero.CERO,
                                            Dinero.CERO),
                                    deudaPredial(
                                            Dinero.de("50.50"),
                                            Dinero.CERO,
                                            Dinero.CERO,
                                            Dinero.CERO)));

            assertThat(recibo.total()).isEqualTo(Dinero.de("150.50"));
        }

        @Test
        @DisplayName("una linea de tasa lleva su cantidad y su precio, o ninguno de los dos")
        void laLineaDeTasaEsCoherente() {
            assertThatThrownBy(
                            () ->
                                    new LineaDeRecibo(
                                            "T-001",
                                            "TASA",
                                            null,
                                            null,
                                            9L,
                                            null,
                                            null,
                                            null,
                                            // Con tasa_id pero sin cantidad: la base lo rechaza
                                            // con recibo_detalle_tasa_ck, y esto lo rechaza antes.
                                            null,
                                            Dinero.de("10.00"),
                                            Dinero.de("10.00"),
                                            Dinero.CERO,
                                            Dinero.CERO,
                                            Dinero.CERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cantidad");
        }

        @Test
        @DisplayName("no se cobra en negativo: una devolucion se documenta aparte")
        void nadaEnNegativo() {
            assertThatThrownBy(
                            () ->
                                    deudaPredial(
                                            Dinero.de("100.00"),
                                            Dinero.CERO,
                                            Dinero.de("-1.00"),
                                            Dinero.CERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("devolucion");
        }
    }

    @Nested
    @DisplayName("El recibo")
    class DelRecibo {

        @Test
        @DisplayName("dice a que fecha estaban actualizados los importes que cobro (RNF-075)")
        void diceSuFechaDeActualizacion() {
            assertThat(
                            reciboCon(
                                            List.of(
                                                    deudaPredial(
                                                            Dinero.de("10.00"),
                                                            Dinero.CERO,
                                                            Dinero.CERO,
                                                            Dinero.CERO)))
                                    .actualizadoA())
                    .isEqualTo(PAGO);
        }

        @Test
        @DisplayName("sin lineas no se emite: no documentaria nada")
        void sinLineasNoSeEmite() {
            assertThatThrownBy(() -> reciboCon(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no documenta nada");
        }

        @Test
        @DisplayName("sin fecha de actualizacion no se construye (regla 9)")
        void sinFechaNoSeConstruye() {
            assertThatThrownBy(
                            () ->
                                    new Recibo(
                                            null,
                                            new NumeroDeRecibo("001", 1),
                                            1L,
                                            1L,
                                            "cajero",
                                            7L,
                                            EMISION,
                                            FormaDePago.EFECTIVO,
                                            TipoDePago.NORMAL,
                                            null,
                                            null,
                                            Observacion.de("Cobranza en ventanilla"),
                                            List.of(
                                                    deudaPredial(
                                                            Dinero.de("10.00"),
                                                            Dinero.CERO,
                                                            Dinero.CERO,
                                                            Dinero.CERO))))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("RNF-075");
        }
    }

    // ------------------------------------------------------------------

    private static LineaDeRecibo deudaPredial(
            Dinero insoluto, Dinero reajuste, Dinero interes, Dinero gasto) {
        return new LineaDeRecibo(
                "PREDIAL",
                "PAGO",
                new Ejercicio(2025),
                null,
                null,
                55L,
                null,
                null,
                null,
                null,
                insoluto,
                reajuste,
                interes,
                gasto);
    }

    private static Recibo reciboCon(List<LineaDeRecibo> lineas) {
        return new Recibo(
                null,
                new NumeroDeRecibo("001", 1),
                1L,
                1L,
                "cajero.prueba",
                7L,
                EMISION,
                FormaDePago.EFECTIVO,
                TipoDePago.NORMAL,
                null,
                PAGO,
                Observacion.de("Cobranza en ventanilla"),
                lineas);
    }
}
