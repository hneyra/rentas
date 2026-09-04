package kamayuk.rentas.sanciones.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #43 — Capa web: se prueba el transporte (forma del JSON, filtro de familia), no la persistencia —
 * eso ya lo verifica {@code CodigoInfraccionRepositoryJdbcTest} contra PostgreSQL real.
 */
@DisplayName("Capa web — GET /api/v1/transito/codigos")
class CodigosTransitoControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);

    private final RepositorioDeMentira repositorio = new RepositorioDeMentira();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new CodigosTransitoController(
                                    new ConsultasDeSanciones(null, repositorio, null, null), RELOJ))
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
    @DisplayName("filtra siempre por la familia TRANSITO")
    void filtraSiempreLaFamiliaTransito() throws Exception {
        mvc.perform(get("/rentas/api/v1/transito/codigos")).andReturn();

        assertThat(repositorio.ultimoCriterio.familia()).isEqualTo(Familia.TRANSITO);
    }

    @Test
    @DisplayName("sin fecha, consulta la vigente hoy segun el reloj inyectado")
    void sinFechaConsultaLaVigenteHoy() throws Exception {
        mvc.perform(get("/rentas/api/v1/transito/codigos")).andReturn();

        assertThat(repositorio.ultimoCriterio.vigenteA()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    @DisplayName("con fecha, consulta la vigente esa fecha, no la de hoy")
    void conFechaConsultaLaVigenteEsaFecha() throws Exception {
        mvc.perform(get("/rentas/api/v1/transito/codigos").param("fecha", "2025-01-10"))
                .andReturn();

        assertThat(repositorio.ultimoCriterio.vigenteA()).isEqualTo(LocalDate.of(2025, 1, 10));
    }

    @Test
    @DisplayName("devuelve la pagina en la forma unica, con campos en español camelCase")
    void devuelveLaPaginaEnLaFormaUnica() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/transito/codigos")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"contenido\"")
                .contains("\"codigo\":\"G-01\"")
                .contains("\"porcentajeUit\":\"8\"")
                .doesNotContain("municipalidad");
    }

    private static final class RepositorioDeMentira implements CodigoInfraccionRepository {

        private CriterioDeCodigoInfraccion ultimoCriterio;

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
            this.ultimoCriterio = criterio;
            CodigoInfraccion codigo =
                    CodigoInfraccion.nuevo(
                            criterio.familia(),
                            "G-01",
                            "Exceso de velocidad",
                            Alicuota.de("8"),
                            null,
                            null,
                            "RNT art. 300",
                            LocalDate.of(2026, 1, 1));
            return Pagina.de(java.util.List.of(codigo), paginacion, 1);
        }

        @Override
        public CodigoInfraccion insertar(CodigoInfraccion codigoInfraccion) {
            throw new UnsupportedOperationException("esta prueba no escribe");
        }

        @Override
        public CodigoInfraccion actualizar(CodigoInfraccion codigoInfraccion) {
            throw new UnsupportedOperationException("esta prueba no escribe");
        }
    }
}
