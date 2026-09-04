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

/** #43 — capa web del cuadro único de infracciones y sanciones (CUIS). */
@DisplayName("Capa web — GET /api/v1/infracciones/cuis")
class CodigosCuisControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);

    private CriterioDeCodigoInfraccion ultimoCriterio;

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new CodigosCuisController(
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
    @DisplayName("filtra siempre por la familia ADMINISTRATIVA, nunca TRANSITO")
    void filtraSiempreLaFamiliaAdministrativa() throws Exception {
        mvc.perform(get("/rentas/api/v1/infracciones/cuis")).andReturn();

        assertThat(ultimoCriterio.familia()).isEqualTo(Familia.ADMINISTRATIVA);
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
