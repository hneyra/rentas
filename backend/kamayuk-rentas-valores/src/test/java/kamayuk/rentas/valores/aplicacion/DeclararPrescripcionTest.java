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
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.HechoDelComputo;
import kamayuk.rentas.valores.dominio.Prescripcion;
import kamayuk.rentas.valores.dominio.ResultadoDeLaSolicitud;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
                        OBSERVACION);

        assertThat(declarada.resultado()).isEqualTo(ResultadoDeLaSolicitud.NO_PROCEDE);
        assertThat(declarada.ejercicios().get(0).inicioVigente())
                .isEqualTo(LocalDate.of(2024, 2, 3));
        assertThat(declarada.hechos()).containsExactly(pago);
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
                OBSERVACION);
    }

    private Valor cobrable(String numero, int ejercicio) {
        return valores.con(valor(numero, ejercicio, EstadoDeValor.EMITIDO), detalle(ejercicio));
    }

    private static ValorDetalle detalle(int ejercicio) {
        return ValorDetalle.nuevo(
                "PREDIAL",
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
