package kamayuk.rentas.valores.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.dobles.ParametrosDeMentira;
import kamayuk.rentas.valores.dobles.PrescripcionesEnMemoria;
import kamayuk.rentas.valores.dobles.ValoresEnMemoria;
import kamayuk.rentas.valores.dominio.CausalDePrescripcion;
import kamayuk.rentas.valores.dominio.ComputoDeEjercicio;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.HechoDelComputo;
import kamayuk.rentas.valores.dominio.Prescripcion;
import kamayuk.rentas.valores.dominio.ResultadoDeLaSolicitud;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #39 — La declaracion de prescripcion, sin base de datos (RF-094).
 *
 * <p>Los plazos entran por el doble de parametros: ninguna prueba de aqui escribe "4 anios" como si
 * fuera parte del algoritmo. La cifra es #192; la estructura es esta.
 */
@DisplayName("#39 — DeclararPrescripcion")
class DeclararPrescripcionTest {

    private static final long CONTRIBUYENTE = 7L;
    private static final LocalDate PRESENTACION = LocalDate.of(2026, 6, 1);
    private static final Observacion OBSERVACION = Observacion.de("Se resuelve para la prueba");

    private ValoresEnMemoria valores;
    private PrescripcionesEnMemoria prescripciones;
    private List<RegistroDeAuditoria> auditados;
    private DeclararPrescripcion servicio;

    @BeforeEach
    void preparar() {
        valores = new ValoresEnMemoria();
        prescripciones = new PrescripcionesEnMemoria();
        auditados = new ArrayList<>();
        ParametrosDeMentira parametros =
                new ParametrosDeMentira()
                        .con("PLAZO", "PRESCRIPCION-DECLARACION_PRESENTADA", "4 ANIOS")
                        .con("PLAZO", "PRESCRIPCION-SIN_DECLARACION", "6 ANIOS")
                        .con("PLAZO", "PRESCRIPCION_INICIO-PREDIAL", "1 ANIOS");
        servicio =
                new DeclararPrescripcion(
                        prescripciones,
                        valores,
                        new PlazosParametrizados(parametros),
                        auditados::add);
    }

    @Test
    @DisplayName("el inicio del computo es el 1 de enero, desplazado por el parametro (art. 44)")
    void elInicioSaleDelParametro() {
        Prescripcion declarada = declarar(2018, 2018, CausalDePrescripcion.DECLARACION_PRESENTADA);

        assertThat(declarada.ejercicios()).hasSize(1);
        assertThat(declarada.ejercicios().get(0).inicioComputo())
                .isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(declarada.ejercicios().get(0).fechaPrescripcion())
                .isEqualTo(LocalDate.of(2023, 1, 1));
    }

    @Test
    @DisplayName("la causal decide el plazo, y el plazo decide el resultado")
    void laCausalDecideElPlazo() {
        // Ejercicio 2020: computo desde 2021-01-01. Con 4 anios prescribe en 2025 -antes de la
        // solicitud-; con 6, en 2027 -despues-.
        Prescripcion conCuatro = declarar(2020, 2020, CausalDePrescripcion.DECLARACION_PRESENTADA);
        Prescripcion conSeis = declarar(2020, 2020, CausalDePrescripcion.SIN_DECLARACION);

        assertThat(conCuatro.resultado()).isEqualTo(ResultadoDeLaSolicitud.PROCEDE);
        assertThat(conSeis.resultado()).isEqualTo(ResultadoDeLaSolicitud.NO_PROCEDE);
    }

    @Test
    @DisplayName("procede en parte: un ejercicio por ejercicio, no un si o un no para el rango")
    void procedeEnParte() {
        // Con 4 anios y computo desde el ejercicio+1: 2020 prescribe en 2025 y 2022 en 2027.
        Prescripcion declarada = declarar(2020, 2022, CausalDePrescripcion.DECLARACION_PRESENTADA);

        assertThat(declarada.resultado()).isEqualTo(ResultadoDeLaSolicitud.PROCEDE_EN_PARTE);
        assertThat(declarada.ejercicios()).hasSize(3);
        assertThat(declarada.ejerciciosPrescritos())
                .containsExactly(new Ejercicio(2020), new Ejercicio(2021));
    }

