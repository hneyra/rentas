package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.fiscalizacion.aplicacion.RegistrarActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeProgramas;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDePrograma;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.TipoDePrograma;
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

/**
 * #45 — Capa web: se prueba el transporte, no la persistencia —eso ya lo verifica {@code
 * ActaFiscalizacionRepositoryJdbcTest} contra PostgreSQL real.
 */
@DisplayName("Capa web — POST /api/v1/fiscalizacion/predial/actas")
class ActaPredialControllerTest {

    private static final long PROGRAMA_PREDIAL = 1L;

    private final List<ActaFiscalizacion> guardadas = new ArrayList<>();
    private final RegistrarActaFiscalizacion servicio =
            new RegistrarActaFiscalizacion(
                    new ActaFiscalizacionRepository() {
                        private long siguiente = 1;

                        @Override
                        public ActaFiscalizacion insertar(ActaFiscalizacion acta) {
                            ActaFiscalizacion guardada =
                                    new ActaFiscalizacion(
                                            siguiente++,
                                            acta.programaId(),
                                            acta.version(),
                                            acta.contribuyenteId(),
                                            acta.predioId(),
                                            acta.vehiculoId(),
                                            acta.fichaId(),
                                            acta.fechaVisita(),
                                            acta.fiscalizador(),
                                            acta.hallazgo(),
                                            acta.areaHallada(),
                                            acta.usoHallado(),
                                            acta.detalle(),
                                            acta.estado(),
                                            acta.observacion());
                            guardadas.add(guardada);
                            return guardada;
                        }

                        @Override
                        public kamayuk.rentas.compartido.Pagina<ActaFiscalizacion> consultar(
                                kamayuk.rentas.fiscalizacion.dominio.CriterioDeActas criterio,
                                kamayuk.rentas.compartido.Paginacion paginacion) {
                            return kamayuk.rentas.compartido.Pagina.vacia(paginacion);
                        }

                        @Override
                        public Optional<ActaFiscalizacion> findById(long id) {
                            return guardadas.stream()
                                    .filter(acta -> acta.id() != null && acta.id() == id)
                                    .findFirst();
                        }

                        @Override
                        public int siguienteVersion(
                                long programaId,
                                long contribuyenteId,
                                @org.jspecify.annotations.Nullable Long predioId,
                                @org.jspecify.annotations.Nullable Long vehiculoId) {
                            return 1;
                        }

                        @Override
                        public java.util.Set<Long> prediosConActaEnElPrograma(
                                long programaId, java.util.Set<Long> predios) {
                            return java.util.Set.of();
                        }

                        @Override
                        public java.util.Set<Long> prediosConActaEnElEjercicio(
                                kamayuk.rentas.dominio.Ejercicio ejercicio,
                                java.util.Set<Long> predios) {
                            return java.util.Set.of();
                        }
                    },
                    new ProgramaFiscalizacionRepository() {
                        @Override
                        public ProgramaFiscalizacion insertar(ProgramaFiscalizacion programa) {
                            throw new UnsupportedOperationException(
                                    "esta prueba no escribe programas");
                        }

                        @Override
                        public Optional<ProgramaFiscalizacion> findById(long id) {
                            return id == PROGRAMA_PREDIAL
                                    ? Optional.of(
                                            new ProgramaFiscalizacion(
                                                    PROGRAMA_PREDIAL,
                                                    "PF-001",
                                                    "Muestra predial",
                                                    TipoDePrograma.PREDIAL,
                                                    LocalDate.of(2026, 1, 1),
                                                    null,
                                                    EstadoDePrograma.ABIERTO))
                                    : Optional.empty();
                        }

                        @Override
                        public Pagina<ProgramaFiscalizacion> consultar(
                                CriterioDeProgramas criterio, Paginacion paginacion) {
                            throw new UnsupportedOperationException(
                                    "esta prueba no consulta la grilla de programas");
                        }
                    },
                    new LectorDeFichas() {
                        @Override
                        public Optional<Long> fichaVigenteEn(long predioId, LocalDate fecha) {
                            return Optional.of(700L);
                        }

                        @Override
                        public Optional<kamayuk.rentas.dominio.AreaM2> areaDeLaVersion(
                                long fichaId) {
                            return Optional.of(kamayuk.rentas.dominio.AreaM2.de("120.00"));
                        }
                    },
                    (RegistroDeAuditoria registro) -> {});

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new ActaPredialController(servicio))
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
    @DisplayName("registra el acta y devuelve 201 con la ficha resuelta")
    void registraElActaYDevuelve201() throws Exception {
        String cuerpo =
                "{\"observacion\":\"Se fiscaliza para la prueba\",\"programaId\":1,"
                        + "\"contribuyenteId\":10,\"predioId\":20,\"fechaVisita\":\"2026-03-15\","
                        + "\"fiscalizador\":\"J. Perez\",\"hallazgo\":\"CONFORME\","
                        + "\"areaHallada\":\"120.50\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/predial/actas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"fichaId\":700")
                .contains("\"hallazgo\":\"CONFORME\"")
                .contains("\"predioId\":20");
    }

    @Test
    @DisplayName("sin hallazgo, 422 y no guarda nada: lo que se perdia era NO_UBICADO (D-16)")
    void sinHallazgoNoSeRegistra() throws Exception {
        String cuerpo =
                "{\"observacion\":\"Se fiscaliza para la prueba\",\"programaId\":1,"
                        + "\"contribuyenteId\":10,\"predioId\":20,\"fechaVisita\":\"2026-03-15\","
                        + "\"fiscalizador\":\"J. Perez\",\"areaHallada\":\"120.50\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/predial/actas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "en la predial la condicion sale de comparar superficies, asi que un acta"
                                + " sin hallazgo compara un PREDIO INEXISTENTE por area como si se"
                                + " hubiera hallado")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("hallazgo");
        assertThat(guardadas).isEmpty();
    }

    @Test
    @DisplayName("#599 — el uso hallado viaja en el cuerpo, se guarda y sale en la respuesta")
    void elUsoHalladoViajaEnElCuerpo() throws Exception {
        String cuerpo =
                "{\"observacion\":\"Se fiscaliza para la prueba\",\"programaId\":1,"
                        + "\"contribuyenteId\":10,\"predioId\":20,\"fechaVisita\":\"2026-03-15\","
                        + "\"fiscalizador\":\"J. Perez\",\"hallazgo\":\"USO_DISTINTO\","
                        + "\"areaHallada\":\"120.50\",\"usoHallado\":\"COMERCIO\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/predial/actas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"hallazgo\":\"USO_DISTINTO\"")
                .contains("\"usoHallado\":\"COMERCIO\"");
        assertThat(guardadas).hasSize(1);
        assertThat(guardadas.get(0).usoHallado())
                .as("sin el campo en la lista blanca, Jackson lo descarta sin decir nada (#538)")
                .isEqualTo("COMERCIO");
    }

    @Test
    @DisplayName("#599 — USO_DISTINTO sin el uso observado es 422, y no guarda nada")
    void usoDistintoSinUsoObservadoEs422() throws Exception {
        String cuerpo =
                "{\"observacion\":\"Se fiscaliza para la prueba\",\"programaId\":1,"
                        + "\"contribuyenteId\":10,\"predioId\":20,\"fechaVisita\":\"2026-03-15\","
                        + "\"fiscalizador\":\"J. Perez\",\"hallazgo\":\"USO_DISTINTO\","
                        + "\"areaHallada\":\"120.50\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/predial/actas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("USO_DISTINTO");
        assertThat(guardadas).isEmpty();
    }

    @Test
    @DisplayName("contra un programa que no existe, 422 y no guarda nada")
    void contraUnProgramaQueNoExiste422() throws Exception {
        String cuerpo =
                "{\"observacion\":\"Se fiscaliza para la prueba\",\"programaId\":999,"
                        + "\"contribuyenteId\":10,\"predioId\":20,\"fechaVisita\":\"2026-03-15\","
                        + "\"fiscalizador\":\"J. Perez\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/predial/actas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(guardadas).isEmpty();
    }

    @Test
    @DisplayName("sin observacion, 422")
    void sinObservacion422() throws Exception {
        String cuerpo =
                "{\"programaId\":1,\"contribuyenteId\":10,\"predioId\":20,"
                        + "\"fechaVisita\":\"2026-03-15\",\"fiscalizador\":\"J. Perez\"}";

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/predial/actas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
    }
}
