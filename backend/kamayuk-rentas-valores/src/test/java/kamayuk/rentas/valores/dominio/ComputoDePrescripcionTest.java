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
        @DisplayName(
                "#335 — el vencimiento se corre tantos dias como duro el intervalo, contado el"
                        + " ultimo")
        void elVencimientoSeCorre() {
            HechoDelComputo reclamacion =
                    HechoDelComputo.suspension(
                            "tramitacion del procedimiento contencioso tributario",
                            LocalDate.of(2017, 1, 1),
                            LocalDate.of(2017, 7, 1));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, List.of(reclamacion), LocalDate.of(2020, 6, 30));

            // Del 1 de enero al 1 de julio de 2017, los dos incluidos: 182 dias. `hasta` es «el
            // ultimo dia del intervalo suspendido» (HechoDelComputo), asi que ese dia tambien
            // estuvo suspendido. Antes de #335 se contaban 181 y vencia el 2020-06-30.
            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2020, 7, 1));
            // El inicio NO se mueve: una suspension detiene, no reinicia.
            assertThat(computo.inicioVigente()).isEqualTo(INICIO);
            // La solicitud del 2020-06-30 llega un dia antes: no procede.
            assertThat(computo.prescrita()).isFalse();
        }

        @Test
        @DisplayName("#335 — una suspension de un solo dia corre el plazo un dia")
        void unaSuspensionDeUnDiaCorreUnDia() {
            // desde == hasta es legitimo: HechoDelComputo solo rechaza hasta < desde, igual que
            // prescripcion_hecho_fechas_ck. Con la cuenta exclusiva sumaba cero.
            HechoDelComputo unDia =
                    HechoDelComputo.suspension(
                            "lapso de no habido",
                            LocalDate.of(2018, 5, 10),
                            LocalDate.of(2018, 5, 10));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, List.of(unDia), LocalDate.of(2020, 1, 1));

            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2020, 1, 2));
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
            // Del 2015-07-01 al 2016-07-01, pero el plazo solo corria desde el 2016-01-01: del
            // 2016-01-01 al 2016-07-01, los dos incluidos, son 183 dias (2016 es bisiesto), con la
            // misma cuenta que `elVencimientoSeCorre` (#335).
            HechoDelComputo reclamacion =
                    HechoDelComputo.suspension(
                            "reclamacion contra la determinacion",
                            LocalDate.of(2015, 7, 1),
                            LocalDate.of(2016, 7, 1));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, List.of(reclamacion), resolucion);

            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2020, 7, 2));
            assertThat(computo.inicioVigente()).isEqualTo(INICIO);
            assertThat(computo.hechosAplicados()).containsExactly(reclamacion);
        }
    }

    /**
     * #335 — Dos suspensiones a la vez no detienen el plazo dos veces.
     *
     * <p>Un dia esta suspendido o no lo esta: si una reclamacion en tramite y un fraccionamiento
     * vigente coinciden —las causales a) y d) del art. 46 para exigir el pago—, los dias que se
     * solapan se descuentan una vez. Las muestras de encima tienen <b>una</b> suspension, y con esa
     * siembra pasan la implementacion que une y la que suma en bruto; la que distingue es el
     * solape. Y el par disjunto es la guarda del otro lado: la union no se come los intervalos que
     * no se tocan.
     *
     * <p>Los solapes no se rechazan: dos causales simultaneas son hechos legitimos, y rechazarlas
     * obligaria a quien registra a inventarse intervalos.
     */
    @Nested
    @DisplayName("#335 — Suspensiones que se solapan: cada dia suspendido cuenta una vez")
    class SuspensionesSolapadas {

        @Test
        @DisplayName("una reclamacion y un fraccionamiento que se solapan corren la union")
        void elSolapeCuentaUnaVez() {
            List<HechoDelComputo> hechos =
                    List.of(
                            HechoDelComputo.suspension(
                                    "tramitacion del procedimiento contencioso tributario",
                                    LocalDate.of(2017, 1, 1),
                                    LocalDate.of(2017, 12, 31)),
                            HechoDelComputo.suspension(
                                    "vigencia del fraccionamiento de la deuda",
                                    LocalDate.of(2017, 6, 1),
                                    LocalDate.of(2018, 6, 1)));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, hechos, LocalDate.of(2021, 9, 1));

            // La union va del 2017-01-01 al 2018-06-01: 517 dias contando el ultimo. Sumando en
            // bruto eran 365 + 366 y vencia el 2022-01-01 —el 2021-12-30 con la cuenta exclusiva—,
            // y la solicitud del 2021-09-01 salia NO_PROCEDE.
            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2021, 6, 1));
            assertThat(computo.prescrita()).isTrue();
            // Las dos entraron al computo: cada una es una causal que la resolucion sustenta.
            assertThat(computo.hechosAplicados()).hasSize(2);
        }

        @Test
        @DisplayName("una suspension contenida en otra no suma nada")
        void laContenidaNoSuma() {
            HechoDelComputo reclamacion =
                    HechoDelComputo.suspension(
                            "tramitacion del procedimiento contencioso tributario",
                            LocalDate.of(2017, 1, 1),
                            LocalDate.of(2017, 7, 1));
            HechoDelComputo dentro =
                    HechoDelComputo.suspension(
                            "vigencia del fraccionamiento de la deuda",
                            LocalDate.of(2017, 3, 1),
                            LocalDate.of(2017, 4, 30));

            ComputoDePrescripcion.Computo sola =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, List.of(reclamacion), LocalDate.of(2020, 1, 1));
            ComputoDePrescripcion.Computo juntas =
                    ComputoDePrescripcion.resolver(
                            INICIO,
                            CUATRO_ANIOS,
                            List.of(dentro, reclamacion),
                            LocalDate.of(2020, 1, 1));

            assertThat(juntas.fechaDePrescripcion())
                    .isEqualTo(sola.fechaDePrescripcion())
                    .isEqualTo(LocalDate.of(2020, 7, 1));
        }

        @Test
        @DisplayName("un par disjunto suma lo mismo con la union que sin ella")
        void elParDisjuntoSumaLoMismo() {
            HechoDelComputo primera =
                    HechoDelComputo.suspension(
                            "tramitacion del procedimiento contencioso tributario",
                            LocalDate.of(2017, 1, 1),
                            LocalDate.of(2017, 3, 31));
            HechoDelComputo segunda =
                    HechoDelComputo.suspension(
                            "vigencia del fraccionamiento de la deuda",
                            LocalDate.of(2017, 6, 1),
                            LocalDate.of(2017, 6, 30));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO,
                            CUATRO_ANIOS,
                            List.of(primera, segunda),
                            LocalDate.of(2020, 1, 1));

            // 90 + 30 dias, los dos intervalos enteros: del 2020-01-01, 120 dias despues.
            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2020, 4, 30));
            assertThat(computo.hechosAplicados()).containsExactly(primera, segunda);
        }

        @Test
        @DisplayName("dos suspensiones que se tocan cuentan cada dia una vez, sin hueco")
        void lasQueSeTocanNoDejanHueco() {
            List<HechoDelComputo> hechos =
                    List.of(
                            HechoDelComputo.suspension(
                                    "tramitacion del procedimiento contencioso tributario",
                                    LocalDate.of(2017, 1, 1),
                                    LocalDate.of(2017, 1, 31)),
                            HechoDelComputo.suspension(
                                    "tramitacion de la demanda contencioso-administrativa",
                                    LocalDate.of(2017, 2, 1),
                                    LocalDate.of(2017, 2, 28)));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, hechos, LocalDate.of(2020, 1, 1));

            // 31 + 28 = 59 dias: del 2020-01-01 (2020 es bisiesto), el 2020-02-29.
            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2020, 2, 29));
        }
    }

    /**
     * #335 — El caso C del issue: una interrupcion <b>dentro</b> de una suspension que sigue en
     * curso. <b>Sin decidir, y fijado a proposito tal como esta.</b>
     *
     * <p>Una lectura del art. 46 al pie de la letra dice que el plazo nuevo nace suspendido y corre
     * cuando la tramitacion termina (2022-07-01 en esta siembra). Esa lectura no esta escrita donde
     * se busca: ni {@code prescripcion-y-plazos.md} de {@code normativa} la trae, ni existe NEG-19
     * —planificado en {@code srtm/docs/00-plan-documental.md}—. Y aqui no se rediseña una regla
     * tributaria. Asi que se conserva lo de antes —la interrupcion reinicia y descarta tambien la
     * parte en curso— y esta prueba lo dice en voz alta: quien cambie la regla la vera roja, y
     * tendra que cambiarla sabiendo que cambia.
     */
    @Nested
    @DisplayName("#335 — Interrupcion dentro de una suspension en curso: sin regla escrita")
    class InterrupcionDentroDeUnaSuspension {

        @Test
        @DisplayName("hoy la interrupcion descarta tambien la parte de la suspension que sigue")
        void laInterrupcionDescartaLaParteEnCurso() {
            List<HechoDelComputo> hechos =
                    List.of(
                            HechoDelComputo.suspension(
                                    "tramitacion del procedimiento contencioso tributario",
                                    LocalDate.of(2017, 1, 1),
                                    LocalDate.of(2018, 6, 30)),
                            HechoDelComputo.interrupcion(
                                    "notificacion de la orden de pago", LocalDate.of(2017, 6, 1)));

            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            INICIO, CUATRO_ANIOS, hechos, LocalDate.of(2021, 12, 1));

            assertThat(computo.inicioVigente()).isEqualTo(LocalDate.of(2017, 6, 2));
            assertThat(computo.fechaDePrescripcion()).isEqualTo(LocalDate.of(2021, 6, 2));
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