    @Test
    @DisplayName("una interrupcion alegada mueve el resultado, y queda guardada con el acto")
    void laInterrupcionMueveElResultado() {
        HechoDelComputo pago =
                HechoDelComputo.interrupcion("pago parcial de la deuda", LocalDate.of(2024, 2, 2));

        Prescripcion declarada =
                servicio.declarar(
                                CONTRIBUYENTE,
                                "PREDIAL",
                                new Ejercicio(2020),
                                new Ejercicio(2020),
                                PRESENTACION,
                                CausalDePrescripcion.DECLARACION_PRESENTADA,
                                List.of(pago),
                                "RES-001",
                                OBSERVACION)
                        .prescripcion();

        assertThat(declarada.resultado()).isEqualTo(ResultadoDeLaSolicitud.NO_PROCEDE);
        assertThat(declarada.ejercicios().get(0).inicioVigente())
                .isEqualTo(LocalDate.of(2024, 2, 3));
        // Guardado con su alcance: en un rango de uno, el hecho es de ese ejercicio (#334).
        assertThat(declarada.hechos()).containsExactly(pago.para(new Ejercicio(2020)));
        assertThat(declarada.resolucion()).isEqualTo("RES-001");
    }

    @Test
    @DisplayName("marca PRESCRITO los valores alcanzados, y no toca los demas")
    void marcaLosValoresAlcanzados() {
        Valor delDosMilVeinte = cobrable("OP-2026-000001", 2020);
        Valor delDosMilVeintidos = cobrable("OP-2026-000002", 2022);

        declarar(2020, 2022, CausalDePrescripcion.DECLARACION_PRESENTADA);

        assertThat(valores.porId(delDosMilVeinte.id()).orElseThrow().estado())
                .isEqualTo(EstadoDeValor.PRESCRITO);
        assertThat(valores.porId(delDosMilVeintidos.id()).orElseThrow().estado())
                .isEqualTo(EstadoDeValor.EMITIDO);
    }

    @Test
    @DisplayName("un valor ya pagado no se marca: no hay accion de cobro que prescriba")
    void noMarcaLoQueYaNoSeCobra() {
        Valor pagado =
                valores.con(valor("OP-2026-000009", 2020, EstadoDeValor.PAGADO), detalle(2020));

        declarar(2020, 2020, CausalDePrescripcion.DECLARACION_PRESENTADA);

        assertThat(valores.porId(pagado.id()).orElseThrow().estado())
                .isEqualTo(EstadoDeValor.PAGADO);
    }

    @Test
    @DisplayName("sin el plazo parametrizado no se declara nada")
    void sinPlazoParametrizadoFalla() {
        DeclararPrescripcion sinPlazos =
                new DeclararPrescripcion(
                        prescripciones,
                        valores,
                        new PlazosParametrizados(new ParametrosDeMentira()),
                        auditados::add);

        assertThatThrownBy(
                        () ->
                                sinPlazos.declarar(
                                        CONTRIBUYENTE,
                                        "PREDIAL",
                                        new Ejercicio(2020),
                                        new Ejercicio(2020),
                                        PRESENTACION,
                                        CausalDePrescripcion.DECLARACION_PRESENTADA,
                                        List.of(),
                                        null,
                                        OBSERVACION))
                .isInstanceOf(PlazosParametrizados.PlazoSinParametrizar.class)
                .hasMessageContaining("PLAZO:PRESCRIPCION-DECLARACION_PRESENTADA");
    }

    @Test
    @DisplayName("el rango invertido se rechaza")
    void elRangoInvertidoSeRechaza() {
        assertThatThrownBy(
                        () ->
                                servicio.declarar(
                                        CONTRIBUYENTE,
                                        "PREDIAL",
                                        new Ejercicio(2022),
                                        new Ejercicio(2020),
                                        PRESENTACION,
                                        CausalDePrescripcion.DECLARACION_PRESENTADA,
                                        List.of(),
                                        null,
                                        OBSERVACION))
                .isInstanceOf(DeclararPrescripcion.RangoInvertido.class);
    }

    /**
     * #334 — Un rango de <b>varios</b> ejercicios, <b>con</b> hechos.
     *
     * <p>Es la siembra que faltaba: la muestra de arriba —un solo ejercicio con un hecho posterior
     * a su inicio— pasa igual aplicando cada hecho a su ejercicio que aplicandolos todos a todos, y
     * por eso el defecto vivio. Aqui cada hecho es de la deuda de UN ejercicio, lo dice, y hay otro
     * ejercicio en el rango al que no le toca. Todos son PREDIAL con DECLARACION_PRESENTADA (4
     * anios) y desfase de 1 anio, como en el issue.
     */
    @Nested
    @DisplayName("#334 — Cada hecho, en el computo de su ejercicio y en el tramo en que corre")
    class CadaHechoEnSuEjercicio {

