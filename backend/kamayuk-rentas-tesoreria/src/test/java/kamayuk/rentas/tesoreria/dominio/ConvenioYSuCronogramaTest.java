package kamayuk.rentas.tesoreria.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #35 — El cronograma, el numero y el estado, <b>sin base de datos y sin reloj</b>.
 *
 * <p>Las tres son funciones puras (regla 6): entran argumentos, sale el resultado. Que se puedan
 * probar asi es exactamente lo que garantiza que reimprimir un compromiso de pago de 2027 en 2037
 * de el mismo centimo.
 *
 * <p><b>Ninguna cifra normativa aparece aqui como esperada.</b> El interes que usan estas pruebas
 * es un valor de prueba, elegido para que las cuentas salgan redondas; lo que se comprueba es la
 * <b>forma</b> del reparto —que el capital cuadre al centimo, que el interes decrezca, que la
 * ultima cuota absorba el descuadre—, no su valor. El valor lo firma D-02b (#191).
 */
@DisplayName("#35 — Convenio, cronograma y estado")
class ConvenioYSuCronogramaTest {

    /** Dos decimales y HALF_UP, como valor de prueba: la politica real entra por parametro. */
    private static final PoliticaDeRedondeo REDONDEO =
            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

    private static final LocalDate PRIMERA = LocalDate.of(2026, 4, 15);

    /** Un interes de prueba, no normativo: 1 % mensual. */
    private static CondicionesDelConvenio condiciones(String interes, int maximo, String inicial) {
        return new CondicionesDelConvenio(Alicuota.de(interes), maximo, Alicuota.de(inicial), 7L);
    }

    @Nested
    @DisplayName("El cronograma")
    class DelCronograma {

        @Test
        @DisplayName("el capital de las cuotas suma exactamente lo fraccionado")
        void elCapitalCuadraAlCentimo() {
            // 100,00 en tres no da tres cifras iguales: deja un centimo huerfano. Si el
            // reparto lo perdiera, el convenio cobraria un centimo de menos en cada
            // convenio, para siempre.
            List<CuotaDeConvenio> cronograma =
                    Cronograma.de(
                            Dinero.de("100.00"), condiciones("0", 12, "0"), 3, PRIMERA, REDONDEO);

            Dinero capital = Dinero.CERO;
            for (CuotaDeConvenio cuota : cronograma) {
                capital = capital.mas(cuota.capital());
            }
            assertThat(capital).isEqualTo(Dinero.de("100.00"));
            assertThat(cronograma).hasSize(3);
            assertThat(cronograma.get(2).capital())
                    .as("la ultima absorbe el descuadre; no se reparte a prorrata")
                    .isEqualTo(Dinero.de("33.34"));
        }

        @Test
        @DisplayName("la cuota inicial es la 0 y no devenga interes")
        void laInicialEsLaCero() {
            List<CuotaDeConvenio> cronograma =
                    Cronograma.de(
                            Dinero.de("1000.00"), condiciones("1", 12, "20"), 4, PRIMERA, REDONDEO);

            assertThat(cronograma).hasSize(5);
            CuotaDeConvenio inicial = cronograma.get(0);
            assertThat(inicial.esInicial()).isTrue();
            assertThat(inicial.numero()).isZero();
            assertThat(inicial.monto()).isEqualTo(Dinero.de("200.00"));
            assertThat(inicial.interes())
                    .as("la inicial se paga en el acto: no financia nada")
                    .isEqualTo(Dinero.CERO);
            assertThat(Cronograma.inicialDe(cronograma)).isEqualTo(Dinero.de("200.00"));
        }

        @Test
        @DisplayName("sin cuota inicial no hay cuota 0")
        void sinInicialNoHayCuotaCero() {
            List<CuotaDeConvenio> cronograma =
                    Cronograma.de(
                            Dinero.de("600.00"), condiciones("0", 12, "0"), 6, PRIMERA, REDONDEO);

            assertThat(cronograma).hasSize(6);
            assertThat(cronograma.get(0).numero()).isEqualTo(1);
            assertThat(Cronograma.inicialDe(cronograma)).isEqualTo(Dinero.CERO);
        }

        @Test
        @DisplayName("el interes decrece: se calcula sobre el saldo, no sobre el capital total")
        void elInteresDecrece() {
            // 800 a fraccionar en 4 al 1 %: 8,00 / 6,00 / 4,00 / 2,00. Sobre el capital
            // total darian 8,00 las cuatro, y el contribuyente pagaria el doble de
            // financiamiento por el mismo dinero.
            List<CuotaDeConvenio> cronograma =
                    Cronograma.de(
                            Dinero.de("800.00"), condiciones("1", 12, "0"), 4, PRIMERA, REDONDEO);

            assertThat(cronograma.stream().map(CuotaDeConvenio::interes).toList())
                    .containsExactly(
                            Dinero.de("8.00"),
                            Dinero.de("6.00"),
                            Dinero.de("4.00"),
                            Dinero.de("2.00"));
            assertThat(Cronograma.total(cronograma))
                    .as("el total comprometido es el capital mas el financiamiento")
                    .isEqualTo(Dinero.de("820.00"));
        }

        @Test
        @DisplayName("los vencimientos van mes a mes desde el primero")
        void losVencimientosVanMesAMes() {
            List<CuotaDeConvenio> cronograma =
                    Cronograma.de(
                            Dinero.de("300.00"), condiciones("0", 12, "0"), 3, PRIMERA, REDONDEO);

            assertThat(cronograma.stream().map(CuotaDeConvenio::vencimiento).toList())
                    .containsExactly(PRIMERA, PRIMERA.plusMonths(1), PRIMERA.plusMonths(2));
        }

        @Test
        @DisplayName("mas cuotas de las que admite la ordenanza se rechazan")
        void masCuotasDeLasAdmitidasSeRechazan() {
            assertThatThrownBy(
                            () ->
                                    Cronograma.de(
                                            Dinero.de("500.00"),
                                            condiciones("1", 6, "0"),
                                            7,
                                            PRIMERA,
                                            REDONDEO))
                    .isInstanceOf(CondicionesDelConvenio.DemasiadasCuotas.class)
                    .hasMessageContaining("el maximo vigente es 6");
        }

        @Test
        @DisplayName("una inicial del 100 % no deja nada que fraccionar")
        void unaInicialDelCienNoDejaNada() {
            assertThatThrownBy(
                            () ->
                                    Cronograma.de(
                                            Dinero.de("500.00"),
                                            condiciones("1", 12, "100"),
                                            3,
                                            PRIMERA,
                                            REDONDEO))
                    .isInstanceOf(Cronograma.NadaQueFraccionar.class)
                    .hasMessageContaining("es un pago");
        }

        @Test
        @DisplayName("el monto de una cuota es la suma de sus tres partes, siempre")
        void elMontoEsLaSuma() {
            List<CuotaDeConvenio> cronograma =
                    Cronograma.de(
                            Dinero.de("777.77"),
                            condiciones("2.5", 12, "10"),
                            5,
                            PRIMERA,
                            REDONDEO);

            for (CuotaDeConvenio cuota : cronograma) {
                assertThat(cuota.monto())
                        .isEqualTo(cuota.capital().mas(cuota.interes()).mas(cuota.gasto()));
                assertThat(cuota.gasto())
                        .as("el gasto administrativo es de ordenanza local: cero hasta D-02b")
                        .isEqualTo(Dinero.CERO);
            }
        }
    }

    @Nested
    @DisplayName("El numero")
    class DelNumero {

        @Test
        @DisplayName("se imprime como F-2026-000123 y se vuelve a leer igual")
        void seImprimeYSeLee() {
            NumeroDeConvenio numero = new NumeroDeConvenio(new Ejercicio(2026), 123);
            assertThat(numero.impreso()).isEqualTo("F-2026-000123");
            assertThat(NumeroDeConvenio.de("F-2026-000123")).isEqualTo(numero);
            assertThat(NumeroDeConvenio.de(" f-2026-000123 "))
                    .as("se admite como lo teclee quien atiende")
                    .isEqualTo(numero);
        }

        @Test
        @DisplayName("un texto que no tiene esa forma se rechaza, no se adivina")
        void unTextoMalFormadoSeRechaza() {
            assertThatThrownBy(() -> NumeroDeConvenio.de("123"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("F-2026-000123");
            assertThatThrownBy(() -> NumeroDeConvenio.de("X-2026-000123"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new NumeroDeConvenio(new Ejercicio(2026), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("El estado, derivado de los movimientos")
    class DelEstado {

        @Test
        @DisplayName("sin movimientos es un preconvenio: no acogio nada")
        void sinMovimientosEsPreconvenio() {
            assertThat(EstadoDeConvenio.deLosMovimientos(List.of()))
                    .isEqualTo(EstadoDeConvenio.PRECONVENIO);
        }

        @Test
        @DisplayName("con su formalizacion esta vigente")
        void conFormalizacionEstaVigente() {
            assertThat(EstadoDeConvenio.deLosMovimientos(List.of(formalizacion())))
                    .isEqualTo(EstadoDeConvenio.VIGENTE);
        }

        @Test
        @DisplayName("un convenio cerrado lo esta aunque antes se formalizara")
        void elCierreGana() {
            assertThat(
                            EstadoDeConvenio.deLosMovimientos(
                                    List.of(
                                            formalizacion(),
                                            cierre(TipoDeMovimientoDeConvenio.QUIEBRE))))
                    .isEqualTo(EstadoDeConvenio.QUEBRADO);
            assertThat(
                            EstadoDeConvenio.deLosMovimientos(
                                    List.of(
                                            formalizacion(),
                                            cierre(TipoDeMovimientoDeConvenio.ANULACION))))
                    .isEqualTo(EstadoDeConvenio.ANULADO);
            assertThat(
                            EstadoDeConvenio.deLosMovimientos(
                                    List.of(
                                            formalizacion(),
                                            cierre(TipoDeMovimientoDeConvenio.REFORMULACION))))
                    .isEqualTo(EstadoDeConvenio.REFORMULADO);
        }

        @Test
        @DisplayName("cerrado y preconvenio se distinguen: son las dos guardas del cierre")
        void seDistinguenLosDos() {
            assertThat(EstadoDeConvenio.PRECONVENIO.esPreconvenio()).isTrue();
            assertThat(EstadoDeConvenio.PRECONVENIO.estaCerrado()).isFalse();
            assertThat(EstadoDeConvenio.VIGENTE.estaCerrado()).isFalse();
            assertThat(EstadoDeConvenio.QUEBRADO.estaCerrado()).isTrue();
        }

        private MovimientoDeConvenio formalizacion() {
            return MovimientoDeConvenio.formalizacion(
                    1L,
                    PRIMERA,
                    9L,
                    0,
                    Dinero.de("100.00"),
                    2,
                    PRIMERA.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                    kamayuk.rentas.dominio.Observacion.de("prueba"));
        }

        private MovimientoDeConvenio cierre(TipoDeMovimientoDeConvenio tipo) {
            return MovimientoDeConvenio.cierre(
                    1L,
                    tipo,
                    PRIMERA,
                    "INCUMPLIMIENTO",
                    null,
                    null,
                    Dinero.de("100.00"),
                    2,
                    tipo == TipoDeMovimientoDeConvenio.REFORMULACION ? 2L : null,
                    PRIMERA.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                    kamayuk.rentas.dominio.Observacion.de("prueba"));
        }
    }

    @Nested
    @DisplayName("Lo que el movimiento no deja construir")
    class DelMovimiento {

        @Test
        @DisplayName("formalizar sin recibo es imposible: sin cuota inicial no hay convenio")
        void formalizarSinReciboEsImposible() {
            assertThatThrownBy(
                            () ->
                                    new MovimientoDeConvenio(
                                            null,
                                            1L,
                                            TipoDeMovimientoDeConvenio.FORMALIZACION,
                                            PRIMERA,
                                            null,
                                            0,
                                            null,
                                            null,
                                            null,
                                            Dinero.de("100.00"),
                                            2,
                                            null,
                                            PRIMERA.atStartOfDay(java.time.ZoneOffset.UTC)
                                                    .toInstant(),
                                            null,
                                            kamayuk.rentas.dominio.Observacion.de("prueba")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sin cuota inicial pagada en caja no hay convenio");
        }

        @Test
        @DisplayName("cerrar sin motivo tampoco: el acta tiene que decir por que")
        void cerrarSinMotivoEsImposible() {
            assertThatThrownBy(
                            () ->
                                    MovimientoDeConvenio.cierre(
                                            1L,
                                            TipoDeMovimientoDeConvenio.QUIEBRE,
                                            PRIMERA,
                                            "   ",
                                            null,
                                            null,
                                            Dinero.de("100.00"),
                                            2,
                                            null,
                                            PRIMERA.atStartOfDay(java.time.ZoneOffset.UTC)
                                                    .toInstant(),
                                            kamayuk.rentas.dominio.Observacion.de("prueba")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exige su motivo");
        }

        @Test
        @DisplayName("solo la reformulacion nombra un convenio nuevo, y siempre lo nombra")
        void soloLaReformulacionNombraOtro() {
            assertThatThrownBy(
                            () ->
                                    MovimientoDeConvenio.cierre(
                                            1L,
                                            TipoDeMovimientoDeConvenio.QUIEBRE,
                                            PRIMERA,
                                            "INCUMPLIMIENTO",
                                            null,
                                            null,
                                            Dinero.de("100.00"),
                                            2,
                                            5L,
                                            PRIMERA.atStartOfDay(java.time.ZoneOffset.UTC)
                                                    .toInstant(),
                                            kamayuk.rentas.dominio.Observacion.de("prueba")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Solo una reformulacion");

            assertThatThrownBy(
                            () ->
                                    MovimientoDeConvenio.cierre(
                                            1L,
                                            TipoDeMovimientoDeConvenio.REFORMULACION,
                                            PRIMERA,
                                            "REFORMULADO A PEDIDO",
                                            null,
                                            null,
                                            Dinero.de("100.00"),
                                            2,
                                            null,
                                            PRIMERA.atStartOfDay(java.time.ZoneOffset.UTC)
                                                    .toInstant(),
                                            kamayuk.rentas.dominio.Observacion.de("prueba")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("se quedaria sin convenio");
        }
    }
}
