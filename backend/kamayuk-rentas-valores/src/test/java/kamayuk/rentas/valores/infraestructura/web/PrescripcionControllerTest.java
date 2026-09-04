package kamayuk.rentas.valores.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.valores.aplicacion.ConsultaDePrescripciones;
import kamayuk.rentas.valores.aplicacion.DeclararPrescripcion;
import kamayuk.rentas.valores.aplicacion.PlazosParametrizados;
import kamayuk.rentas.valores.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.valores.dobles.ParametrosDeMentira;
import kamayuk.rentas.valores.dobles.PrescripcionesEnMemoria;
import kamayuk.rentas.valores.dobles.ValoresEnMemoria;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #39 — Capa web de la prescripcion: {@code POST /api/v1/coactiva/prescripcion} (RF-094).
 *
 * <p>Se prueba el transporte, no la persistencia —eso lo verifica {@code NotificacionYPaseJdbcTest}
 * contra PostgreSQL real—. Lo propio de esta ruta es lo que <b>no</b> deja entrar: el plazo, el
 * inicio del computo y el resultado los deriva el servidor, y la pantalla del manual los dibuja de
 * solo lectura precisamente por eso.
 */
@DisplayName("Capa web — /api/v1/coactiva/prescripcion")
class PrescripcionControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 6, 1);

    private final ContribuyentesDeMentira contribuyentes =
            new ContribuyentesDeMentira()
                    .con(new ResumenDeContribuyente(7L, "C-0007", "TITULAR, PRUEBA", "DNI 1"));
    private final PrescripcionesEnMemoria prescripciones = new PrescripcionesEnMemoria();
    private final ParametrosDeMentira parametros =
            new ParametrosDeMentira()
                    .con("PLAZO", "PRESCRIPCION-DECLARACION_PRESENTADA", "4 ANIOS")
                    .con("PLAZO", "PRESCRIPCION_INICIO-PREDIAL", "1 ANIOS");
    private final DeclararPrescripcion declarar =
            new DeclararPrescripcion(
                    prescripciones,
                    new ValoresEnMemoria(),
                    new PlazosParametrizados(parametros),
                    (RegistroDeAuditoria registro) -> {});

    private final ConsultaDePrescripciones consulta =
            new ConsultaDePrescripciones(prescripciones, contribuyentes);

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new PrescripcionController(
                                    declarar,
                                    consulta,
                                    contribuyentes,
                                    Clock.fixed(
                                            HOY.atStartOfDay(ZoneOffset.UTC).toInstant(),
                                            ZoneOffset.UTC)))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new org.springframework.http.converter.json
                                    .JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    @Test
    @DisplayName("declara y devuelve el computo ejercicio por ejercicio, no solo el resultado")
    void declaraYDevuelveElComputo() throws Exception {
        MvcResult resultado =
                declararRango(
                        """
                        {"codContribuyente":"C-0007","tributo":"PREDIAL",
                         "ejercicioDesde":2020,"ejercicioHasta":2022,
                         "fechaDePresentacion":"2026-06-01",
                         "plazoAplicable":"DECLARACION_PRESENTADA",
                         "observacion":"Se resuelve la solicitud"}
                        """);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"resultado\":\"PROCEDE_EN_PARTE\"");
        assertThat(cuerpo).contains("\"plazo\":\"4 ANIOS\"");
        // La resolucion tiene que poder sustentarse: sale el computo de los tres ejercicios.
        assertThat(cuerpo).contains("\"ejercicio\":2020").contains("\"ejercicio\":2022");
        assertThat(cuerpo).contains("\"inicioDelComputo\":\"2021-01-01\"");
    }

    @Test
    @DisplayName("y lo declarado sale por la relacion, con los ejercicios que prescribieron (#674)")
    void loDeclaradoSaleEnLaRelacion() throws Exception {
        declararRango(
                """
                {"codContribuyente":"C-0007","tributo":"PREDIAL",
                 "ejercicioDesde":2020,"ejercicioHasta":2022,
                 "fechaDePresentacion":"2026-06-01",
                 "plazoAplicable":"DECLARACION_PRESENTADA",
                 "observacion":"Se resuelve la solicitud"}
                """);

        MvcResult relacion =
                mvc.perform(MockMvcRequestBuilders.get("/rentas/api/v1/coactiva/prescripcion"))
                        .andReturn();

        assertThat(relacion.getResponse().getStatus()).isEqualTo(200);
        String cuerpo = relacion.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"totalElementos\":1");
        assertThat(cuerpo)
                .as("de 2020 a 2022 prescriben los dos primeros: el tercero todavia es exigible")
                .contains("\"ejerciciosPrescritos\":[2020,2021]");
        assertThat(cuerpo)
                .as("ninguna cifra de dinero: la prescripcion no extingue un importe")
                .doesNotContain("importe");
    }

    @Test
    @DisplayName("una interrupcion alegada viaja y sale reflejada en el computo")
    void laInterrupcionViajaYSaleReflejada() throws Exception {
        MvcResult resultado =
                declararRango(
                        """
                        {"codContribuyente":"C-0007","tributo":"PREDIAL",
                         "ejercicioDesde":2020,"ejercicioHasta":2020,
                         "fechaDePresentacion":"2026-06-01",
                         "plazoAplicable":"DECLARACION_PRESENTADA",
                         "hechos":[{"clase":"INTERRUPCION","causal":"pago parcial de la deuda",
                                    "fechaDesde":"2024-02-02"}],
                         "nDeResolucion":"RES-2026-001",
                         "observacion":"Se resuelve la solicitud"}
                        """);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"resultado\":\"NO_PROCEDE\"");
        assertThat(cuerpo).contains("\"nuevoInicioDelComputo\":\"2024-02-03\"");
        assertThat(cuerpo).contains("\"causal\":\"pago parcial de la deuda\"");
    }

    @Test
    @DisplayName("sin observacion, 422: no se declara nada")
    void sinObservacionRechaza() throws Exception {
        MvcResult resultado =
                declararRango(
                        """
                        {"codContribuyente":"C-0007","tributo":"PREDIAL",
                         "ejercicioDesde":2020,"ejercicioHasta":2020,
                         "plazoAplicable":"DECLARACION_PRESENTADA"}
                        """);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(prescripciones.porId(1L)).isEmpty();
    }

    @Test
    @DisplayName("un contribuyente que no existe, 404")
    void contribuyenteInexistente404() throws Exception {
        MvcResult resultado =
                declararRango(
                        """
                        {"codContribuyente":"NO-EXISTE","tributo":"PREDIAL",
                         "ejercicioDesde":2020,"ejercicioHasta":2020,
                         "plazoAplicable":"DECLARACION_PRESENTADA",
                         "observacion":"Se resuelve la solicitud"}
                        """);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("una causal desconocida, 422 con las tres que se admiten")
    void causalDesconocida422() throws Exception {
        MvcResult resultado =
                declararRango(
                        """
                        {"codContribuyente":"C-0007","tributo":"PREDIAL",
                         "ejercicioDesde":2020,"ejercicioHasta":2020,
                         "plazoAplicable":"5 ANIOS",
                         "observacion":"Se resuelve la solicitud"}
                        """);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("AGENTE_RETENCION");
    }

    @Test
    @DisplayName("sin el plazo parametrizado, 422 nombrando la llave que falta (#192)")
    void sinPlazoParametrizado422() throws Exception {
        MvcResult resultado = declararCon(new ParametrosDeMentira());

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("PLAZO:PRESCRIPCION-DECLARACION_PRESENTADA");
        assertThat(resultado.getResponse().getContentAsString())
                .as("#691 — y la llave, legible por programa")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,"
                                + "\"llave\":\"PLAZO:PRESCRIPCION-DECLARACION_PRESENTADA\"}");
    }

    // ------------------------------------------------------- lo que falta publicar (#562)

    @Test
    @DisplayName("sin ningun conjunto sellado, 422 nombrando el ejercicio y no 500")
    void sinConjuntoSellado422() throws Exception {
        MvcResult resultado = declararCon(new ParametrosDeMentira().sinSellar());

        assertThat(resultado.getResponse().getStatus())
                .as("no es que el servidor este roto: es que nadie ha sellado 2026 (D-02a)")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("VALIDACION").contains("2026");
        assertThat(cuerpo)
                .as("y no hay llave que nombrar: lo que falta es el conjunto entero")
                .doesNotContain("PLAZO:")
                .doesNotContain("incidencia");
        assertThat(cuerpo)
                .as("#691 — y el miembro lo dice igual: ejercicio, sin llave")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2026}");
        assertThat(prescripciones.porId(1L)).isEmpty();
    }

    @Test
    @DisplayName("y no deja una incidencia de nivel ERROR en el registro del servidor")
    void loQueFaltaPublicarNoEnsuciaElRegistro() throws Exception {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ListAppender<ILoggingEvent> anotados = new ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        try {
            declararCon(new ParametrosDeMentira().sinSellar());
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(anotados.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList())
                .as("el registro de incidencias es para defectos, no para cifras sin publicar")
                .isEmpty();
    }

    @Test
    @DisplayName("lo que SI es un fallo del servidor sigue siendo 500 con su incidencia")
    void loQueSiEsInternoNoSeDisfraza() throws Exception {
        MvcResult resultado =
                declararCon(
                        new ParametrosDeMentira()
                                .con("PLAZO", "PRESCRIPCION-DECLARACION_PRESENTADA", "cuatro anios")
                                .con("PLAZO", "PRESCRIPCION_INICIO-PREDIAL", "1 ANIOS"));

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "traducir lo que falta publicar no puede convertir TODO en 422: un plazo"
                                + " sellado que no se puede leer es un dato que hay que investigar")
                .isEqualTo(500);
        assertThat(resultado.getResponse().getContentAsString()).contains("incidencia");
    }

    // ------------------------------------------------------------------

    /** El mismo borde con otro lector de parametros detras del computo. */
    private MvcResult declararCon(ParametrosDeMentira lector) throws Exception {
        MockMvc borde =
                MockMvcBuilders.standaloneSetup(
                                new PrescripcionController(
                                        new DeclararPrescripcion(
                                                prescripciones,
                                                new ValoresEnMemoria(),
                                                new PlazosParametrizados(lector),
                                                (RegistroDeAuditoria registro) -> {}),
                                        consulta,
                                        contribuyentes,
                                        Clock.fixed(
                                                HOY.atStartOfDay(ZoneOffset.UTC).toInstant(),
                                                ZoneOffset.UTC)))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .build();

        return borde.perform(
                        MockMvcRequestBuilders.post("/rentas/api/v1/coactiva/prescripcion")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"codContribuyente":"C-0007","tributo":"PREDIAL",
                                         "ejercicioDesde":2020,"ejercicioHasta":2020,
                                         "plazoAplicable":"DECLARACION_PRESENTADA",
                                         "observacion":"Se resuelve la solicitud"}
                                        """))
                .andReturn();
    }

    private MvcResult declararRango(String cuerpo) throws Exception {
        return mvc.perform(
                        MockMvcRequestBuilders.post("/rentas/api/v1/coactiva/prescripcion")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }
}