        private final Ejercicio e2019 = new Ejercicio(2019);
        private final Ejercicio e2020 = new Ejercicio(2020);
        private final Ejercicio e2021 = new Ejercicio(2021);

        @Test
        @DisplayName("(a) el pago del 2019 no hace 422 en 2021, y 2021 sale prescrito")
        void a() {
            // El pago de la cuota de mayo del 2021 es de la deuda del 2021 y ocurre ANTES de que
            // su plazo empiece (2022-01-01): sin el recorte, el inicio vigente quedaria en el
            // 2021-06-01 y la solicitud entera saldria 422.
            Prescripcion declarada =
                    declararConHechos(
                            2019,
                            2021,
                            LocalDate.of(2026, 3, 1),
                            HechoDelComputo.interrupcion(
                                            "pago parcial del predial 2019",
                                            LocalDate.of(2021, 3, 15))
                                    .para(e2019),
                            HechoDelComputo.interrupcion(
                                            "pago de la cuota de mayo del predial 2021",
                                            LocalDate.of(2021, 5, 31))
                                    .para(e2021));

            assertThat(declarada.ejerciciosPrescritos()).containsExactly(e2019, e2020, e2021);
            assertThat(declarada.resultado()).isEqualTo(ResultadoDeLaSolicitud.PROCEDE);
            // El pago del 2019 SI reinicia el 2019: se aplica a su ejercicio, y solo a el.
            assertThat(computoDe(declarada, e2019).inicioVigente())
                    .isEqualTo(LocalDate.of(2021, 3, 16));
            assertThat(computoDe(declarada, e2020).inicioVigente())
                    .isEqualTo(LocalDate.of(2021, 1, 1));
            assertThat(computoDe(declarada, e2021).inicioVigente())
                    .isEqualTo(LocalDate.of(2022, 1, 1));
            assertThat(computoDe(declarada, e2021).fechaPrescripcion())
                    .isEqualTo(LocalDate.of(2026, 1, 1));
        }

        @Test
        @DisplayName("(a) de un solo ejercicio: el pago de su propia cuota no hace 422")
        void aDeUnSoloEjercicio() {
            // «El rodeo no alcanza»: partir la solicitud por ano no evita este caso. Y sin alcance
            // declarado, porque en un rango de uno no hay a donde mas pertenecer.
            Prescripcion declarada =
                    declararConHechos(
                            2021,
                            2021,
                            LocalDate.of(2026, 3, 1),
                            HechoDelComputo.interrupcion(
                                    "pago de la cuota de mayo del predial 2021",
                                    LocalDate.of(2021, 5, 31)));

            assertThat(declarada.resultado()).isEqualTo(ResultadoDeLaSolicitud.PROCEDE);
            assertThat(declarada.ejercicios().get(0).inicioVigente())
                    .isEqualTo(LocalDate.of(2022, 1, 1));
        }

        @Test
        @DisplayName("(b) ni la reclamacion del 2019 ni la del 2021 niegan el 2021: PROCEDE")
        void b() {
            // La del 2019 no es del 2021. La del 2021 si lo es, pero termino antes de que su plazo
            // empezara: sin el recorte suma 289 dias, el 2021 vence el 2026-10-17 y la solicitud
            // sale PROCEDE_EN_PARTE.
            Prescripcion declarada =
                    declararConHechos(
                            2019,
                            2021,
                            LocalDate.of(2026, 6, 1),
                            HechoDelComputo.suspension(
                                            "reclamacion contra la RD del predial 2019",
                                            LocalDate.of(2020, 2, 1),
                                            LocalDate.of(2020, 12, 15))
                                    .para(e2019),
                            HechoDelComputo.suspension(
                                            "reclamacion contra la determinacion del predial 2021",
                                            LocalDate.of(2021, 3, 1),
                                            LocalDate.of(2021, 12, 15))
                                    .para(e2021));

            assertThat(declarada.resultado()).isEqualTo(ResultadoDeLaSolicitud.PROCEDE);
            assertThat(computoDe(declarada, e2021).fechaPrescripcion())
                    .isEqualTo(LocalDate.of(2026, 1, 1));
            // Y la del 2019 si corre el 2019, que es de quien es: 319 dias contando el ultimo
            // (#335) desde el 2024-01-01, que es bisiesto.
            assertThat(computoDe(declarada, e2019).fechaPrescripcion())
                    .isEqualTo(LocalDate.of(2024, 11, 15));
        }

