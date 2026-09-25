package kamayuk.rentas.cuentacorriente.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CristalizacionDelDevengo}: lo que hay que cargar antes de escribir en una cuota (#365).
 *
 * <p>Sin Spring, sin base y sin reloj (regla 6). La prueba de que los seis caminos que escriben la
 * llaman —y de que sin ella pierden el devengo— es {@code
 * ElDevengoSeCristalizaAntesDeEscribirJdbcTest}; aqui se prueba la cuenta.
 */
@DisplayName("#365 — CristalizacionDelDevengo")
class CristalizacionDelDevengoTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final PoliticaDeRedondeo REDONDEO =
            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

    private static final LocalDate CARGO = LocalDate.of(2026, 1, 10);
    private static final LocalDate ACTO = LocalDate.of(2026, 6, 10);

    /** 1,00 de interes y 0,50 de reajuste por dia: no es la TIM, es lo que hace visible el dia. */
    private static final CristalizacionDelDevengo CRISTALIZACION =
            new CristalizacionDelDevengo(
                    new CalculoDeDeuda(new PorDia(Dinero.de("0.50"), Dinero.de("1.00"))));

    @Test
    @DisplayName("el reajuste y el interes devengados y no asentados, uno por parte y en orden")
    void cargaLoDevengadoParteAParte() {
        List<Asiento> libro =
                List.of(asiento(Concepto.INSOLUTO, TipoAsiento.CARGO, "400.00", CARGO));

        assertThat(CRISTALIZACION.sinAsentar(libro, ACTO, REDONDEO))
                .as("151 dias del 01-10 al 06-10: 75,50 de reajuste y 151,00 de interes")
                .containsExactly(
                        new CristalizacionDelDevengo.Devengo(Concepto.REAJUSTE, Dinero.de("75.50")),
                        new CristalizacionDelDevengo.Devengo(
                                Concepto.INTERES, Dinero.de("151.00")));
    }

    @Test
    @DisplayName("lo que el libro ya tiene asentado no se vuelve a cargar")
    void loYaAsentadoNoSeVuelveACargar() {
        List<Asiento> libro =
                List.of(
                        asiento(Concepto.INSOLUTO, TipoAsiento.CARGO, "400.00", CARGO),
                        asiento(Concepto.INTERES, TipoAsiento.CARGO, "50.00", CARGO.plusDays(50)),
                        asiento(Concepto.REAJUSTE, TipoAsiento.CARGO, "25.00", CARGO.plusDays(50)));

        // El cargo del dia 50 ya cristalizo los primeros 50 dias y es el ancla: faltan 101.
        assertThat(CRISTALIZACION.sinAsentar(libro, ACTO, REDONDEO))
                .containsExactly(
                        new CristalizacionDelDevengo.Devengo(Concepto.REAJUSTE, Dinero.de("50.50")),
                        new CristalizacionDelDevengo.Devengo(
                                Concepto.INTERES, Dinero.de("101.00")));
    }

    @Test
    @DisplayName("el mismo dia del ultimo asiento no hay nada que cristalizar")
    void elMismoDiaNoHayNada() {
        List<Asiento> libro =
                List.of(asiento(Concepto.INSOLUTO, TipoAsiento.CARGO, "400.00", ACTO));

        assertThat(CRISTALIZACION.sinAsentar(libro, ACTO, REDONDEO)).isEmpty();
    }

    @Test
    @DisplayName("sin insoluto pendiente no se devenga, aunque haya pasado el tiempo")
    void sinInsolutoNoSeDevenga() {
        List<Asiento> libro =
                List.of(
                        asiento(Concepto.INSOLUTO, TipoAsiento.CARGO, "400.00", CARGO),
                        asiento(Concepto.INSOLUTO, TipoAsiento.ABONO, "400.00", CARGO));

        assertThat(CRISTALIZACION.sinAsentar(libro, ACTO, REDONDEO)).isEmpty();
    }

    @Test
    @DisplayName("con una mora que no acumula —la de src/main hoy— nunca hay cargo")
    void sinAcumulacionNoHayCargo() {
        CristalizacionDelDevengo sinMora =
                new CristalizacionDelDevengo(
                        new CalculoDeDeuda(new PorDia(Dinero.CERO, Dinero.CERO)));
        List<Asiento> libro =
                List.of(asiento(Concepto.INSOLUTO, TipoAsiento.CARGO, "400.00", CARGO));

        assertThat(sinMora.sinAsentar(libro, ACTO, REDONDEO))
                .as("por eso los cuatro caminos que no cristalizaban pasaban en verde")
                .isEmpty();
    }

    @Test
    @DisplayName("cargar lo que devuelve deja lo asentado igual a lo que se debe ese dia")
    void loAsentadoMasElCargoEsLoQueSeDebe() {
        CalculoDeDeuda calculo =
                new CalculoDeDeuda(new PorDia(Dinero.de("0.50"), Dinero.de("1.00")));
        List<Asiento> libro =
                new java.util.ArrayList<>(
                        List.of(asiento(Concepto.INSOLUTO, TipoAsiento.CARGO, "400.00", CARGO)));
        DeudaActualizada seDebe = calculo.deudaActualizadaA(libro, ACTO, REDONDEO);

        for (CristalizacionDelDevengo.Devengo devengo :
                new CristalizacionDelDevengo(calculo).sinAsentar(libro, ACTO, REDONDEO)) {
            libro.add(
                    asiento(
                            devengo.parte(),
                            TipoAsiento.CARGO,
                            devengo.monto().valor().toPlainString(),
                            ACTO));
        }

        assertThat(calculo.asentadoA(libro, ACTO))
                .as("es la invariante: lo que el libro tiene ya es lo que se debe")
                .isEqualTo(seDebe);
        assertThat(calculo.deudaActualizadaA(libro, ACTO.plusDays(1), REDONDEO).total())
                .as("y al dia siguiente, un dia mas: 1,50")
                .isEqualTo(seDebe.total().mas(Dinero.de("1.50")));
    }

    @Test
    @DisplayName("un devengo de cero o negativo no es un cargo")
    void unDevengoNoPositivoNoExiste() {
        assertThatThrownBy(
                        () -> new CristalizacionDelDevengo.Devengo(Concepto.INTERES, Dinero.CERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------

    private static Asiento asiento(
            Concepto concepto, TipoAsiento tipo, String monto, LocalDate fechaValor) {
        return Asiento.nuevo(
                EJERCICIO,
                1L,
                "PREDIAL",
                concepto,
                tipo,
                Fase.ORDINARIA,
                null,
                null,
                null,
                null,
                Dinero.de(monto),
                fechaValor,
                "EM-2026-0365");
    }

    /** Un importe fijo por dia de reajuste y otro de interes, sobre cualquier insoluto. */
    private record PorDia(Dinero reajuste, Dinero interes) implements PoliticaDeMora {

        @Override
        public Dinero reajusteAcumulado(
                Dinero insolutoPendiente,
                LocalDate desde,
                LocalDate hasta,
                PoliticaDeRedondeo redondeo) {
            return porDias(reajuste, desde, hasta, redondeo);
        }

        @Override
        public Dinero interesAcumulado(
                Dinero insolutoPendiente,
                LocalDate desde,
                LocalDate hasta,
                PoliticaDeRedondeo redondeo) {
            return porDias(interes, desde, hasta, redondeo);
        }

        private static Dinero porDias(
                Dinero porDia, LocalDate desde, LocalDate hasta, PoliticaDeRedondeo redondeo) {
            return porDia.por(BigDecimal.valueOf(ChronoUnit.DAYS.between(desde, hasta)))
                    .redondeadoCon(redondeo);
        }
    }
}
