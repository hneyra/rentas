package kamayuk.rentas.sanciones.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.sanciones.aplicacion.ConsultasDeSanciones;
import kamayuk.rentas.sanciones.dominio.CriterioDelProcedimiento;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.FaseDelProcedimiento;
import kamayuk.rentas.sanciones.dominio.ProcedimientoSancionador;
import kamayuk.rentas.sanciones.dominio.ProcedimientoSancionadorRepository;
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
 * Capa web de {@code infracciones_adm} (#397).
 *
 * <p>Lo que se comprueba aquí es la frontera: que los <b>cuatro</b> filtros de la pantalla lleguen
 * al criterio —el cuarto, «Estado», es el que no existía—, que un valor que no sea una de las cinco
 * fases se rechace con 422 diciendo cuáles admite, y que la respuesta publique los dos vocabularios
 * con dos nombres distintos. Que la fase se derive bien de los hechos se verifica contra PostgreSQL
 * en {@code ProcedimientoSancionadorRepositoryJdbcTest}, que es donde significa algo.
 */
@DisplayName("Capa web — GET /api/v1/infracciones/actas")
class InfraccionesAdministrativasControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-13T09:00:00Z"), ZoneOffset.UTC);

    private final RepositorioDeMentira repositorio = new RepositorioDeMentira();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new InfraccionesAdministrativasController(
                                    new ConsultasDeSanciones(null, null, repositorio, null), RELOJ))
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
    @DisplayName("traslada los CUATRO filtros de la pantalla al criterio, «Estado» incluido")
    void trasladaLosCuatroFiltrosAlCriterio() throws Exception {
        mvc.perform(
                        get("/rentas/api/v1/infracciones/actas")
                                .param("nroDeActa", "AC-2026-0912")
                                .param("administrado", "20525118447")
                                .param("codigoCuis", "C-101")
                                .param("estado", "SANCIONADA"))
                .andReturn();

        assertThat(repositorio.ultimoCriterio.nroDeActa()).isEqualTo("AC-2026-0912");
        assertThat(repositorio.ultimoCriterio.administrado()).isEqualTo("20525118447");
        assertThat(repositorio.ultimoCriterio.codigoCuis()).isEqualTo("C-101");
        assertThat(repositorio.ultimoCriterio.fase()).isEqualTo(FaseDelProcedimiento.SANCIONADA);
    }

    @Test
    @DisplayName("sin «Estado» no filtra por fase: es lo que significa «Todos»")
    void sinEstadoNoFiltraPorFase() throws Exception {
        mvc.perform(get("/rentas/api/v1/infracciones/actas")).andReturn();

        assertThat(repositorio.ultimoCriterio.fase()).isNull();
    }

    @Test
    @DisplayName("la fecha de la fase sale del reloj inyectado, no del reloj de la maquina")
    void laFechaDeLaFaseSaleDelRelojInyectado() throws Exception {
        mvc.perform(get("/rentas/api/v1/infracciones/actas")).andReturn();

        assertThat(repositorio.ultimoCriterio.aLaFecha()).isEqualTo(LocalDate.of(2026, 8, 13));
    }

    @Test
    @DisplayName("un «Estado» que no es una de las cinco fases es 422, y dice cuales admite")
    void unEstadoDesconocidoEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/infracciones/actas").param("estado", "Todos"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("PREVENTIVA")
                .contains("CONSTATADA")
                .contains("SANCIONADA");
    }

    @Test
    @DisplayName("el estado del PROCEDIMIENTO y el de la DEUDA salen con dos nombres distintos")
    void losDosEstadosSalenConNombresDistintos() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/infracciones/actas")).andReturn();
        String cuerpo = resultado.getResponse().getContentAsString();

        // Los dos, cada uno con su nombre. Y NINGUN campo llamado «estado» a
        // secas: es lo que impide que una pantalla dibuje uno bajo el rotulo del
        // otro sin enterarse (RNF-080).
        assertThat(cuerpo).contains("\"fase\":\"CONSTATADA\"");
        assertThat(cuerpo).contains("\"estadoDeLaDeuda\":\"IMPUESTA\"");
        assertThat(cuerpo).doesNotContain("\"estado\":");
    }

    @Test
    @DisplayName("la multa viaja con la fecha del acta, y la fase con la suya")
    void cadaCifraConSuFecha() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/infracciones/actas")).andReturn();
        String cuerpo = resultado.getResponse().getContentAsString();

        assertThat(cuerpo).contains("\"importeAPagar\":\"2675.00\"");
        // La del acta, congelada: no la de hoy.
        assertThat(cuerpo).contains("\"actualizadoA\":\"2026-03-01\"");
        assertThat(cuerpo).contains("\"faseAlDia\":\"2026-08-13\"");
    }

    @Test
    @DisplayName(
            "una fila cuya fase no nombra ninguna palabra del manual sale vacia, no «la mas"
                    + " parecida»")
    void unaFilaSinFaseSaleVacia() throws Exception {
        repositorio.sinFase = true;

        MvcResult resultado = mvc.perform(get("/rentas/api/v1/infracciones/actas")).andReturn();

        assertThat(resultado.getResponse().getContentAsString()).contains("\"fase\":null");
    }

    private static final class RepositorioDeMentira implements ProcedimientoSancionadorRepository {
        private CriterioDelProcedimiento ultimoCriterio;
        private boolean sinFase;

        @Override
        public Pagina<ProcedimientoSancionador> buscar(
                CriterioDelProcedimiento criterio, Paginacion paginacion) {
            this.ultimoCriterio = criterio;
            ProcedimientoSancionador fila =
                    new ProcedimientoSancionador(
                            7L,
                            "AC-2026-0912",
                            "NOBLECILLA ARISMENDIZ SAC",
                            "C-101",
                            "Funcionar sin licencia municipal",
                            Alicuota.de("50"),
                            Dinero.de("2675.00"),
                            LocalDate.of(2026, 3, 1),
                            "Clausura temporal",
                            sinFase ? null : FaseDelProcedimiento.CONSTATADA,
                            criterio.aLaFecha(),
                            EstadoDePapeleta.IMPUESTA);
            return Pagina.de(List.of(fila), paginacion, 1);
        }
    }
}