        @Test
        @DisplayName("(c) el pago del 2019 no reinicia el 2020: PROCEDE_EN_PARTE")
        void c() {
            Prescripcion declarada =
                    declararConHechos(
                            2019,
                            2020,
                            LocalDate.of(2025, 2, 3),
                            HechoDelComputo.interrupcion(
                                            "pago parcial del predial 2019",
                                            LocalDate.of(2021, 3, 10))
                                    .para(e2019));

            assertThat(declarada.resultado()).isEqualTo(ResultadoDeLaSolicitud.PROCEDE_EN_PARTE);
            assertThat(declarada.ejerciciosPrescritos()).containsExactly(e2020);
            assertThat(computoDe(declarada, e2020).fechaPrescripcion())
                    .isEqualTo(LocalDate.of(2025, 1, 1));
            assertThat(computoDe(declarada, e2019).fechaPrescripcion())
                    .isEqualTo(LocalDate.of(2025, 3, 11));
        }

        @Test
        @DisplayName("un hecho de dos ejercicios actua en los dos, y en ninguno mas")
        void unHechoDeDosEjercicios() {
            // Un reconocimiento expreso de la deuda del 2019 y del 2020 en el mismo escrito.
            Prescripcion declarada =
                    declararConHechos(
                            2019,
                            2021,
                            LocalDate.of(2026, 3, 1),
                            HechoDelComputo.interrupcion(
                                            "reconocimiento expreso de la obligacion",
                                            LocalDate.of(2023, 4, 4))
                                    .para(e2019, e2020));

            assertThat(computoDe(declarada, e2019).inicioVigente())
                    .isEqualTo(LocalDate.of(2023, 4, 5));
            assertThat(computoDe(declarada, e2020).inicioVigente())
                    .isEqualTo(LocalDate.of(2023, 4, 5));
            assertThat(computoDe(declarada, e2021).inicioVigente())
                    .isEqualTo(LocalDate.of(2022, 1, 1));
            assertThat(declarada.ejerciciosPrescritos()).containsExactly(e2021);
        }

        @Test
        @DisplayName("en un rango de varios, un hecho sin alcance se rechaza nombrandolo")
        void sinAlcanceEnUnRangoSeRechaza() {
            // Suponer «todos» es el defecto: no se supone nada.
            assertThatThrownBy(
                            () ->
                                    declararConHechos(
                                            2019,
                                            2021,
                                            LocalDate.of(2026, 3, 1),
                                            HechoDelComputo.interrupcion(
                                                    "pago parcial del predial 2019",
                                                    LocalDate.of(2021, 3, 15))))
                    .isInstanceOf(DeclararPrescripcion.HechoSinAlcance.class)
                    .hasMessageContaining("'pago parcial del predial 2019'")
                    .hasMessageContaining("2021-03-15")
                    .hasMessageContaining("ejercicios");
            assertThat(prescripciones.porId(1L)).isEmpty();
        }

        @Test
        @DisplayName("un hecho que nombra un ejercicio fuera del rango se rechaza nombrandolo")
        void fueraDelRangoSeRechaza() {
            assertThatThrownBy(
                            () ->
                                    declararConHechos(
                                            2019,
                                            2021,
                                            LocalDate.of(2026, 3, 1),
                                            HechoDelComputo.interrupcion(
                                                            "pago parcial del predial 2018",
                                                            LocalDate.of(2021, 3, 15))
                                                    .para(new Ejercicio(2018))))
                    .isInstanceOf(DeclararPrescripcion.AlcanceFueraDelRango.class)
                    .hasMessageContaining("'pago parcial del predial 2018'")
                    .hasMessageContaining("2018");
            assertThat(prescripciones.porId(1L)).isEmpty();
        }

        @Test
        @DisplayName("lo que se guarda es el hecho con su alcance, tambien el completado")
        void seGuardaConSuAlcance() {
            HechoDelComputo pago =
                    HechoDelComputo.interrupcion(
                            "pago de la cuota de mayo del predial 2021", LocalDate.of(2021, 5, 31));

            Prescripcion declarada = declararConHechos(2021, 2021, LocalDate.of(2026, 3, 1), pago);

            assertThat(declarada.hechos()).containsExactly(pago.para(e2021));
        }

