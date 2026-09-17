package kamayuk.rentas.sanciones.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeActosDeLaPapeleta;
import kamayuk.rentas.sanciones.aplicacion.ConsultaDeInternamientos;
import kamayuk.rentas.sanciones.dominio.ActoDeLaPapeleta;
import kamayuk.rentas.sanciones.dominio.AcuseDelActo;
import kamayuk.rentas.sanciones.dominio.CriterioDeInternamiento;
import kamayuk.rentas.sanciones.dominio.EstadoDeInternamiento;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.InternamientoEnConsulta;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #185 — Las dos columnas que ninguna operación llenaba viajan por HTTP: el estado del acto y la
 * clase del vehículo internado.
 *
 * <h2>Por qué se mira el JSON y no los {@code record}</h2>
 *
 * <p>Porque lo que le faltaba a {@code tra-pap} y a {@code tra-veh} no era un dato del dominio: el
 * estado se podía derivar y la categoría estaba en el padrón. Lo que faltaba era que <b>viajaran
 * por el cable</b>. Una prueba sobre {@link ActoDeLaPapeleta#estado()} o sobre {@link
 * InternamientoEnConsulta#clase()} sigue en verde el día que alguien deja de serializar el campo,
 * que es justo por donde llegan estos defectos.
 *
 * <h2>Lo que NO se prueba aquí</h2>
 *
 * <p>De dónde sale la categoría. El {@code LEFT JOIN vehiculo} lo mide {@code SancionesJdbcTest}
 * contra PostgreSQL de verdad, que es el único sitio donde un {@code JOIN} se puede comprobar. Aquí
 * lo que se mide es el transporte.
 */
@DisplayName("#185 — «estado» y «clase» llegan por HTTP")
class ElEstadoYLaClaseLleganPorElCableTest {

    private static final LocalDate DIA = LocalDate.of(2026, 3, 4);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("el acto que nadie encontro llega como NO_NOTIFICADO, con sus tres acuses")
    void elActoNoNotificadoLlegaConSuEstado() throws Exception {
        JsonNode actos = actosDe(resolucionNuncaNotificada(), actaDeIngreso());

        JsonNode resolucion = actos.get(0);
        assertThat(resolucion.get("tipo").asString()).isEqualTo("ORDINARIA");
        assertThat(resolucion.get("estado"))
                .as(
                        "«ActoResource» tiene que publicar el estado del acto: sin el, la columna"
                                + " «Estado» de `tra-pap` no tiene que pintar (#185)")
                .isNotNull();
        assertThat(resolucion.get("estado").asString()).isEqualTo("NO_NOTIFICADO");
        assertThat(resolucion.get("acuses").size())
                .as(
                        "y los TRES intentos siguen viajando: el estado se publica AL LADO de los"
                                + " acuses, no en su lugar")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("el acta del deposito llega como SIN_NOTIFICACION, no como pendiente")
    void elActaLlegaSinNotificacion() throws Exception {
        JsonNode actos = actosDe(resolucionNuncaNotificada(), actaDeIngreso());

        JsonNode acta = actos.get(1);
        assertThat(acta.get("clase").asString()).isEqualTo(ActoDeLaPapeleta.CLASE_INTERNAMIENTO);
        assertThat(acta.get("estado").asString()).isEqualTo("SIN_NOTIFICACION");
    }

    @Test
    @DisplayName("la clase del vehiculo internado viaja en cada fila de la grilla")
    void laClaseViajaEnCadaFila() throws Exception {
        JsonNode filas =
                internamientos(
                        internamiento(1L, "T2G-418", "AUTOMOVIL"),
                        internamiento(2L, "M1B-207", "TRIMOVIL"),
                        internamiento(3L, "X9Z-001", null));

        assertThat(filas.get(0).get("clase"))
                .as(
                        "«InternamientoResource» tiene que publicar la clase: la ficha la publica"
                                + " de UN vehiculo, y la grilla es el deposito entero (#185)")
                .isNotNull();

        assertThat(List.of(filas.get(0), filas.get(1)))
                .extracting(fila -> fila.get("clase").asString())
                .as("cada fila la SUYA: una sola clase repetida diria que el deposito es de una")
                .containsExactly("AUTOMOVIL", "TRIMOVIL");

        assertThat(filas.get(2).get("clase").isNull())
                .as(
                        "y el que no esta en el padron llega NULO, no con cadena vacia: se interna"
                                + " lo que se interna, este o no inscrito, y un «» en esa columna se"
                                + " leeria como un dato")
                .isTrue();
    }

    // ------------------------------------------------------------------

    private JsonNode actosDe(ActoDeLaPapeleta... actos) throws Exception {
        ConsultaDeActosDeLaPapeleta consulta =
                new ConsultaDeActosDeLaPapeleta(null, null, null, null, null) {
                    @Override
                    public Expediente de(Familia familia, String numero) {
                        return new Expediente(papeleta(), List.of(), List.of(actos));
                    }
                };

        MockMvc mvc = montar(new ActosDeLaPapeletaController(consulta));
        MvcResult respuesta =
                mvc.perform(get("/rentas/api/v1/transito/papeletas/PT-0001/actos")).andReturn();

        assertThat(respuesta.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(respuesta.getResponse().getContentAsString()).get("actos");
    }

    private JsonNode internamientos(InternamientoEnConsulta... filas) throws Exception {
        ConsultaDeInternamientos consulta =
                new ConsultaDeInternamientos(null) {
                    @Override
                    public Pagina<InternamientoEnConsulta> listar(
                            CriterioDeInternamiento criterio,
                            LocalDate aLaFecha,
                            Paginacion paginacion) {
                        return Pagina.de(List.of(filas), paginacion, filas.length);
                    }
                };

        MockMvc mvc =
                montar(
                        new InternamientosController(
                                consulta,
                                null,
                                null,
                                java.time.Clock.fixed(
                                        DIA.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                                        java.time.ZoneOffset.UTC)));
        MvcResult respuesta =
                mvc.perform(get("/rentas/api/v1/transito/internamientos")).andReturn();

        assertThat(respuesta.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(respuesta.getResponse().getContentAsString()).get("contenido");
    }

    private static MockMvc montar(Object controlador) {
        return MockMvcBuilders.standaloneSetup(controlador)
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    /** Tres intentos y ninguno encontro a nadie: el acto existe y no abre plazo. */
    private static ActoDeLaPapeleta resolucionNuncaNotificada() {
        return new ActoDeLaPapeleta(
                ActoDeLaPapeleta.CLASE_RESOLUCION,
                "ORDINARIA",
                "RG-2026-000001",
                DIA,
                11L,
                Observacion.de("resolucion de gerencia de la prueba"),
                List.of(acuseFallido(1), acuseFallido(2), acuseFallido(3)));
    }

    private static ActoDeLaPapeleta actaDeIngreso() {
        return new ActoDeLaPapeleta(
                ActoDeLaPapeleta.CLASE_INTERNAMIENTO,
                "INGRESO",
                "ACTA_INTERNAMIENTO-2026-000001",
                DIA.plusDays(1),
                12L,
                Observacion.de("Conducir sin licencia vigente"),
                List.of());
    }

    private static AcuseDelActo acuseFallido(int intento) {
        return new AcuseDelActo(
                intento,
                DIA.plusDays(intento),
                ModalidadDeNotificacion.PERSONAL,
                ResultadoDeNotificacion.NO_UBICADO,
                null,
                null,
                null);
    }

    private static InternamientoEnConsulta internamiento(long id, String placa, String clase) {
        return new InternamientoEnConsulta(
                id,
                placa,
                clase,
                "PT-000" + id,
                "DEPOSITO SULLANA NORTE",
                DIA,
                null,
                11,
                DIA.plusDays(11),
                EstadoDeInternamiento.INTERNADO,
                "CUSTODIA",
                "ACTA_INTERNAMIENTO-2026-00000" + id);
    }

    private static Papeleta papeleta() {
        return Papeleta.nuevaTransito(
                "PT-0001",
                1L,
                DIA,
                null,
                "Av. Grau",
                "T2G-418",
                null,
                null,
                null,
                null,
                1L,
                Dinero.de("5500"),
                Alicuota.de("8"),
                Dinero.de("440"),
                Alicuota.de("100"),
                Dinero.de("440"),
                null,
                Observacion.de("papeleta de la prueba"));
    }
}
