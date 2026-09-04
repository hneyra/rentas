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
