package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeProgramas;
import kamayuk.rentas.fiscalizacion.aplicacion.RegistrarPrograma;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeProgramas;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDePrograma;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.TipoDePrograma;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import kamayuk.rentas.web.ParametrosDePaginacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #45 y #431 — Capa web: se prueba el transporte (forma del JSON, la observacion obligatoria, de
 * donde salen los filtros del listado), no la persistencia —eso ya lo verifica {@code
 * ProgramaFiscalizacionRepositoryJdbcTest} contra PostgreSQL real.
 */
@DisplayName("Capa web — GET y POST /api/v1/fiscalizacion/programas")
class ProgramasControllerTest {

    private final List<ProgramaFiscalizacion> guardados = new ArrayList<>();

    /**
     * Un doble que <b>anota lo que le llega</b>: es lo que permite comprobar que los dos filtros
     * del listado viajan por la consulta y no se pierden por el camino.
     */
    private final List<CriterioDeProgramas> criteriosPedidos = new ArrayList<>();

    private final List<Paginacion> paginacionesPedidas = new ArrayList<>();

    private final ProgramaFiscalizacionRepository repositorio =
            new ProgramaFiscalizacionRepository() {
                private long siguiente = 1;

                @Override
                public ProgramaFiscalizacion insertar(ProgramaFiscalizacion programa) {
                    ProgramaFiscalizacion guardado =
                            new ProgramaFiscalizacion(
                                    siguiente++,
                                    programa.codigo(),
                                    programa.descripcion(),
                                    programa.tipo(),
                                    programa.fechaInicio(),
                                    programa.fechaFin(),
                                    programa.estado(),
                                    // Los cuatro parametros de la muestra tienen que sobrevivir al
                                    // doble: si los perdiera, la prueba de que viajan pasaria en
                                    // verde contra un repositorio que los tira.
                                    programa.ejercicio(),
                                    programa.sectorCodigo(),
                                    programa.criterio(),
                                    programa.fiscalizador());
                    guardados.add(guardado);
                    return guardado;
                }

                @Override
                public Optional<ProgramaFiscalizacion> findById(long id) {
                    return guardados.stream().filter(p -> p.id() == id).findFirst();
                }

                @Override
                public Pagina<ProgramaFiscalizacion> consultar(
                        CriterioDeProgramas criterio, Paginacion paginacion) {
                    criteriosPedidos.add(criterio);
                    paginacionesPedidas.add(paginacion);
                    return Pagina.de(List.copyOf(guardados), paginacion, guardados.size());
                }
            };

