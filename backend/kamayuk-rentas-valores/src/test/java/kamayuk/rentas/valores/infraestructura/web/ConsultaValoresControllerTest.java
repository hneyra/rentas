package kamayuk.rentas.valores.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.aplicacion.ConsultaDeValores;
import kamayuk.rentas.valores.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.valores.dobles.ValoresEnMemoria;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
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
 * {@code consulta_valores} por HTTP de verdad y sin base de datos (RF-041, #25).
 *
 * <p>Lo que se verifica aqui es lo que la base no puede decir: la forma del JSON —que toda cifra
 * salga con su fecha—, y sobre todo que un filtro que el dominio no sabe responder <b>se
 * rechace</b> en vez de ignorarse. Lo que si depende de la base —que el filtro por situacion traiga
 * exactamente las filas cuya columna «Estado» dice eso— tiene sus pruebas en {@code
 * NotificacionYPaseJdbcTest}, contra PostgreSQL.
 */
@DisplayName("Capa web — GET /api/v1/consultas/valores")
class ConsultaValoresControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 5, 20);
    private static final LocalDate EMISION = LocalDate.of(2026, 3, 2);

    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private final ValoresEnMemoria repositorio = new ValoresEnMemoria();
    private final ContribuyentesDeMentira contribuyentes = new ContribuyentesDeMentira();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ConsultaValoresController(
                                    new ConsultaDeValores(repositorio, contribuyentes), RELOJ))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    ConsultaValoresControllerTest() {
        contribuyentes.con(
                new ResumenDeContribuyente(7L, "C-000007", "PEÑA GARCIA, LUIS", "40700007"));
        // Esta persona SI esta en el padron y no tiene ni un valor emitido: es el unico
        // caso en que «cero filas» significa de verdad «no tiene» (#622).
        contribuyentes.con(
                new ResumenDeContribuyente(8L, "C-000008", "SIN VALORES, ANA", "40800008"));
        repositorio.con(
                valor("OP-2026-000001", TipoValor.ORDEN_DE_PAGO, EstadoDeValor.EMITIDO),
                detalle(2026));
        repositorio.con(
                valor(
                        "RD-2026-000002",
                        TipoValor.RESOLUCION_DE_DETERMINACION,
                        EstadoDeValor.NOTIFICADO),
                detalle(2026));
    }

    @Test
    @DisplayName("devuelve la pagina en la forma unica, con campos en español camelCase")
    void devuelveLaPaginaEnLaFormaUnica() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/consultas/valores")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"contenido\"")
                .contains("\"totalElementos\":2")
                .contains("\"numero\":\"OP-2026-000001\"")
                .contains("\"tipo\":\"OP\"")
                .contains("\"contribuyente\":\"PEÑA GARCIA, LUIS\"");
    }

    @Test
    @DisplayName("el monto viaja con su fecha, y esa fecha es la de proyeccion, no la de hoy")
    void elMontoViajaConSuFechaDeProyeccion() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/consultas/valores")).andReturn();

        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "el desglose de un valor esta congelado (AC de #37): decir que esta"
                                + " actualizado a hoy convertiria un documento notificado en una"
                                + " cifra que cambia sola")
                .contains("\"actualizadoA\":\"" + EMISION + "\"")
                .doesNotContain("\"actualizadoA\":\"" + HOY + "\"");
    }

    @Test
    @DisplayName("la situacion dice a que fecha se miro (regla 9)")
    void laSituacionDiceAQueFechaSeMiro() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/consultas/valores")).andReturn();

        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "sin situacionA, «NOTIFICADO» seria una afirmacion sin fecha, y manana"
                                + " podria ser otra cosa sin que nada en la fila lo explique")
                .contains("\"situacionA\":\"" + HOY + "\"");
    }

    @Test
    @DisplayName("el tributo y el periodo salen agregados desde el servidor")
    void elTributoYElPeriodoSalenAgregados() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/consultas/valores")).andReturn();

        assertThat(resultado.getResponse().getContentAsString())
                .as("la interfaz no compone la columna «Tributo» ni la de «Periodo» (RNF-083)")
                .contains("\"tributo\":\"PREDIAL\"")
                .contains("\"periodo\":\"2026\"");
    }

    @Test
    @DisplayName("«RECLAMADO» es 422 con su motivo, no un listado sin filtrar")
    void reclamadoEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/consultas/valores").param("estado", "RECLAMADO"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "ignorar el filtro devolveria todos los valores y quien lo mira creeria"
                                + " estar viendo solo los reclamados")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("reclamacion");
    }

    @Test
    @DisplayName("«FIRME» se acepta: es como el prototipo llama a EXIGIBLE")
    void firmeSeAcepta() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/consultas/valores").param("estado", "FIRME"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .as("ninguno de los dos valores del doble esta notificado con acuse")
                .contains("\"totalElementos\":0");
    }

    @Test
    @DisplayName("«Todos» del desplegable no filtra nada, ni falla")
    void todosNoFiltra() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/consultas/valores")
                                        .param("estado", "Todos")
                                        .param("tipo", "Todos"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"totalElementos\":2");
    }

    @Test
    @DisplayName("el tipo llega como lo escribe el desplegable, con tilde y todo")
    void elTipoLlegaConTilde() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/consultas/valores")
                                        .param("tipo", "RES. DETERMINACIÓN"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .as("comparar con la tilde tal cual dejaria el desplegable sin encontrar nada")
                .contains("\"totalElementos\":1")
                .contains("RD-2026-000002");
    }

    @Test
    @DisplayName("un tipo que no existe es 422, sin nombrar columnas")
    void unTipoDesconocidoEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/consultas/valores").param("tipo", "MARCIANO"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("OP")
                .doesNotContain("valor_detalle");
    }

    @Test
    @DisplayName("un codigo de contribuyente que no existe NO devuelve el listado entero")
    void unCodigoInexistenteNoDevuelveTodo() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/consultas/valores")
                                        .param("codContribuyente", "C-999999"))
                        .andReturn();

        // Lo que esta prueba defiende desde #25 sigue en pie: ignorar el filtro devolveria
        // los valores de toda la municipalidad y quien mira creeria estar viendo los de esa
        // persona. Lo que #622 cambia es la OTRA mitad: la respuesta ya no es 200 con cero
        // filas —que afirma que la persona existe y no tiene valores— sino el 404 que las
        // otras seis lecturas del mismo expediente contestan.
        assertThat(resultado.getResponse().getContentAsString()).doesNotContain("OP-2026-000001");
        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("no devuelve la municipalidad, porque no la conoce ni la necesita")
    void noDevuelveLaMunicipalidad() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/consultas/valores")).andReturn();

        assertThat(resultado.getResponse().getContentAsString())
                .as("el identificador de municipalidad no sale ni entra por HTTP (ADR-0005)")
                .doesNotContain("municipalidad");
    }

    @Test
    @DisplayName("#622 — un codigo que no esta en el padron es 404, no cero filas")
    void codigoInventadoEs404() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/consultas/valores")
                                        .param("codContribuyente", "C-NO-EXISTE"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "el expediente de Consultas pide siete lecturas con el mismo codigo; con"
                                + " `Pagina.vacia` esta afirmaba que la persona existe y no tiene"
                                + " valores, debajo de otra que decia que no existe")
                .isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString()).contains("C-NO-EXISTE");
    }

    @Test
    @DisplayName("#622 — quien SI esta en el padron y no tiene valores sigue siendo 200 con cero")
    void elQueExisteYNoTieneValoresSigueSiendoCeroFilas() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/consultas/valores")
                                        .param("codContribuyente", "C-000008"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("sin este caso, un 404 lanzado tambien con la lista vacia pasaria en verde")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"totalElementos\":0");
    }

    // ------------------------------------------------------------------

    private static Valor valor(String numero, TipoValor tipo, EstadoDeValor estado) {
        return new Valor(
                null,
                tipo,
                numero,
                new Ejercicio(2026),
                7L,
                tipo.baseLegal(),
                Dinero.de("500.00"),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO,
                EMISION,
                estado,
                EMISION,
                null,
                Observacion.de("Se emite para la prueba del transporte"));
    }

    private static ValorDetalle detalle(int ejercicio) {
        return ValorDetalle.nuevo(
                "PREDIAL",
                new Ejercicio(ejercicio),
                null,
                null,
                null,
                null,
                Dinero.de("500.00"),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO);
    }
}
