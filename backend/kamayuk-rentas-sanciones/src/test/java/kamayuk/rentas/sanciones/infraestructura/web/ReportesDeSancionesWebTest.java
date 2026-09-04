package kamayuk.rentas.sanciones.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Capa web de #53: lo que los seis controladores rechazan <b>antes</b> de llamar a nada.
 *
 * <h2>Por qué los servicios entran en {@code null}</h2>
 *
 * <p>No es un atajo: es el <b>enunciado</b> de la prueba. Todo lo que se comprueba aquí ocurre en
 * el borde —la observación que falta, el criterio que viene por dos caminos a la vez, el filtro que
 * este contexto no puede servir, el record sin sujeto— y si alguna de esas comprobaciones se
 * moviera detrás del servicio, esta prueba fallaría con un {@code NullPointerException} en vez de
 * con el 422 que espera. Es decir: se pone roja exactamente cuando la validación deja de estar
 * donde tiene que estar.
 *
 * <p>Lo que pasa <b>después</b> de esa frontera —el 409 de la constancia negada con su lista de
 * papeletas, la corrida que no encuentra candidatos, la numeración— se verifica en {@code
 * ValoresMasivosYReportesJdbcTest} contra PostgreSQL, que es donde significa algo.
 */
@DisplayName("#53 — Capa web: lo que se rechaza en el borde")
class ReportesDeSancionesWebTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-04-20T09:00:00Z"), ZoneOffset.UTC);

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new GeneracionMasivaDeValoresController(null, RELOJ),
                            new ConstanciasLibresController(null, RELOJ),
                            new PadronesDeTransitoController(null, null, RELOJ),
                            new RecordsDeTransitoController(null, null, RELOJ),
                            new ResumenesDeTransitoController(null, null, RELOJ),
                            new ReportesDeTransitoController(null, null, null, RELOJ),
                            new ReportesAdministrativosController(null, null, null, RELOJ))
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
    @DisplayName("una generacion masiva sin observacion no se registra: 422 (regla 10, RNF-052)")
    void sinObservacionNoSeRegistra() throws Exception {
        for (String ruta :
                new String[] {
                    "/rentas/api/v1/transito/valores/generacion-masiva",
                    "/rentas/api/v1/infracciones/administrativas/valores/generacion-masiva"
                }) {
            MvcResult resultado = enviar(ruta, "{\"papeletas\":[\"PT-0001\"]}");

            assertThat(resultado.getResponse().getStatus()).as(ruta).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("Toda modificacion exige la observacion del usuario");
        }
    }

    @Test
    @DisplayName("con seleccion Y rango a la vez, se rechaza en vez de que uno gane en silencio")
    void conLosDosCriteriosSeRechaza() throws Exception {
        MvcResult resultado =
                enviar(
                        "/rentas/api/v1/transito/valores/generacion-masiva",
                        "{\"papeletas\":[\"PT-0001\"],\"desde\":\"2026-03-01\","
                                + "\"hasta\":\"2026-03-31\",\"observacion\":\"Emision masiva\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("y solo uno de los dos");
    }

    @Test
    @DisplayName("sin seleccion NI rango tampoco: una corrida sin criterio no existe")
    void sinNingunCriterioTambien() throws Exception {
        MvcResult resultado =
                enviar(
                        "/rentas/api/v1/transito/valores/generacion-masiva",
                        "{\"observacion\":\"Emision masiva\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("el numero del valor no entra por el cuerpo: no hay campo por donde mandarlo")
    void elNumeroNoEntraPorElCuerpo() {
        assertThat(
                        java.util.Arrays.stream(
                                        PeticionDeCorridaDeValores.class.getRecordComponents())
                                .map(java.lang.reflect.RecordComponent::getName))
                .as(
                        "AC 1 de #53: el correlativo sale de valor_correlativo (V26). Si entrara"
                                + " por aqui, «una serie propia para las multas» seria mandar otro"
                                + " texto")
                .containsExactlyInAnyOrder(
                        "papeletas", "desde", "hasta", "fechaCriterio", "observacion");
    }

    @Test
    @DisplayName("una constancia sin observacion o sin placa: 422")
    void laConstanciaExigeSusCampos() throws Exception {
        MvcResult sinObservacion =
                enviar("/rentas/api/v1/transito/constancias-libres", "{\"placa\":\"P1T-234\"}");
        assertThat(sinObservacion.getResponse().getStatus()).isEqualTo(422);
        assertThat(sinObservacion.getResponse().getContentAsString())
                .contains("Toda modificacion exige la observacion del usuario");

        MvcResult sinPlaca =
                enviar(
                        "/rentas/api/v1/transito/constancias-libres",
                        "{\"observacion\":\"Solicitud del administrado\"}");
        assertThat(sinPlaca.getResponse().getStatus()).isEqualTo(422);
        assertThat(sinPlaca.getResponse().getContentAsString()).contains("placa");
    }

    @Test
    @DisplayName("un formato que no existe se rechaza nombrando los tres de RF-132")
    void elFormatoVaEntreLosTres() throws Exception {
        MvcResult resultado =
                enviar(
                        "/rentas/api/v1/transito/constancias-libres",
                        "{\"placa\":\"P1T-234\",\"formato\":\"DOCX\","
                                + "\"observacion\":\"Solicitud del administrado\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("El formato va entre PDF, XLS y RTF");
    }

    @Test
    @DisplayName("el padron de coactiva rechaza el ejecutor: no es columna de la papeleta")
    void elPadronDeCoactivaRechazaLoQueNoEsSuyo() throws Exception {
        for (String filtro : new String[] {"ejecutor=RUIZ", "estadoDelExpediente=ABIERTO"}) {
            MvcResult resultado =
                    mvc.perform(get("/rentas/api/v1/transito/reportes/padron-coactiva?" + filtro))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).as(filtro).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .as("dice donde vive ese filtro, en vez de devolver el padron sin filtrar")
                    .contains("/coactiva/expedientes");
        }
    }

    @Test
    @DisplayName("el resumen de recaudacion rechaza el filtro por caja: la caja es de tesoreria")
    void elResumenRechazaElFiltroPorCaja() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/transito/reportes/resumen-recaudacion?caja=C-01"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("/tesoreria/recaudacion/por-area");
    }

    @Test
    @DisplayName("un record sin sujeto no es un record: 422 en los dos")
    void unRecordSinSujetoSeRechaza() throws Exception {
        MvcResult conductor =
                mvc.perform(get("/rentas/api/v1/transito/reportes/record-conductor")).andReturn();
        assertThat(conductor.getResponse().getStatus()).isEqualTo(422);
        assertThat(conductor.getResponse().getContentAsString())
                .contains("el padron entero con otro titulo");

        MvcResult vehicular =
                mvc.perform(get("/rentas/api/v1/transito/reportes/record-vehicular")).andReturn();
        assertThat(vehicular.getResponse().getStatus()).isEqualTo(422);
        assertThat(vehicular.getResponse().getContentAsString()).contains("necesita la placa");
    }

    @Test
    @DisplayName("el emisor de reportes administrativos nombra los tres que sabe emitir")
    void elEmisorNombraLosTres() throws Exception {
        MvcResult resultado =
                enviar(
                        "/rentas/api/v1/infracciones/administrativas/reportes",
                        "{\"reporte\":\"PADRON_DE_LO_QUE_SEA\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("PADRON_NOTIFICACIONES")
                .contains("RESUMEN_PAPELETAS")
                .contains("RESUMEN_RECAUDACION");
    }

    @Test
    @DisplayName("el emisor de transito nombra los nueve reportes que sabe emitir")
    void elEmisorDeTransitoNombraLosNueve() throws Exception {
        MvcResult resultado =
                enviar(
                        "/rentas/api/v1/transito/reportes",
                        "{\"reporte\":\"PADRON_DE_LO_QUE_SEA\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("PADRON_COACTIVA")
                .contains("PADRON_CONSTANCIAS")
                .contains("RECORD_CONDUCTOR")
                .contains("RECORD_VEHICULAR")
                .contains("RESUMEN_RECAUDACION")
                .contains("RESUMEN_PAPELETAS")
                .contains("RESUMEN_CODIGO")
                .contains("RESUMEN_PLACA");
    }

    @Test
    @DisplayName("un criterio que el reporte de transito no usa se rechaza NOMBRANDOLO")
    void elCriterioDeMasSeRechazaNombrandolo() throws Exception {
        MvcResult resultado =
                enviar(
                        "/rentas/api/v1/transito/reportes",
                        "{\"reporte\":\"RESUMEN_RECAUDACION\",\"ano\":\"2026\","
                                + "\"placa\":\"P1T-234\"}");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "con los servicios en null, un 500 significaria que la validacion se movio"
                                + " detras de la frontera")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("la hoja saldria correcta para una pregunta que no es la que se hizo")
                .contains("placa")
                .contains("RESUMEN_RECAUDACION");
    }

    @Test
    @DisplayName("y un record sin sujeto se rechaza tambien desde el emisor")
    void elRecordSinSujetoDesdeElEmisor() throws Exception {
        MvcResult resultado =
                enviar("/rentas/api/v1/transito/reportes", "{\"reporte\":\"RECORD_CONDUCTOR\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("el padron entero con otro titulo");
    }

    @Test
    @DisplayName("una fecha mal escrita se rechaza diciendo el formato, no con un 500")
    void unaFechaMalEscritaSeRechaza() throws Exception {
        MvcResult resultado =
                enviar(
                        "/rentas/api/v1/transito/valores/generacion-masiva",
                        "{\"papeletas\":[\"PT-0001\"],\"fechaCriterio\":\"15/04/2026\","
                                + "\"observacion\":\"Emision masiva\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("AAAA-MM-DD");
    }

    private MvcResult enviar(String ruta, String cuerpo) throws Exception {
        return mvc.perform(post(ruta).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andReturn();
    }
}
