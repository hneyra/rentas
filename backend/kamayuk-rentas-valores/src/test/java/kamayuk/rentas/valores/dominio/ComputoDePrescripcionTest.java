package kamayuk.rentas.valores.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Plazo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #39 — El computo de la prescripcion, <b>sin base y sin reloj</b> (arts. 43 a 46 del TUO del
 * Codigo Tributario).
 *
 * <p>El plazo entra como argumento en todas las pruebas: ninguna escribe "4 anios" como si fuera
 * una propiedad del computo. Esa cifra es normativa (regla 5) y su carga es #192; lo que se
 * verifica aqui es la <b>estructura</b>, que es lo que el issue dice que se puede escribir hoy.
 */
@DisplayName("#39 — Computo de la prescripcion")
class ComputoDePrescripcionTest {

    private static final Plazo CUATRO_ANIOS = Plazo.de("4 ANIOS");
    private static final LocalDate INICIO = LocalDate.of(2016, 1, 1);

    @Nested
    @DisplayName("Sin hechos: el plazo corre de corrido")
    class SinHechos {

        @Test
        @DisplayName("prescribe al cumplirse el plazo, contado desde el inicio")
        void prescribeAlCumplirseElPlazo() {
            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, List.of(), LocalDate.of(2026, 6, 1));

            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2020, 1, 1));
            assertThat(computo.inicioVigente()).isEqualTo(INICIO);
            assertThat(computo.prescrita()).isTrue();
        }

        @Test
        @DisplayName("la vispera todavia no ha prescrito; ese dia, si")
        void elDiaExactoImporta() {
            LocalDate vencimiento = LocalDate.of(2020, 1, 1);
            assertThat(
                            ComputoDePrescripcion.resolver(
                                            INICIO,
                                            CUATRO_ANIOS,
                                            List.of(),
                                            vencimiento.minusDays(1))
                                    .prescrita())
                    .isFalse();
            assertThat(
                            ComputoDePrescripcion.resolver(
                                            INICIO, CUATRO_ANIOS, List.of(), vencimiento)
                                    .prescrita())
                    .isTrue();
        }

        @Test
        @DisplayName("el resultado depende de la fecha que entra, no de hoy (regla 6)")
        void dependeDeLaFechaQueEntra() {
            ComputoDePrescripcion.Computo enFecha =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, List.of(), LocalDate.of(2019, 12, 31));
            ComputoDePrescripcion.Computo despues =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, List.of(), LocalDate.of(2026, 6, 1));

            assertThat(enFecha.fechaDePrescripcion()).isEqualTo(despues.fechaDePrescripcion());
            assertThat(enFecha.prescrita()).isFalse();
            assertThat(despues.prescrita()).isTrue();
        }
    }

    @Nested
    @DisplayName("Interrupcion (art. 45): el plazo vuelve a empezar")
    class ConInterrupcion {

        @Test
        @DisplayName("se cuenta de nuevo desde el dia SIGUIENTE al acto")
        void elPlazoVuelveAEmpezarElDiaSiguiente() {
            HechoDelComputo pagoParcial =
                    HechoDelComputo.interrupcion(
                            "pago parcial de la deuda", LocalDate.of(2018, 7, 10));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, List.of(pagoParcial), LocalDate.of(2021, 1, 1));

            assertThat(computo.inicioVigente()).isEqualTo(LocalDate.of(2018, 7, 11));
            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2022, 7, 11));
            // Sin la interrupcion habria prescrito el 2020-01-01; con ella, todavia no.
            assertThat(computo.prescrita()).isFalse();
        }

        @Test
        @DisplayName("dos interrupciones: manda la ultima, y se aplican en orden cronologico")
        void mandaLaUltima() {
            // Deliberadamente desordenadas al entrar: el computo las ordena.
            List<HechoDelComputo> hechos =
                    List.of(
                            HechoDelComputo.interrupcion(
                                    "notificacion de REC", LocalDate.of(2019, 3, 1)),
                            HechoDelComputo.interrupcion(
                                    "reconocimiento de deuda", LocalDate.of(2017, 5, 20)));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, hechos, LocalDate.of(2020, 1, 1));

            assertThat(computo.inicioVigente()).isEqualTo(LocalDate.of(2019, 3, 2));
            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2023, 3, 2));
            assertThat(computo.hechosAplicados()).hasSize(2);
        }

        @Test
        @DisplayName("un acto posterior a la prescripcion no la deshace")
        void unActoPosteriorNoLaDeshace() {
            // El plazo vencio el 2020-01-01; el acto es de 2021.
            HechoDelComputo tardio =
                    HechoDelComputo.interrupcion("pago parcial", LocalDate.of(2021, 4, 4));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, List.of(tardio), LocalDate.of(2022, 1, 1));

            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2020, 1, 1));
            assertThat(computo.prescrita()).isTrue();
            assertThat(computo.hechosAplicados()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Suspension (art. 46): el plazo se detiene")
    class ConSuspension {

        @Test
        @DisplayName("el vencimiento se corre tantos dias como duro el intervalo")
        void elVencimientoSeCorre() {
            HechoDelComputo reclamacion =
                    HechoDelComputo.suspension(
                            "tramitacion del procedimiento contencioso tributario",
                            LocalDate.of(2017, 1, 1),
                            LocalDate.of(2017, 7, 1));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, List.of(reclamacion), LocalDate.of(2020, 1, 2));

            // 181 dias entre el 1 de enero y el 1 de julio de 2017.
            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2020, 6, 30));
            // El inicio NO se mueve: una suspension detiene, no reinicia.
            assertThat(computo.inicioVigente()).isEqualTo(INICIO);
            assertThat(computo.prescrita()).isFalse();
        }

        @Test
        @DisplayName("una suspension anterior a una interrupcion deja de contar")
        void laInterrupcionBorraLaSuspensionAnterior() {
            // La interrupcion reinicia el plazo: lo que la suspension anterior prorrogaba ya no
            // existe. Tratarlas por acumulacion daria una fecha mas tardia, y en contra del deudor.
            List<HechoDelComputo> hechos =
                    List.of(
                            HechoDelComputo.suspension(
                                    "proceso judicial",
                                    LocalDate.of(2016, 3, 1),
                                    LocalDate.of(2016, 9, 1)),
                            HechoDelComputo.interrupcion(
                                    "pago parcial", LocalDate.of(2017, 2, 10)));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, hechos, LocalDate.of(2022, 1, 1));

            assertThat(computo.inicioVigente()).isEqualTo(LocalDate.of(2017, 2, 11));
            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2021, 2, 11));
        }
    }

    /**
     * #334 — Un hecho solo actua sobre el tramo en que el plazo <b>corre</b>.
     *
     * <p>Todas las muestras de encima tienen sus hechos despues de {@link #INICIO}, y con esa
     * siembra cualquier implementacion pasa: la que recorta y la que no. La que distingue es un
     * hecho <b>anterior</b> al dia 1 del computo, que es lo que ocurre en cuanto un hecho de un
     * ejercicio temprano llega al computo de uno tardio, o cuando se alega el pago de una cuota del
     * propio ejercicio, que se hace antes de que su plazo empiece a correr.
     */
    @Nested
    @DisplayName("#334 — Hechos anteriores al inicio del computo: no hay plazo que tocar")
    class AntesDelInicio {

        private final LocalDate resolucion = LocalDate.of(2019, 6, 1);
        private final ComputoDePrescripcion.Computo sinHechos =
                ComputoDePrescripcion.resolver(INICIO, CUATRO_ANIOS, List.of(), resolucion);

        @Test
        @DisplayName("una interrupcion anterior al inicio deja el computo igual que sin hechos")
        void laInterrupcionAnteriorNoHaceNada() {
            HechoDelComputo pagoDeLaCuota =
                    HechoDelComputo.interrupcion(
                            "pago de la cuota de mayo", LocalDate.of(2015, 5, 29));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, List.of(pagoDeLaCuota), resolucion);

            // Sin el recorte, el inicio vigente quedaria en el 2015-05-30 -antes del inicio- y
            // ComputoDeEjercicio lanzaria «adelanta el inicio del computo, nunca lo atrasa».
            assertThat(computo.inicioVigente()).isEqualTo(sinHechos.inicioVigente());
            assertThat(computo.fechaDePrescripcion()).isEqualTo(sinHechos.fechaDePrescripcion());
            assertThat(computo.prescrita()).isEqualTo(sinHechos.prescrita());
            assertThat(computo.hechosAplicados()).isEmpty();
        }

        @Test
        @DisplayName("una suspension terminada antes del inicio no corre el vencimiento")
        void laSuspensionAnteriorNoSumaNada() {
            HechoDelComputo reclamacion =
                    HechoDelComputo.suspension(
                            "reclamacion contra la determinacion",
                            LocalDate.of(2015, 3, 1),
                            LocalDate.of(2015, 12, 15));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, List.of(reclamacion), resolucion);

            assertThat(computo.inicioVigente()).isEqualTo(sinHechos.inicioVigente());
            assertThat(computo.fechaDePrescripcion()).isEqualTo(sinHechos.fechaDePrescripcion());
            assertThat(computo.hechosAplicados()).isEmpty();
        }

        @Test
        @DisplayName("de una suspension que cruza el inicio solo cuentan los dias desde el inicio")
        void deLaQueCruzaSoloCuentaLoQueCaeDentro() {
            // Del 2015-07-01 al 2016-07-01: 366 dias en bruto, pero el plazo solo corria desde el
            // 2016-01-01, asi que se detiene 182 —con la misma cuenta que `elVencimientoSeCorre`,
            // que es la de hoy: el ultimo dia es de #335—.
            HechoDelComputo reclamacion =
                    HechoDelComputo.suspension(
                            "reclamacion contra la determinacion",
                            LocalDate.of(2015, 7, 1),
                            LocalDate.of(2016, 7, 1));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, List.of(reclamacion), resolucion);

            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2020, 7, 1));
            assertThat(computo.inicioVigente()).isEqualTo(INICIO);
            assertThat(computo.hechosAplicados()).containsExactly(reclamacion);
        }
    }

    @Nested
    @DisplayName("Resultado de la solicitud")
    class DelResultado {

        @Test
        @DisplayName("procede en parte cuando unos ejercicios prescriben y otros no")
        void procedeEnParte() {
            assertThat(ResultadoDeLaSolicitud.de(0, 3))
                    .isEqualTo(ResultadoDeLaSolicitud.NO_PROCEDE);
            assertThat(ResultadoDeLaSolicitud.de(2, 3))
                    .isEqualTo(ResultadoDeLaSolicitud.PROCEDE_EN_PARTE);
            assertThat(ResultadoDeLaSolicitud.de(3, 3)).isEqualTo(ResultadoDeLaSolicitud.PROCEDE);
        }
    }
}
