package kamayuk.rentas.rentas.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.rentas.aplicacion.ConsultasDeRentas;
import kamayuk.rentas.rentas.dominio.arbitrios.CriterioDeArbitrio;
import kamayuk.rentas.rentas.dominio.arbitrios.CuotaDeArbitrio;
import kamayuk.rentas.rentas.dominio.arbitrios.CuotaDeArbitrioRepository;
import kamayuk.rentas.rentas.dominio.arbitrios.Servicio;
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
 * #31 — Capa web: se prueba el transporte (forma del JSON, resolucion del ejercicio), no la
 * persistencia —eso ya lo verifica {@code CuotaDeArbitrioRepositoryJdbcTest} contra PostgreSQL
 * real.
 */
@DisplayName("Capa web — GET /api/v1/rentas/arbitrios")
class ArbitriosControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);

    private final RepositorioDeMentira repositorio = new RepositorioDeMentira();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ArbitriosController(
                                    new ConsultasDeRentas(repositorio, null, null, null), RELOJ))
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
    @DisplayName("sin anio, consulta el ejercicio de hoy segun el reloj inyectado")
    void sinAnioConsultaElEjercicioDeHoy() throws Exception {
        mvc.perform(get("/rentas/api/v1/rentas/arbitrios")).andReturn();

        assertThat(repositorio.ultimoCriterio.ejercicio()).isEqualTo(new Ejercicio(2026));
    }

    @Test
    @DisplayName("con anio, consulta ese ejercicio, no el de hoy")
    void conAnioConsultaEseEjercicio() throws Exception {
        mvc.perform(get("/rentas/api/v1/rentas/arbitrios").param("anio", "2025")).andReturn();

        assertThat(repositorio.ultimoCriterio.ejercicio()).isEqualTo(new Ejercicio(2025));
    }

    @Test
    @DisplayName("con codigoPredial, lo traslada al criterio")
    void conCodigoPredialLoTrasladaAlCriterio() throws Exception {
        mvc.perform(get("/rentas/api/v1/rentas/arbitrios").param("codigoPredial", "abc-001"))
                .andReturn();

        assertThat(repositorio.ultimoCriterio.codigoPredial()).isEqualTo("ABC-001");
    }

    @Test
    @DisplayName("devuelve la pagina en la forma unica, con campos en español camelCase")
    void devuelveLaPaginaEnLaFormaUnica() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/rentas/arbitrios")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"contenido\"")
                .contains("\"servicio\":\"LIMPIEZA_PUBLICA\"")
                .contains("\"monto\":\"8.50\"")
                .doesNotContain("municipalidad");
    }

    @Test
    @DisplayName("con ejercicio —el filtro que la pantalla dibuja—, consulta ese ejercicio")
    void conEjercicioConsultaEseEjercicio() throws Exception {
        mvc.perform(get("/rentas/api/v1/rentas/arbitrios").param("ejercicio", "2027")).andReturn();

        assertThat(repositorio.ultimoCriterio.ejercicio()).isEqualTo(new Ejercicio(2027));
    }

    @Test
    @DisplayName("cuando vienen los dos nombres manda «ejercicio», que es el canonico")
    void elEjercicioGanaAlAnio() throws Exception {
        mvc.perform(
                        get("/rentas/api/v1/rentas/arbitrios")
                                .param("ejercicio", "2027")
                                .param("anio", "2025"))
                .andReturn();

        assertThat(repositorio.ultimoCriterio.ejercicio()).isEqualTo(new Ejercicio(2027));
    }

    @Test
    @DisplayName("un ejercicio que no es un ano se rechaza con 422, no con 500")
    void unEjercicioQueNoEsUnAnoSeRechaza() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/rentas/arbitrios").param("ejercicio", "dos mil"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("ejercicio");
    }

    /**
     * <b>Los dos filtros que esta consulta no puede servir</b> (#541).
     *
     * <p>Antes se tecleaban, viajaban y la tabla volvia igual. Ahora se rechazan diciendo por que:
     * los valores que el desplegable ofrece —«Zona 1»…«Zona 4» y cinco usos en mayusculas— no
     * existen en el sistema, porque la zona sale del sector y el uso de la ficha catastral, los dos
     * texto libre por municipalidad. Es lo que ya hacen `ConsultaController` con «Conciliada con
     * rentas» (#322) y los dos resumenes de transito (#398).
     */
    @Test
    @DisplayName("«zona» se rechaza con 422 y dice por que, en vez de devolver todo")
    void laZonaSeRechaza() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/rentas/arbitrios").param("zona", "Zona 1"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("zona")
                .contains("codigoPredial");
        assertThat(repositorio.ultimoCriterio)
                .as("y no se llega a consultar: la peticion no pasa del borde")
                .isNull();
    }

    @Test
    @DisplayName("«uso» tambien, y por el mismo motivo")
    void elUsoSeRechaza() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/rentas/arbitrios").param("uso", "COMERCIO"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("uso");
    }

    @Test
    @DisplayName("un «zona» vacio no es un filtro: no se rechaza nada")
    void laZonaVaciaNoEsUnFiltro() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/rentas/arbitrios").param("zona", "")).andReturn();

        // `?zona=` no significa «zona en blanco»: es el control sin elegir, y la interfaz no
        // manda los filtros vacios. Rechazarlo dejaria la pantalla sin poder abrirse.
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
    }

    private static final class RepositorioDeMentira implements CuotaDeArbitrioRepository {

        private CriterioDeArbitrio ultimoCriterio;

        @Override
        public boolean existe(long predioId, Servicio servicio, Ejercicio ejercicio, int periodo) {
            return false;
        }

        @Override
        public CuotaDeArbitrio insertar(CuotaDeArbitrio cuota) {
            throw new UnsupportedOperationException("esta prueba no escribe");
        }

        @Override
        public Pagina<CuotaDeArbitrio> buscar(CriterioDeArbitrio criterio, Paginacion paginacion) {
            this.ultimoCriterio = criterio;
            CuotaDeArbitrio cuota =
                    CuotaDeArbitrio.nueva(
                            criterio.ejercicio(),
                            Servicio.LIMPIEZA_PUBLICA,
                            1,
                            1L,
                            1L,
                            1L,
                            Dinero.de("8.50"),
                            "TASA_LIMPIEZA_PUBLICA:S-01:CASA_HABITACION",
                            LocalDate.of(2026, 1, 31));
            return Pagina.de(List.of(cuota), paginacion, 1);
        }
    }
}