        private ComputoDeEjercicio computoDe(Prescripcion prescripcion, Ejercicio ejercicio) {
            return prescripcion.ejercicios().stream()
                    .filter(computo -> computo.ejercicio().equals(ejercicio))
                    .findFirst()
                    .orElseThrow();
        }
    }

    /**
     * #337 — Un valor formaliza varias obligaciones, y la prescripcion es de cada una.
     *
     * <p>Es la siembra que faltaba: {@link #marcaLosValoresAlcanzados} usa valores de <b>una</b>
     * linea, y con esa muestra «alguna linea coincide» y «ninguna linea queda fuera» dan lo mismo.
     * Aqui el mismo contribuyente tiene cuatro valores:
     *
     * <ul>
     *   <li>A, solo con PREDIAL 2021;
     *   <li>B, con PREDIAL 2021 y PREDIAL 2022;
     *   <li>C, con PREDIAL 2021 y ARBITRIOS 2021 —un tributo que la solicitud ni pide—;
     *   <li>D, igual que C pero ya en {@code COACTIVA}, porque el defecto tambien lo alcanzaba.
     * </ul>
     *
     * <p>Con la solicitud de PREDIAL 2021–2022 del 2026-03-01 prescribe 2021 (el 2026-01-01) y no
     * 2022 (el 2027-01-01): solo A queda entero dentro de lo prescrito. La del PREDIAL 2022 del
     * 2027-02-01 completa B —la cobertura se acumula entre resoluciones— y a C y D no les llega
     * nunca: los ARBITRIOS 2021 no los prescribio nadie.
     */
    @Nested
    @DisplayName("#337 — PRESCRITO solo el valor cuyas lineas prescribieron todas")
    class LaCoberturaDeLaPrescripcion {

        private static final LocalDate PRIMERA = LocalDate.of(2026, 3, 1);
        private static final LocalDate SEGUNDA = LocalDate.of(2027, 2, 1);

        private Valor a;
        private Valor b;
        private Valor c;
        private Valor d;

        @BeforeEach
        void sembrar() {
            a =
                    valores.con(
                            valor("OP-2026-000011", 2021, EstadoDeValor.EMITIDO),
                            linea("PREDIAL", 2021));
            b =
                    valores.con(
                            valor("OP-2026-000012", 2021, EstadoDeValor.EMITIDO),
                            linea("PREDIAL", 2021),
                            linea("PREDIAL", 2022));
            c =
                    valores.con(
                            valor("OP-2026-000013", 2021, EstadoDeValor.EMITIDO),
                            linea("PREDIAL", 2021),
                            linea("ARBITRIOS", 2021));
            d =
                    valores.con(
                            valor("OP-2026-000014", 2021, EstadoDeValor.COACTIVA),
                            linea("PREDIAL", 2021),
                            linea("ARBITRIOS", 2021));
        }

        @Test
        @DisplayName("la solicitud de 2021–2022 marca A y deja B, C y D como estaban")
        void laPrimeraSoloMarcaA() {
            PrescripcionDeclarada declarada = declararPredial(2021, 2022, PRIMERA);

            assertThat(declarada.prescripcion().resultado())
                    .isEqualTo(ResultadoDeLaSolicitud.PROCEDE_EN_PARTE);
            assertThat(declarada.prescripcion().ejerciciosPrescritos())
                    .containsExactly(new Ejercicio(2021));
            assertThat(estadoDe(a)).isEqualTo(EstadoDeValor.PRESCRITO);
            assertThat(estadoDe(b))
                    .as("B formaliza el PREDIAL 2022, que no prescribio")
                    .isEqualTo(EstadoDeValor.EMITIDO);
            assertThat(estadoDe(c))
                    .as("C formaliza los ARBITRIOS 2021, que la solicitud ni pidio")
                    .isEqualTo(EstadoDeValor.EMITIDO);
            assertThat(estadoDe(d))
                    .as("y D sigue en coactiva: su deuda viva se puede seguir exigiendo")
                    .isEqualTo(EstadoDeValor.COACTIVA);
        }

