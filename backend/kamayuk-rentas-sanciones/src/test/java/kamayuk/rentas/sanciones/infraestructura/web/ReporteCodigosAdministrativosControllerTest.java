package kamayuk.rentas.sanciones.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.sanciones.aplicacion.ConsultasDeSanciones;
import kamayuk.rentas.sanciones.dominio.CodigoInfraccion;
import kamayuk.rentas.sanciones.dominio.CodigoInfraccionRepository;
import kamayuk.rentas.sanciones.dominio.CriterioDeCodigoInfraccion;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/** #43 — capa web del reporte impreso del CUIS. */
@DisplayName("Capa web — GET /api/v1/infracciones/administrativas/codigos/reporte")
class ReporteCodigosAdministrativosControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);

    private CriterioDeCodigoInfraccion ultimoCriterio;

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ReporteCodigosAdministrativosController(
                                    new ConsultasDeSanciones(
                                            null, repositorioDeMentira(), null, null),
                                    RELOJ))
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
    @DisplayName("filtra siempre por la familia ADMINISTRATIVA")
    void filtraSiempreLaFamiliaAdministrativa() throws Exception {
        mvc.perform(get("/rentas/api/v1/infracciones/administrativas/codigos/reporte")).andReturn();

        assertThat(ultimoCriterio.familia()).isEqualTo(Familia.ADMINISTRATIVA);
    }

    @Test
    @DisplayName("sin fecha, reporta la vigente hoy segun el reloj inyectado")
    void sinFechaReportaLaVigenteHoy() throws Exception {
        mvc.perform(get("/rentas/api/v1/infracciones/administrativas/codigos/reporte")).andReturn();

        assertThat(ultimoCriterio.vigenteA()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    /**
     * #422 — Una fecha con barras es 422 que nombra el parametro, y no el 500 de {@code
     * DateTimeParseException}.
     *
     * <p>{@code DateTimeParseException} no es una {@code IllegalArgumentException}, asi que hasta
     * #422 ningun manejador la reconocia y caia en el de «cualquier otra»: 500 con incidencia ERROR
     * por una fecha tecleada como la escribe cualquiera en el Peru.
     */
    @Test
    @DisplayName("#422 — una fecha dd/MM/aaaa es 422, no consulta nada y no deja incidencia")
    void unaFechaConBarrasEs422() throws Exception {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> anotados =
                new ch.qos.logback.core.read.ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        org.springframework.test.web.servlet.MvcResult resultado;
        try {
            resultado =
                    mvc.perform(
                                    get("/rentas/api/v1/infracciones/administrativas/codigos/reporte")
                                            .param("fecha", "14/08/2026"))
                            .andReturn();
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("VALIDACION")
                .contains("fecha")
                .contains("14/08/2026")
                .doesNotContain("incidencia");
        assertThat(
                        anotados.list.stream()
                                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                                .toList())
                .as("un rechazo del usuario no es una incidencia del servidor")
                .isEmpty();
        assertThat(ultimoCriterio).as("y no se llego a consultar").isNull();
    }

    private CodigoInfraccionRepository repositorioDeMentira() {
        return new CodigoInfraccionRepository() {
            @Override
            public Optional<CodigoInfraccion> findById(long id) {
                return Optional.empty();
            }

            @Override
            public Optional<CodigoInfraccion> vigenteA(
                    Familia familia, String codigo, LocalDate fecha) {
                return Optional.empty();
            }

            @Override
            public Pagina<CodigoInfraccion> buscar(
                    CriterioDeCodigoInfraccion criterio, Paginacion paginacion) {
                ultimoCriterio = criterio;
                CodigoInfraccion codigo =
                        CodigoInfraccion.nuevo(
                                criterio.familia(),
                                "CUIS-01",
                                "Comercio sin licencia",
                                Alicuota.de("15"),
                                null,
                                null,
                                "Ordenanza 001-2026",
                                LocalDate.of(2026, 1, 1));
                return Pagina.de(List.of(codigo), paginacion, 1);
            }

            @Override
            public CodigoInfraccion insertar(CodigoInfraccion codigoInfraccion) {
                throw new UnsupportedOperationException("esta prueba no escribe");
            }

            @Override
            public CodigoInfraccion actualizar(CodigoInfraccion codigoInfraccion) {
                throw new UnsupportedOperationException("esta prueba no escribe");
            }
        };
    }
}
