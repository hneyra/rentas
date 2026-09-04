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
import kamayuk.rentas.sanciones.aplicacion.ConsultasDeSanciones;
import kamayuk.rentas.sanciones.dominio.CriterioDeNotificacion;
import kamayuk.rentas.sanciones.dominio.NotificacionAdministrativa;
import kamayuk.rentas.sanciones.dominio.NotificacionAdministrativaRepository;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Capa web — GET /api/v1/infracciones/administrativas/reportes/vencidas")
class NotificacionesVencidasControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);

    private final RepositorioDeMentira repositorio = new RepositorioDeMentira();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new NotificacionesVencidasController(
                                    new ConsultasDeSanciones(null, null, null, repositorio), RELOJ))
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
    @DisplayName("sin vencidasAl, consulta contra hoy segun el reloj inyectado")
    void sinVencidasAlConsultaContraHoy() throws Exception {
        mvc.perform(get("/rentas/api/v1/infracciones/administrativas/reportes/vencidas"))
                .andReturn();

        assertThat(repositorio.ultimoCriterio.vencidasAl()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    @DisplayName("con vencidasAl, consulta contra esa fecha, no la de hoy")
    void conVencidasAlConsultaEsaFecha() throws Exception {
        mvc.perform(
                        get("/rentas/api/v1/infracciones/administrativas/reportes/vencidas")
                                .param("vencidasAl", "2026-01-01"))
                .andReturn();

        assertThat(repositorio.ultimoCriterio.vencidasAl()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    @DisplayName("traslada fiscalizador y conPapeleta al criterio")
    void trasladaFiscalizadorYConPapeleta() throws Exception {
        mvc.perform(
                        get("/rentas/api/v1/infracciones/administrativas/reportes/vencidas")
                                .param("fiscalizador", "inspector.uno")
                                .param("conPapeleta", "true"))
                .andReturn();

        assertThat(repositorio.ultimoCriterio.registradoPor()).isEqualTo("INSPECTOR.UNO");
        assertThat(repositorio.ultimoCriterio.conPapeleta()).isTrue();
    }

    private static final class RepositorioDeMentira
            implements NotificacionAdministrativaRepository {
        private CriterioDeNotificacion ultimoCriterio;

        @Override
        public NotificacionAdministrativa insertar(NotificacionAdministrativa notificacion) {
            throw new UnsupportedOperationException("esta prueba no escribe");
        }

        @Override
        public Optional<NotificacionAdministrativa> porNumero(String numero) {
            return Optional.empty();
        }

        @Override
        public Pagina<NotificacionAdministrativa> buscarVencidas(
                CriterioDeNotificacion criterio, Paginacion paginacion) {
            this.ultimoCriterio = criterio;
            NotificacionAdministrativa notificacion =
                    NotificacionAdministrativa.emitida(
                            "NA-0001",
                            LocalDate.of(2026, 1, 1),
                            null,
                            null,
                            "Av. Grau 123",
                            "Falta administrativa",
                            (short) 10);
            return Pagina.de(List.of(notificacion), paginacion, 1);
        }

        @Override
        public kamayuk.rentas.compartido.Pagina<
                        kamayuk.rentas.sanciones.dominio.NotificacionDelPadron>
                buscarPadron(
                        kamayuk.rentas.sanciones.dominio.CriterioDelPadronDeNotificaciones criterio,
                        Paginacion paginacion) {
            // #53 aniade el padron de notificaciones al puerto. Este doble no lo ejerce:
            // lo verifica SancionesDeReportesJdbcTest contra PostgreSQL, que es donde el
            // LEFT JOIN con la papeleta significa algo.
            throw new UnsupportedOperationException("Este doble no sirve el padron de #53");
        }

        @Override
        public NotificacionAdministrativa subsanar(long notificacionId) {
            throw new UnsupportedOperationException("esta prueba no subsana");
        }
    }
}
