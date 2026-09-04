package kamayuk.rentas.tesoreria.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.dominio.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #36 — El arqueo de un turno, sin base y sin reloj.
 *
 * <p>Aqui se prueba la <b>aritmetica</b> del arqueo: que el neto sea lo cobrado menos lo anulado,
 * que las partes sumen el total al centimo, que la diferencia pueda ser negativa y que las
 * anulaciones no cuenten como cobro. Todo lo demas —el privilegio, la concurrencia, el cuadre
 * contra el libro— vive en las otras dos suites.
 */
@DisplayName("#36 — El arqueo del turno")
class ArqueoDelTurnoTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 15);
    private static final long TURNO = 7L;

    @Nested
    @DisplayName("Las partes suman el total")
    class DeLaSuma {

        @Test
        @DisplayName("el neto es lo cobrado menos lo anulado, y no lo anulado ignorado")
        void elNetoRestaLoAnulado() {
            ArqueoDelTurno arqueo =
                    ArqueoDelTurno.de(
                            TURNO,
                            List.of(
                                    recibo(1, FormaDePago.EFECTIVO, "300.00", "0.00"),
                                    recibo(2, FormaDePago.EFECTIVO, "120.00", "120.00")),
                            Map.of(),
                            HOY);

            assertThat(arqueo.totalCobrado()).isEqualTo(Dinero.de("420.00"));
            assertThat(arqueo.totalAnulado()).isEqualTo(Dinero.de("120.00"));
            assertThat(arqueo.neto())
                    .as("el recibo anulado entro y salio: no deja nada en el cajon")
                    .isEqualTo(Dinero.de("300.00"));
            assertThat(arqueo.recibosEmitidos()).isEqualTo(2);
            assertThat(arqueo.recibosAnulados()).isEqualTo(1);
        }

        @Test
        @DisplayName("la suma de las lineas es el total, al centimo, sin redondeo de por medio")
        void lasLineasSumanElTotalExacto() {
            // Importes que no se dividen bien a proposito: si en algun sitio hubiera una
            // division, aqui saldria un centimo huerfano.
            ArqueoDelTurno arqueo =
                    ArqueoDelTurno.de(
                            TURNO,
                            List.of(
                                    recibo(1, FormaDePago.EFECTIVO, "33.33", "0.00"),
                                    recibo(2, FormaDePago.TARJETA, "33.33", "0.00"),
                                    recibo(3, FormaDePago.DEPOSITO, "33.34", "0.00")),
                            Map.of(),
                            HOY);

            Dinero sumaDeLasPartes = Dinero.CERO;
            for (LineaDeArqueo linea : arqueo.lineas()) {
                sumaDeLasPartes = sumaDeLasPartes.mas(linea.neto());
            }
            assertThat(sumaDeLasPartes)
                    .as("la distribucion reparte filas, no prorratea: no hay centimo huerfano")
                    .isEqualTo(arqueo.neto())
                    .isEqualTo(Dinero.de("100.00"));
        }

        @Test
        @DisplayName("las lineas salen en el orden del enumerado, no en el de llegada")
        void elOrdenEsEstable() {
            ArqueoDelTurno arqueo =
                    ArqueoDelTurno.de(
                            TURNO,
                            List.of(
                                    recibo(1, FormaDePago.TRANSFERENCIA, "10.00", "0.00"),
                                    recibo(2, FormaDePago.EFECTIVO, "20.00", "0.00"),
                                    recibo(3, FormaDePago.DEPOSITO, "30.00", "0.00")),
                            Map.of(),
                            HOY);

            assertThat(arqueo.lineas().stream().map(LineaDeArqueo::formaDePago))
                    .containsExactly(
                            FormaDePago.EFECTIVO, FormaDePago.DEPOSITO, FormaDePago.TRANSFERENCIA);
        }

        @Test
        @DisplayName("un medio de pago sin movimiento y sin declaracion no aporta una fila")
        void sinMovimientoNoHayFila() {
            ArqueoDelTurno arqueo =
                    ArqueoDelTurno.de(
                            TURNO,
                            List.of(recibo(1, FormaDePago.EFECTIVO, "50.00", "0.00")),
                            Map.of(),
                            HOY);

            assertThat(arqueo.lineas()).hasSize(1);
        }

        @Test
        @DisplayName("pero un medio declarado sin movimiento si: es un descuadre que hay que ver")
        void loDeclaradoSinMovimientoSiApareceEnElActa() {
            ArqueoDelTurno arqueo =
                    ArqueoDelTurno.de(
                            TURNO,
                            List.of(recibo(1, FormaDePago.EFECTIVO, "50.00", "0.00")),
                            Map.of(
                                    FormaDePago.EFECTIVO, Dinero.de("50.00"),
                                    FormaDePago.CHEQUE, Dinero.de("20.00")),
                            HOY);

            assertThat(arqueo.lineas()).hasSize(2);
            assertThat(arqueo.diferencia())
                    .as("veinte soles en cheque declarados que el sistema no registro")
                    .isEqualTo(Dinero.de("20.00"));
        }
    }

    @Nested
    @DisplayName("El descuadre se guarda, no se rechaza")
    class DeLaDiferencia {

        @Test
        @DisplayName("si falta dinero en el cajon, la diferencia sale negativa y el arqueo existe")
        void faltaDineroYElArqueoSeConstruyeIgual() {
            ArqueoDelTurno arqueo =
                    ArqueoDelTurno.de(
                            TURNO,
                            List.of(recibo(1, FormaDePago.EFECTIVO, "500.00", "0.00")),
                            Map.of(FormaDePago.EFECTIVO, Dinero.de("490.00")),
                            HOY);

            assertThat(arqueo.diferencia()).isEqualTo(Dinero.de("-10.00"));
            assertThat(arqueo.cuadra()).isFalse();
        }

        @Test
        @DisplayName("y si cuadra, la diferencia es cero")
        void cuandoCuadraLaDiferenciaEsCero() {
            ArqueoDelTurno arqueo =
                    ArqueoDelTurno.de(
                            TURNO,
                            List.of(recibo(1, FormaDePago.EFECTIVO, "500.00", "100.00")),
                            Map.of(FormaDePago.EFECTIVO, Dinero.de("400.00")),
                            HOY);

            assertThat(arqueo.neto()).isEqualTo(Dinero.de("400.00"));
            assertThat(arqueo.diferencia()).isEqualTo(Dinero.CERO);
            assertThat(arqueo.cuadra()).isTrue();
        }
    }

    @Nested
    @DisplayName("Lo que no puede construirse")
    class DeLoImposible {

        @Test
        @DisplayName("una anulacion no puede sacar del cajon mas de lo que entro por ese medio")
        void noSeAnulaMasDeLoCobrado() {
            assertThatThrownBy(
                            () ->
                                    new LineaDeArqueo(
                                            FormaDePago.EFECTIVO,
                                            Dinero.de("100.00"),
                                            Dinero.de("150.00"),
                                            Dinero.CERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no puede sacar del cajon mas de lo que entro");
        }

        @Test
        @DisplayName("un recibo no puede tener anulado mas que su total")
        void noSeReversaMasDeLoCobrado() {
            assertThatThrownBy(
                            () ->
                                    new ReciboDelTurno(
                                            new NumeroDeRecibo("001", 1),
                                            TipoDePago.NORMAL,
                                            FormaDePago.EFECTIVO,
                                            Dinero.de("100.00"),
                                            Dinero.de("101.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("congela el total del recibo");
        }

        @Test
        @DisplayName("no se declara en negativo: eso no es contar, es inventar")
        void noSeDeclaraEnNegativo() {
            assertThatThrownBy(
                            () ->
                                    new LineaDeArqueo(
                                            FormaDePago.EFECTIVO,
                                            Dinero.CERO,
                                            Dinero.CERO,
                                            Dinero.de("-1.00")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Que abona en el libro y que no")
    class DelCuadre {

        @Test
        @DisplayName("una tasa y una cuota inicial de convenio no dejan asientos")
        void loQueNoAbona() {
            assertThat(TipoDePago.TASA.abonaEnElLibro()).isFalse();
            assertThat(TipoDePago.PRECONVENIO.abonaEnElLibro()).isFalse();
        }

        @Test
        @DisplayName("una cobranza normal si")
        void loQueAbona() {
            assertThat(TipoDePago.NORMAL.abonaEnElLibro()).isTrue();
        }

        @Test
        @DisplayName("y el documento de origen de un recibo se compone en un solo sitio")
        void elDocumentoDeOrigen() {
            NumeroDeRecibo numero = new NumeroDeRecibo("001", 123);
            assertThat(numero.documentoDeLaCobranza()).isEqualTo("RECIBO 001-0000123");
            assertThat(numero.documentoDeLaAnulacion())
                    .as("distinto del anterior: si no, la segunda anulacion reversaria la primera")
                    .isEqualTo("ANULACION 001-0000123")
                    .isNotEqualTo(numero.documentoDeLaCobranza());
        }
    }

    @Nested
    @DisplayName("El estado del turno se deriva de sus movimientos")
    class DelEstado {

        @Test
        @DisplayName("sin movimientos, abierto")
        void sinMovimientos() {
            assertThat(EstadoDeTurno.deLosMovimientos(List.of())).isEqualTo(EstadoDeTurno.ABIERTO);
        }

        @Test
        @DisplayName("tras un cierre, cerrado; tras su reversion, abierto otra vez")
        void cerrarYReversar() {
            assertThat(EstadoDeTurno.trasElUltimoMovimiento(TipoDeMovimientoDeTurno.CIERRE))
                    .isEqualTo(EstadoDeTurno.CERRADO);
            assertThat(EstadoDeTurno.trasElUltimoMovimiento(TipoDeMovimientoDeTurno.REVERSION))
                    .as("reversar reabre: es la unica forma de seguir cobrando ese dia")
                    .isEqualTo(EstadoDeTurno.ABIERTO);
        }
    }

    // ------------------------------------------------------------------

    private static ReciboDelTurno recibo(
            long numero, FormaDePago forma, String total, String anulado) {
        return new ReciboDelTurno(
                new NumeroDeRecibo("001", numero),
                TipoDePago.NORMAL,
                forma,
                Dinero.de(total),
                Dinero.de(anulado));
    }
}
