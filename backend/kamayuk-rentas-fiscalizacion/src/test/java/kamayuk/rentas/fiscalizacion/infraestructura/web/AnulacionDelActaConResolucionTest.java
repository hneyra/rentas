package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.aplicacion.AnularActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeActas;
import kamayuk.rentas.fiscalizacion.dobles.ActasEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.LiquidacionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.MovimientosDeLiquidacionEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.ResolucionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeActa;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacion;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeFiscalizacion;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #338 — el paso 3 del escenario por HTTP: {@code POST /fiscalizacion/actas/{id}/anulacion} sobre
 * una visita cuya liquidación ya está ANULADA pero cuya resolución de determinación sigue en pie.
 *
 * <p>Sin base de datos: lo que aquí se fija es que la negativa del caso de uso sale como 409 y no
 * como 500. La siembra que distingue es la resolución: la misma visita con la misma liquidación
 * anulada, sin RDF, sí se anula (201).
 */
@DisplayName("#338 — Anular una visita cuya liquidacion anulada tiene RDF")
class AnulacionDelActaConResolucionTest {

    private static final Observacion OBSERVACION = Observacion.de("Siembra de la prueba");
    private static final LocalDate HOY = LocalDate.of(2026, 4, 1);
    private static final Ejercicio E2026 = new Ejercicio(2026);
    private static final long CONTRIBUYENTE = 10L;
    private static final long PREDIO = 20L;
    private static final long FICHA = 900L;

    private ActasEnMemoria actas;
    private LiquidacionesEnMemoria liquidaciones;
    private MovimientosDeLiquidacionEnMemoria movimientos;
    private ResolucionesEnMemoria resoluciones;
    private MockMvc mvc;
    private long actaId;
    private Liquidacion anulada;

    @BeforeEach
    void armar() {
        actas = new ActasEnMemoria();
        liquidaciones = new LiquidacionesEnMemoria();
        movimientos = new MovimientosDeLiquidacionEnMemoria();
        resoluciones = new ResolucionesEnMemoria();

        actaId =
                actas.sembrar(
                        ActaFiscalizacion.nuevaPredial(
                                1L,
                                1,
                                CONTRIBUYENTE,
                                PREDIO,
                                FICHA,
                                LocalDate.of(2026, 3, 1),
                                "J. Perez",
                                Hallazgo.SUBVALUADOR,
                                AreaM2.de("300.00"),
                                null,
                                "ampliacion",
                                OBSERVACION));
        anulada =
                liquidaciones.insertar(
                        Liquidacion.primera(
                                "LIQ-2026-000010",
                                E2026,
                                10,
                                actaId,
                                E2026,
                                E2026,
                                TipoDeFiscalizacion.CIERTA,
                                "Subvaluacion",
                                HOY,
                                OBSERVACION),
                        List.of());
        movimientos.insertar(
                MovimientoDeLiquidacion.apertura(
                        anulada.identificador(), HOY, "Apertura", OBSERVACION));
        movimientos.insertar(
                MovimientoDeLiquidacion.cambioDeEstado(
                        anulada.identificador(),
                        EstadoDeLiquidacion.ANULADA,
                        HOY,
                        "Se deja sin efecto",
                        OBSERVACION));

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new ActasController(
                                        new ConsultaDeActas(actas),
                                        new AnularActaFiscalizacion(
                                                actas,
                                                liquidaciones,
                                                movimientos,
                                                resoluciones,
                                                registro -> {}),
                                        new ContribuyentesDeMentira()
                                                .con(
                                                        CONTRIBUYENTE,
                                                        "C-0001",
                                                        "TITULAR, PRUEBA",
                                                        "Jr. Union 100")))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
                                new JacksonJsonHttpMessageConverter(
                                        JsonMapper.builder()
                                                .addModule(
                                                        new ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()))
                        .build();
    }

    @Test
    @DisplayName("con la RDF en pie: 409 nombrandola, y el acta sigue ABIERTA")
    void conLaResolucionEnPieEs409() throws Exception {
        resoluciones.registrar(
                ResolucionDeDeterminacion.predial(
                        "RDF-2026-000004",
                        1L,
                        anulada.identificador(),
                        CONTRIBUYENTE,
                        PREDIO,
                        FICHA,
                        FICHA + 1,
                        HOY,
                        "INFORME 12-2026",
                        "Subvaluacion",
                        "TUO LTM art. 14",
                        OBSERVACION));

        MvcResult resultado = anular();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
        assertThat(resultado.getResponse().getContentAsString()).contains("RDF-2026-000004");
        assertThat(actas.findById(actaId).orElseThrow().estado()).isEqualTo(EstadoDeActa.ABIERTA);
    }

    @Test
    @DisplayName("la misma visita sin RDF se anula: 201")
    void sinResolucionEs201() throws Exception {
        assertThat(anular().getResponse().getStatus()).isEqualTo(201);
        assertThat(actas.findById(actaId).orElseThrow().estado()).isEqualTo(EstadoDeActa.ANULADA);
    }

    private MvcResult anular() throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/fiscalizacion/actas/{id}/anulacion", actaId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"observacion\":\"La visita no vale\","
                                                + "\"fecha\":\"2026-04-01\"}"))
                .andReturn();
    }
}
