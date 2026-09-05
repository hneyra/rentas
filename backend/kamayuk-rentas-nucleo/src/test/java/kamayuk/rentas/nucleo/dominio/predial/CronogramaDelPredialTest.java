package kamayuk.rentas.nucleo.dominio.predial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El reparto del impuesto en cuotas (#395, TUO LTM art. 15). Funcion pura: sin base y sin reloj.
 *
 * <p>Lo que estas pruebas defienden es una sola cosa y es la que se ve en ventanilla: <b>las cuotas
 * suman exactamente el impuesto</b>. Un reparto que pierda un centimo produce un cronograma cuya
 * suma no es la deuda, y la diferencia aparece el dia que alguien paga las cuatro.
 */
@DisplayName("#395 — Cronograma del predial (art. 15)")
class CronogramaDelPredialTest {

    private static final List<LocalDate> CUATRO_TRIMESTRES =
            List.of(
                    LocalDate.parse("2026-02-27"),
                    LocalDate.parse("2026-05-29"),
                    LocalDate.parse("2026-08-31"),
                    LocalDate.parse("2026-11-30"));

    private static final PoliticasDeRedondeo REDONDEO =
            PoliticasDeRedondeo.construir()
                    .en(PuntoDeRedondeo.CUOTA, new PoliticaDeRedondeo(2, RoundingMode.HALF_UP))
                    .construir();

    @Test
    @DisplayName("cuatro cuotas iguales cuando el impuesto se divide exacto")
    void repartoExacto() {
        List<CuotaDelPredial> cuotas =
                CronogramaDelPredial.repartir(Dinero.de("400.00"), CUATRO_TRIMESTRES, REDONDEO);

        assertThat(cuotas).hasSize(4);
        assertThat(cuotas)
                .allSatisfy(cuota -> assertThat(cuota.importe()).isEqualTo(Dinero.de("100.00")));
        assertThat(cuotas.get(0).numero()).isEqualTo(1);
        assertThat(cuotas.get(0).vencimiento()).isEqualTo(LocalDate.parse("2026-02-27"));
        assertThat(cuotas.get(3).vencimiento()).isEqualTo(LocalDate.parse("2026-11-30"));
    }

    @Test
    @DisplayName(
            "la ultima cuota se lleva el centimo que no cabe, y la suma sigue siendo el impuesto")
    void elCentimoQueNoCabe() {
        Dinero impuesto = Dinero.de("587.45");

        List<CuotaDelPredial> cuotas =
                CronogramaDelPredial.repartir(impuesto, CUATRO_TRIMESTRES, REDONDEO);

        // 587.45 / 4 = 146.8625 -> 146.86 con la politica del conjunto. Tres cuotas asi dejan
        // 146.87 para la ultima: es el centimo huerfano, y esta en el cronograma, no fuera.
        assertThat(cuotas.get(0).importe()).isEqualTo(Dinero.de("146.86"));
        assertThat(cuotas.get(1).importe()).isEqualTo(Dinero.de("146.86"));
        assertThat(cuotas.get(2).importe()).isEqualTo(Dinero.de("146.86"));
        assertThat(cuotas.get(3).importe()).isEqualTo(Dinero.de("146.87"));
        assertThat(sumaDe(cuotas)).isEqualTo(impuesto);
    }

    @Test
    @DisplayName("una sola cuota —pago al contado— es el impuesto entero")
    void alContado() {
        List<CuotaDelPredial> cuotas =
                CronogramaDelPredial.repartir(
                        Dinero.de("587.45"), List.of(LocalDate.parse("2026-02-27")), REDONDEO);

        assertThat(cuotas).hasSize(1);
        assertThat(cuotas.get(0).importe()).isEqualTo(Dinero.de("587.45"));
    }

    @Test
    @DisplayName("cuantas cuotas hay lo dice el cronograma, no el codigo")
    void elNumeroDeCuotasEsDato() {
        List<LocalDate> tres =
                List.of(
                        LocalDate.parse("2026-03-31"),
                        LocalDate.parse("2026-07-31"),
                        LocalDate.parse("2026-11-30"));

        List<CuotaDelPredial> cuotas =
                CronogramaDelPredial.repartir(Dinero.de("300.00"), tres, REDONDEO);

        assertThat(cuotas).hasSize(3);
        assertThat(sumaDe(cuotas)).isEqualTo(Dinero.de("300.00"));
    }

    @Test
    @DisplayName("la politica de redondeo del conjunto decide la cuota, y cambiarla la cambia")
    void laPoliticaMandaSobreLaCuota() {
        PoliticasDeRedondeo aLaUnidad =
                PoliticasDeRedondeo.construir()
                        .en(PuntoDeRedondeo.CUOTA, new PoliticaDeRedondeo(0, RoundingMode.DOWN))
                        .construir();

        List<CuotaDelPredial> cuotas =
                CronogramaDelPredial.repartir(Dinero.de("587.45"), CUATRO_TRIMESTRES, aLaUnidad);

        assertThat(cuotas.get(0).importe()).isEqualTo(Dinero.de("146.00"));
        assertThat(cuotas.get(3).importe()).isEqualTo(Dinero.de("149.45"));
        assertThat(sumaDe(cuotas)).isEqualTo(Dinero.de("587.45"));
    }

    @Test
    @DisplayName("sin la politica de CUOTA no se reparte: no se redondea a lo que salga")
    void sinPoliticaNoHayCronograma() {
        // El conjunto trae politicas —parametrizarlo vacio ni siquiera se puede construir— pero no
        // la del punto CUOTA. Repartir «como salga» daria un cronograma sin ningun error de por
        // medio, que es el modo de falla silencioso que D-03c evita.
        PoliticasDeRedondeo sinLaDeLaCuota =
                PoliticasDeRedondeo.construir()
                        .en(
                                PuntoDeRedondeo.IMPUESTO_ANUAL,
                                new PoliticaDeRedondeo(2, RoundingMode.HALF_UP))
                        .construir();

        assertThatThrownBy(
                        () ->
                                CronogramaDelPredial.repartir(
                                        Dinero.de("587.45"), CUATRO_TRIMESTRES, sinLaDeLaCuota))
                .isInstanceOf(PoliticasDeRedondeo.PuntoSinPolitica.class);
    }

    @Test
    @DisplayName("un cronograma sin ninguna fecha no es un cronograma")
    void sinFechas() {
        assertThatThrownBy(
                        () ->
                                CronogramaDelPredial.repartir(
                                        Dinero.de("100.00"), List.of(), REDONDEO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cronograma");
    }

    private static Dinero sumaDe(List<CuotaDelPredial> cuotas) {
        Dinero total = Dinero.CERO;
        for (CuotaDelPredial cuota : cuotas) {
            total = total.mas(cuota.importe());
        }
        return total;
    }
}
