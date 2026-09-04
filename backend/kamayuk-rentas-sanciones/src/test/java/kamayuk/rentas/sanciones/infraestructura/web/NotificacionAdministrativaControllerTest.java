package kamayuk.rentas.sanciones.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Optional;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.sanciones.aplicacion.RegistrarNotificacionAdministrativa;
import kamayuk.rentas.sanciones.dominio.CriterioDeNotificacion;
import kamayuk.rentas.sanciones.dominio.EstadoDeNotificacion;
import kamayuk.rentas.sanciones.dominio.NotificacionAdministrativa;
import kamayuk.rentas.sanciones.dominio.NotificacionAdministrativaRepository;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Capa web — POST /api/v1/infracciones/administrativas/notificaciones")
class NotificacionAdministrativaControllerTest {

    private final RepositorioDeMentira repositorio = new RepositorioDeMentira();
    private final RegistrarNotificacionAdministrativa servicio =
            new RegistrarNotificacionAdministrativa(repositorio, (RegistroDeAuditoria r) -> {});

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new NotificacionAdministrativaController(servicio))
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
    @DisplayName("registra y devuelve 201, sin exigir contribuyente ni predio")
    void registraYDevuelve201() throws Exception {
        String cuerpo =
                "{\"observacion\":\"prueba\",\"numero\":\"NA-0001\",\"fecha\":\"2026-03-01\","
                        + "\"direccion\":\"Av. Grau 123\",\"motivo\":\"Falta administrativa\","
                        + "\"plazoDias\":10}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/infracciones/administrativas/notificaciones")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"numero\":\"NA-0001\"");
    }

    @Test
    @DisplayName("sin observacion, 422")
    void sinObservacion422() throws Exception {
        String cuerpo =
                "{\"numero\":\"NA-0002\",\"fecha\":\"2026-03-01\",\"direccion\":\"Av. Grau 123\","
                        + "\"motivo\":\"Falta administrativa\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/infracciones/administrativas/notificaciones")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("sin numero, 422")
    void sinNumero422() throws Exception {
        String cuerpo =
                "{\"observacion\":\"prueba\",\"fecha\":\"2026-03-01\",\"direccion\":\"Av. Grau"
                        + " 123\",\"motivo\":\"Falta administrativa\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/infracciones/administrativas/notificaciones")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("el filtro «numero» viaja por la consulta y es el que se registra (#425)")
    void elNumeroViajaPorLaConsulta() throws Exception {
        String sinNumero =
                "{\"observacion\":\"prueba\",\"fecha\":\"2026-03-01\",\"direccion\":\"Av. Grau"
                        + " 123\",\"motivo\":\"Falta administrativa\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/infracciones/administrativas/notificaciones")
                                        .param("numero", "NA-0100")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(sinNumero))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(repositorio.ultimoNumero)
                .as("no basta con que se acepte: la notificacion se registra con ESE numero")
                .isEqualTo("NA-0100");
        assertThat(resultado.getResponse().getContentAsString()).contains("\"numero\":\"NA-0100\"");
    }

    @Test
    @DisplayName("y si viene en los dos sitios gana el cuerpo: el cliente viejo sigue igual")
    void elCuerpoGanaALaConsulta() throws Exception {
        String conNumero =
                "{\"observacion\":\"prueba\",\"numero\":\"NA-0101\",\"fecha\":\"2026-03-01\","
                        + "\"direccion\":\"Av. Grau 123\",\"motivo\":\"Falta administrativa\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/infracciones/administrativas/notificaciones")
                                        .param("numero", "NA-0100")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(conNumero))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(repositorio.ultimoNumero).isEqualTo("NA-0101");
    }

    private static final class RepositorioDeMentira
            implements NotificacionAdministrativaRepository {
        private long siguiente = 1;

        /** Con que numero se guardo de verdad la ultima: lo que #425 tiene que poder mirar. */
        private @org.jspecify.annotations.Nullable String ultimoNumero;

        @Override
        public NotificacionAdministrativa insertar(NotificacionAdministrativa notificacion) {
            ultimoNumero = notificacion.numero();
            return new NotificacionAdministrativa(
                    siguiente++,
                    notificacion.numero(),
                    notificacion.fecha(),
                    notificacion.contribuyenteId(),
                    notificacion.predioId(),
                    notificacion.direccion(),
                    notificacion.motivo(),
                    notificacion.plazoDias(),
                    EstadoDeNotificacion.EMITIDA,
                    "prueba");
        }

        @Override
        public Optional<NotificacionAdministrativa> porNumero(String numero) {
            return Optional.empty();
        }

        @Override
        public Pagina<NotificacionAdministrativa> buscarVencidas(
                CriterioDeNotificacion criterio, Paginacion paginacion) {
            throw new UnsupportedOperationException("esta prueba no lista notificaciones");
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