        @Test
        @DisplayName("la respuesta y la auditoria nombran los marcados y los que se dejaron")
        void seInformanLosDosGrupos() {
            PrescripcionDeclarada declarada = declararPredial(2021, 2022, PRIMERA);

            assertThat(declarada.valoresPrescritos())
                    .extracting(Valor::numero)
                    .containsExactly("OP-2026-000011");
            assertThat(declarada.valoresCubiertosEnParte())
                    .as("los que tocan el PREDIAL 2021 y formalizan ademas deuda viva")
                    .extracting(Valor::numero)
                    .containsExactly("OP-2026-000012", "OP-2026-000013", "OP-2026-000014");
            assertThat(auditados).hasSize(1);
            assertThat(auditados.get(0).datosNuevos())
                    .contains("\"valoresPrescritos\":[\"OP-2026-000011\"]")
                    .contains(
                            "\"valoresCubiertosEnParte\":[\"OP-2026-000012\",\"OP-2026-000013\","
                                    + "\"OP-2026-000014\"]");
        }

        @Test
        @DisplayName("la del 2022 completa B, porque la cobertura se acumula, y C y D siguen vivos")
        void laSegundaCompletaB() {
            declararPredial(2021, 2022, PRIMERA);
            PrescripcionDeclarada segunda = declararPredial(2022, 2022, SEGUNDA);

            assertThat(segunda.prescripcion().resultado())
                    .isEqualTo(ResultadoDeLaSolicitud.PROCEDE);
            assertThat(segunda.valoresPrescritos())
                    .extracting(Valor::numero)
                    .containsExactly("OP-2026-000012");
            assertThat(segunda.valoresCubiertosEnParte())
                    .as("C y D no tocan el PREDIAL 2022: esta resolucion no va con ellos")
                    .isEmpty();
            assertThat(estadoDe(a)).isEqualTo(EstadoDeValor.PRESCRITO);
            assertThat(estadoDe(b))
                    .as("el PREDIAL 2021 lo prescribio la primera y el 2022 la segunda")
                    .isEqualTo(EstadoDeValor.PRESCRITO);
            assertThat(estadoDe(c)).isEqualTo(EstadoDeValor.EMITIDO);
            assertThat(estadoDe(d)).isEqualTo(EstadoDeValor.COACTIVA);
        }

        private PrescripcionDeclarada declararPredial(
                int desde, int hasta, LocalDate presentacion) {
            return servicio.declarar(
                    CONTRIBUYENTE,
                    "PREDIAL",
                    new Ejercicio(desde),
                    new Ejercicio(hasta),
                    presentacion,
                    CausalDePrescripcion.DECLARACION_PRESENTADA,
                    List.of(),
                    null,
                    OBSERVACION);
        }

        private EstadoDeValor estadoDe(Valor valor) {
            return valores.porId(valor.id()).orElseThrow().estado();
        }
    }

    private Prescripcion declararConHechos(
            int desde, int hasta, LocalDate presentacion, HechoDelComputo... hechos) {
        return servicio.declarar(
                        CONTRIBUYENTE,
                        "PREDIAL",
                        new Ejercicio(desde),
                        new Ejercicio(hasta),
                        presentacion,
                        CausalDePrescripcion.DECLARACION_PRESENTADA,
                        List.of(hechos),
                        null,
                        OBSERVACION)
                .prescripcion();
    }

    // ------------------------------------------------------------------

    private Prescripcion declarar(int desde, int hasta, CausalDePrescripcion causal) {
        return servicio.declarar(
                        CONTRIBUYENTE,
                        "PREDIAL",
                        new Ejercicio(desde),
                        new Ejercicio(hasta),
                        PRESENTACION,
                        causal,
                        List.of(),
                        null,
                        OBSERVACION)
                .prescripcion();
    }

    private Valor cobrable(String numero, int ejercicio) {
        return valores.con(valor(numero, ejercicio, EstadoDeValor.EMITIDO), detalle(ejercicio));
    }

    private static ValorDetalle detalle(int ejercicio) {
        return linea("PREDIAL", ejercicio);
    }

    private static ValorDetalle linea(String tributo, int ejercicio) {
        return ValorDetalle.nuevo(
                tributo,
                new Ejercicio(ejercicio),
                null,
                null,
                null,
                null,
                Dinero.de("100.00"),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO);
    }

    private static Valor valor(String numero, int ejercicio, EstadoDeValor estado) {
        LocalDate emision = LocalDate.of(ejercicio + 1, 3, 1);
        return new Valor(
                null,
                TipoValor.ORDEN_DE_PAGO,
                numero,
                new Ejercicio(ejercicio + 1),
                CONTRIBUYENTE,
                TipoValor.ORDEN_DE_PAGO.baseLegal(),
                Dinero.de("100.00"),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO,
                emision,
                estado,
                emision,
                null,
                Observacion.de("Emitido para la prueba"));
    }
}