    private final RegistrarPrograma servicio =
            new RegistrarPrograma(repositorio, (RegistroDeAuditoria registro) -> {});

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ProgramasController(servicio, new ConsultaDeProgramas(repositorio)))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    @Test
    @DisplayName("programa un tipo PREDIAL y devuelve 201 con el codigo en camelCase")
    void programaYDevuelve201() throws Exception {
        String cuerpo =
                "{\"observacion\":\"Se programa para la prueba\",\"codigo\":\"PF-200\","
                        + "\"descripcion\":\"Muestra de riesgo\",\"tipo\":\"PREDIAL\","
                        + "\"fechaInicio\":\"2026-03-01\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/programas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"PF-200\"")
                .contains("\"tipo\":\"PREDIAL\"")
                .contains("\"estado\":\"ABIERTO\"");
    }

    @Test
    @DisplayName("sin observacion, 422 y no guarda nada")
    void sinObservacionNoGuardaNada() throws Exception {
        String cuerpo =
                "{\"codigo\":\"PF-201\",\"descripcion\":\"Muestra de riesgo\","
                        + "\"tipo\":\"PREDIAL\",\"fechaInicio\":\"2026-03-01\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/programas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(guardados).isEmpty();
    }

    @Test
    @DisplayName("el filtro «tipo» viaja por la consulta y decide de que es el programa (#425)")
    void elTipoViajaPorLaConsulta() throws Exception {
        String sinTipo =
                "{\"observacion\":\"Se programa para la prueba\",\"codigo\":\"PF-210\","
                        + "\"descripcion\":\"Muestra de riesgo\",\"fechaInicio\":\"2026-03-01\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/programas")
                                        .param("tipo", "VEHICULAR")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(sinTipo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(guardados)
                .as("no basta con que se acepte: el programa guardado es del tipo que se pidio")
                .singleElement()
                .satisfies(
                        programa ->
                                assertThat(programa.tipo()).isEqualTo(TipoDePrograma.VEHICULAR));
        assertThat(resultado.getResponse().getContentAsString()).contains("\"tipo\":\"VEHICULAR\"");
    }

    @Test
    @DisplayName("y si viene en los dos sitios gana el cuerpo: el cliente viejo sigue igual")
    void elCuerpoGanaALaConsulta() throws Exception {
        String conTipo =
                "{\"observacion\":\"Se programa para la prueba\",\"codigo\":\"PF-211\","
                        + "\"descripcion\":\"Muestra de riesgo\",\"tipo\":\"PREDIAL\","
                        + "\"fechaInicio\":\"2026-03-01\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/programas")
                                        .param("tipo", "VEHICULAR")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(conTipo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"tipo\":\"PREDIAL\"");
    }

    @Test
    @DisplayName("sin tipo en ninguno de los dos sitios, 422 y no guarda nada")
    void sinTipoEnNingunSitio422() throws Exception {
        String sinTipo =
                "{\"observacion\":\"Se programa para la prueba\",\"codigo\":\"PF-212\","
                        + "\"descripcion\":\"Muestra de riesgo\",\"fechaInicio\":\"2026-03-01\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/programas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(sinTipo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(guardados).isEmpty();
    }

    @Test
    @DisplayName("con un tipo desconocido, 422")
    void conUnTipoDesconocido422() throws Exception {
        String cuerpo =
                "{\"observacion\":\"Se programa para la prueba\",\"codigo\":\"PF-202\","
                        + "\"descripcion\":\"Muestra de riesgo\",\"tipo\":\"AMBIENTAL\","
                        + "\"fechaInicio\":\"2026-03-01\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/programas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
    }

    /* ── #431: la lectura del programa ─────────────────────────────────── */

    @Test
    @DisplayName("el listado devuelve los programas en el sobre paginado de siempre")
    void elListadoDevuelveElSobrePaginado() throws Exception {
        guardados.add(unPrograma("PF-300", TipoDePrograma.PREDIAL));

        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/fiscalizacion/programas")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"contenido\"")
                .contains("\"totalElementos\":1")
                .contains("\"codigo\":\"PF-300\"")
                .contains("\"tipo\":\"PREDIAL\"")
                .contains("\"estado\":\"ABIERTO\"");
    }

    @Test
    @DisplayName("los dos filtros viajan por la CONSULTA, no por el cuerpo")
    void losDosFiltrosViajanPorLaConsulta() throws Exception {
        mvc.perform(
                        get("/rentas/api/v1/fiscalizacion/programas")
                                .param("nDePrograma", "PF-300")
                                .param("ejercicio", "2026"))
                .andReturn();

        assertThat(criteriosPedidos).hasSize(1);
        assertThat(criteriosPedidos.get(0).codigo()).isEqualTo("PF-300");
        assertThat(criteriosPedidos.get(0).ejercicio()).isEqualTo(2026);
    }

    @Test
    @DisplayName("sin filtros no inventa ninguno: el criterio llega vacio")
    void sinFiltrosElCriterioLlegaVacio() throws Exception {
        mvc.perform(get("/rentas/api/v1/fiscalizacion/programas")).andReturn();

        assertThat(criteriosPedidos).hasSize(1);
        assertThat(criteriosPedidos.get(0).codigo()).isNull();
        assertThat(criteriosPedidos.get(0).ejercicio()).isNull();
    }

    @Test
    @DisplayName("un filtro en blanco no es un filtro")
    void unFiltroEnBlancoNoEsUnFiltro() throws Exception {
        mvc.perform(
                        get("/rentas/api/v1/fiscalizacion/programas")
                                .param("nDePrograma", "   ")
                                .param("ejercicio", ""))
                .andReturn();

        assertThat(criteriosPedidos).hasSize(1);
        assertThat(criteriosPedidos.get(0).codigo()).isNull();
        assertThat(criteriosPedidos.get(0).ejercicio()).isNull();
    }

    @Test
    @DisplayName("con un ejercicio que no es un numero, 422 y no consulta nada")
    void conUnEjercicioQueNoEsUnNumero422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/fiscalizacion/programas")
                                        .param("ejercicio", "dos mil veintiseis"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(criteriosPedidos).isEmpty();
    }

    @Test
    @DisplayName("el orden por omision es el codigo, que es como se buscan los programas")
    void elOrdenPorOmisionEsElCodigo() throws Exception {
        mvc.perform(get("/rentas/api/v1/fiscalizacion/programas")).andReturn();

        assertThat(paginacionesPedidas).hasSize(1);
        assertThat(paginacionesPedidas.get(0).ordenarPor()).isEqualTo("codigo");
        assertThat(paginacionesPedidas.get(0).pagina()).isZero();
    }

    @Test
    @DisplayName("la paginacion de la URL llega tal cual al repositorio")
    void laPaginacionDeLaUrlLlegaTalCual() throws Exception {
        mvc.perform(
                        get("/rentas/api/v1/fiscalizacion/programas")
                                .param("pagina", "2")
                                .param("tamano", "5")
                                .param("ordenarPor", "fechaInicio")
                                .param("direccion", "DESCENDENTE"))
                .andReturn();

        assertThat(paginacionesPedidas).hasSize(1);
        assertThat(paginacionesPedidas.get(0).pagina()).isEqualTo(2);
        assertThat(paginacionesPedidas.get(0).tamano()).isEqualTo(5);
        assertThat(paginacionesPedidas.get(0).ordenarPor()).isEqualTo("fechaInicio");
        assertThat(paginacionesPedidas.get(0).direccion())
                .isEqualTo(Paginacion.Direccion.DESCENDENTE);
    }

    /**
     * El listado exige {@code LECTURA}, y la anotacion tiene que estar <b>en el metodo</b>.
     *
     * <p>Esta prueba existe porque la rotura obvia —quitarle el {@code @RequiereAcceso} al {@code
     * GET}— pasa en <b>verde</b>: la regla de ArchUnit exige la anotacion «en la clase o en cada
     * endpoint», y la clase la declara para poder programar. Sin la del metodo, el {@code GET}
     * hereda {@code REGISTRO}, de modo que quien solo tiene lectura sobre {@code fisc_programa} no
     * podria abrir la pantalla y nada lo diria hasta integrar.
     */
    @Test
    @DisplayName("el listado exige LECTURA, no el REGISTRO que la clase declara para programar")
    void elListadoExigeLectura() throws Exception {
        RequiereAcceso enElMetodo =
                ProgramasController.class
                        .getMethod(
                                "programas",
                                String.class,
                                String.class,
                                ParametrosDePaginacion.class)
                        .getAnnotation(RequiereAcceso.class);

        assertThat(enElMetodo)
                .as("sin la anotacion en el metodo el GET hereda el REGISTRO de la clase")
                .isNotNull();
        assertThat(enElMetodo.acceso()).isEqualTo("fisc_programa");
        assertThat(enElMetodo.privilegio()).isEqualTo(Privilegio.LECTURA);
    }

    // ------------------------------------------------------------------

    private static ProgramaFiscalizacion unPrograma(String codigo, TipoDePrograma tipo) {
        return new ProgramaFiscalizacion(
                1L,
                codigo,
                "Muestra de riesgo",
                tipo,
                LocalDate.of(2026, 1, 15),
                null,
                EstadoDePrograma.ABIERTO);
    }
}
