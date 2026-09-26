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

    /** El dia de la firma: un mes antes de la cuota 1, para que las dos fechas no coincidan. */
    private static final LocalDate FIRMA = LocalDate.of(2026, 3, 15);

    private static final PlazosDelConvenio PLAZOS = new PlazosDelConvenio(FIRMA, PRIMERA);

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
                            Dinero.de("100.00"), condiciones("0", 12, "0"), 3, PLAZOS, REDONDEO);

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
                            Dinero.de("1000.00"), condiciones("1", 12, "20"), 4, PLAZOS, REDONDEO);

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

        /**
         * #459 — La inicial vence el dia del convenio, y la cuota 1 el dia pactado.
         *
         * <p>La siembra lleva la firma y la cuota 1 en dias distintos: con los dos iguales —lo que
         * el borde fabricaba cuando faltaba el campo— la implementacion buena y la que le ponia a
         * la inicial el vencimiento de la cuota 1 dan la misma fecha.
         */
        @Test
        @DisplayName("#459 — la inicial vence el dia de la firma, y la cuota 1 el dia pactado")
        void laInicialVenceElDiaDelConvenio() {
            List<CuotaDeConvenio> cronograma =
                    Cronograma.de(
                            Dinero.de("1000.00"), condiciones("1", 12, "20"), 4, PLAZOS, REDONDEO);

            assertThat(cronograma.stream().map(CuotaDeConvenio::vencimiento).limit(2).toList())
                    .as("la inicial se paga en el acto; la cuota 1, cuando se pacto")
                    .containsExactly(FIRMA, PRIMERA);
        }

        @Test
        @DisplayName("sin cuota inicial no hay cuota 0")
        void sinInicialNoHayCuotaCero() {
            List<CuotaDeConvenio> cronograma =
                    Cronograma.de(
                            Dinero.de("600.00"), condiciones("0", 12, "0"), 6, PLAZOS, REDONDEO);

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
                            Dinero.de("800.00"), condiciones("1", 12, "0"), 4, PLAZOS, REDONDEO);

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
                            Dinero.de("300.00"), condiciones("0", 12, "0"), 3, PLAZOS, REDONDEO);

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
                                            PLAZOS,
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
                                            PLAZOS,
                                            REDONDEO))
                    .isInstanceOf(Cronograma.NadaQueFraccionar.class)
                    .hasMessageContaining("es un pago");
        }

        @Test
        @DisplayName("el monto de una cuota es la suma de sus tres partes, siempre")
        void elMontoEsLaSuma() {
            List<CuotaDeConvenio> cronograma =
                    Cronograma.de(
                            Dinero.de("777.77"), condiciones("2.5", 12, "10"), 5, PLAZOS, REDONDEO);

            for (CuotaDeConvenio cuota : cronograma) {
                assertThat(cuota.monto())
                        .isEqualTo(cuota.capital().mas(cuota.interes()).mas(cuota.gasto()));
                assertThat(cuota.gasto())
                        .as("el gasto administrativo es de ordenanza local: cero hasta D-02b")
                        .isEqualTo(Dinero.CERO);
            }
        }
    }

    /**
     * #382 — El capital de cada cuota es el cociente <b>exacto</b> redondeado una vez con la
     * politica, no el producto por un 1/N truncado a 16 digitos.
     *
     * <p>Las muestras de {@link DelCronograma} —divisores 3, 4, 5 y 6, ningun cociente en medio
     * centimo y siempre {@code HALF_UP}— pasan igual con las dos implementaciones, y por eso el
     * defecto sobrevivio. Estas tres son las que distinguen: un cociente que cae <i>justo</i> en el
     * medio centimo (1 234,50 / 12 = 102,875) y dos repartos exactos con un modo dirigido, que el
     * error del reciproco —por debajo en 1/3, por encima en 1/6— manda al lado equivocado.
     */
    @Nested
    @DisplayName("#382 — El capital por cuota es el cociente exacto, redondeado una vez")
    class ElCocienteExacto {

        @Test
        @DisplayName("1 234,50 en 12 con HALF_UP: 102,875 sube a 102,88, no baja a 102,87")
        void elMedioCentimoSubeConHalfUp() {
            // 1/12 a 16 digitos es 0.08333333333333333, por debajo: el producto queda en
            // 102.8749999999999958850 y HALF_UP lo baja. El cociente exacto es 102.875.
            // El interes, de prueba (1,5 % mensual), se devenga sobre el saldo, asi que el
            // centimo desplazado a la ultima cuota tambien movia el interes total.
            List<CuotaDeConvenio> cronograma =
                    Cronograma.de(
                            Dinero.de("1234.50"),
                            condiciones("1.5", 12, "0"),
                            12,
                            PLAZOS,
                            REDONDEO);

            assertThat(cronograma.subList(0, 11))
                    .as("las once primeras cuotas llevan el cociente redondeado con la politica")
                    .allSatisfy(
                            cuota -> assertThat(cuota.capital()).isEqualTo(Dinero.de("102.88")));
            assertThat(cronograma.get(11).capital())
                    .as("la ultima absorbe el descuadre del cociente exacto")
                    .isEqualTo(Dinero.de("102.82"));
            Dinero interes = Dinero.CERO;
            for (CuotaDeConvenio cuota : cronograma) {
                interes = interes.mas(cuota.interes());
            }
            assertThat(interes).isEqualTo(Dinero.de("120.36"));
            assertThat(Cronograma.total(cronograma)).isEqualTo(Dinero.de("1354.86"));
        }

        @Test
        @DisplayName("300,00 en 3 con DOWN: 100,00 tres veces, no 99,99")
        void unRepartoExactoConDownNoPierdeElCentimo() {
            // 1/3 a 16 digitos queda por debajo: 300 x 0.3333333333333333 = 99.99999999999999,
            // y DOWN lo deja en 99,99 aunque el reparto sea exacto.
            List<CuotaDeConvenio> cronograma =
                    Cronograma.de(
                            Dinero.de("300.00"),
                            condiciones("0", 12, "0"),
                            3,
                            PLAZOS,
                            new PoliticaDeRedondeo(2, RoundingMode.DOWN));

            assertThat(cronograma.stream().map(CuotaDeConvenio::capital).toList())
                    .containsExactly(Dinero.de("100.00"), Dinero.de("100.00"), Dinero.de("100.00"));
        }

        @Test
        @DisplayName("600,00 en 6 con UP: 100,00 seis veces, no 100,01")
        void unRepartoExactoConUpNoAnadeElCentimo() {
            // 1/6 a 16 digitos queda por encima: 600 x 0.1666666666666667 = 100.00000000000002,
            // y UP lo sube a 100,01 aunque el reparto sea exacto.
            List<CuotaDeConvenio> cronograma =
                    Cronograma.de(
                            Dinero.de("600.00"),
                            condiciones("0", 12, "0"),
                            6,
                            PLAZOS,
                            new PoliticaDeRedondeo(2, RoundingMode.UP));

            assertThat(cronograma)
                    .allSatisfy(
                            cuota -> assertThat(cuota.capital()).isEqualTo(Dinero.de("100.00")));
        }
    }

    @Nested
    @DisplayName("El vencimiento de una cuota (#411)")
    class DelVencimiento {

        private final CuotaDeConvenio cuota =
                new CuotaDeConvenio(1, PRIMERA, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO);

        @Test
        @DisplayName("el dia en que vence todavia se puede pagar: vencida es al dia siguiente")
        void elDiaEnQueVenceTodaviaNoEstaVencida() {
            assertThat(cuota.vencidaA(PRIMERA.minusDays(1))).isFalse();
            assertThat(cuota.vencidaA(PRIMERA))
                    .as("el criterio de Exigibilidad: el dia en que vence, tampoco")
                    .isFalse();
            assertThat(cuota.vencidaA(PRIMERA.plusDays(1))).isTrue();
        }

        @Test
        @DisplayName("la copia SQL es la misma comparacion estricta")
        void laCopiaSqlEsEstricta() {
            assertThat(CuotaDeConvenio.vencidaEnSql("q", "hoy"))
                    .isEqualTo("(q.vencimiento < :hoy)");
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
