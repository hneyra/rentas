package kamayuk.rentas.sanciones.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * Capa web de #50: lo que los cuatro controladores rechazan <b>antes</b> de llamar a nada.
 *
 * <h2>Por qué los servicios entran en {@code null}</h2>
 *
 * <p>No es un atajo: es el <b>enunciado</b> de la prueba. Todo lo que se comprueba aquí ocurre en
 * el borde —la observación que falta, la fecha mal escrita, el campo que no está en la lista
 * blanca, el enumerado que no existe— y si alguna de esas comprobaciones se moviera detrás del
 * servicio, esta prueba fallaría con un {@code NullPointerException} en vez de con el 422 que
 * espera. Es decir: la prueba se pone roja exactamente cuando la validación deja de estar donde
 * tiene que estar.
 *
 * <p>Lo que pasa <b>después</b> de esa frontera —el 404 de la papeleta inexistente, el 409 de la
 * custodia sin pagar, el 409 del plazo en curso— se verifica en {@code SancionesJdbcTest} con las
 * excepciones de verdad y contra PostgreSQL, que es donde significan algo.
 */
@DisplayName("#50 — Capa web: la observacion y la lista blanca")
class SancionesWebTest {

    private static final java.time.Clock RELOJ =
            java.time.Clock.fixed(
                    java.time.Instant.parse("2026-08-13T09:00:00Z"), java.time.ZoneOffset.UTC);

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new DescargosController(null),
                            // El reloj SI entra: la grilla resuelve «a que fecha» antes de mirar
                            // ningun filtro, porque los dias en deposito no significan nada sin
                            // su fecha (regla 9, RNF-075).
                            new InternamientosController(null, null, null, RELOJ),
                            new ResolucionesDeGerenciaController(null, null),
                            new ActosDeLaPapeletaController(null))
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
    @DisplayName("un descargo sin observacion no se guarda: 422 (regla 10, RNF-052)")
    void unDescargoSinObservacionNoSeGuarda() throws Exception {
        MvcResult resultado =
                enviar(
                        "/rentas/api/v1/transito/descargos",
                        "{\"papeleta\":\"PT-0001\",\"nDeExpediente\":\"2026-1188\","
                                + "\"fechaDePresentacion\":\"2026-03-06\","
                                + "\"tipoDeRecurso\":\"DESCARGO\",\"fundamento\":\"x\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("Toda modificacion exige la observacion del usuario");
    }

    @Test
    @DisplayName("una resolucion de gerencia sin observacion tampoco: 422")
    void unaResolucionSinObservacionTampoco() throws Exception {
        for (String ruta :
                new String[] {
                    "/rentas/api/v1/transito/resoluciones/ordinaria",
                    "/rentas/api/v1/transito/resoluciones/sancionadora",
                    "/rentas/api/v1/infracciones/administrativas/resoluciones"
                }) {
            MvcResult resultado =
                    enviar(
                            ruta,
                            "{\"papeleta\":\"PT-0001\",\"fecha\":\"2026-04-01\","
                                    + "\"sustento\":\"x\"}");
            assertThat(resultado.getResponse().getStatus()).as(ruta).isEqualTo(422);
        }
    }

    @Test
    @DisplayName("las dos notificaciones de resolucion tampoco: 422")
    void lasNotificacionesTampoco() throws Exception {
        for (String ruta :
                new String[] {
                    "/rentas/api/v1/transito/resoluciones/RGO-2026-000001/notificacion",
                    "/rentas/api/v1/infracciones/administrativas/resoluciones/RGA-2026-000001/notificacion"
                }) {
            MvcResult resultado =
                    enviar(
                            ruta,
                            "{\"fechaDeNotificacion\":\"2026-04-02\",\"modalidad\":\"PERSONAL\","
                                    + "\"resultado\":\"NOTIFICADO\",\"notificador\":\"V. RETO\"}");
            assertThat(resultado.getResponse().getStatus()).as(ruta).isEqualTo(422);
        }
    }

    @Test
    @DisplayName("el ingreso al deposito y la liberacion, tampoco: 422")
    void elDepositoTampoco() throws Exception {
        MvcResult ingreso =
                enviar(
                        "/rentas/api/v1/transito/internamientos",
                        "{\"placa\":\"T2G-418\",\"deposito\":\"DEPOSITO NORTE\","
                                + "\"fechaDeIngreso\":\"2026-08-02\","
                                + "\"tasaDeCustodia\":\"CUSTODIA\",\"motivo\":\"x\"}");
        assertThat(ingreso.getResponse().getStatus()).isEqualTo(422);

        MvcResult liberacion =
                enviar(
                        "/rentas/api/v1/transito/internamientos/T2G-418/liberacion",
                        "{\"fechaDeLiberacion\":\"2026-08-13\","
                                + "\"reciboDeCustodia\":\"001-0000123\","
                                + "\"personaQueRetira\":\"DORIS\","
                                + "\"documentoDeQuienRetira\":\"DNI 44218937\"}");
        assertThat(liberacion.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("con observacion pero sin el recibo de la custodia, 422: no es opcional")
    void sinElReciboDeLaCustodia422() throws Exception {
        MvcResult resultado =
                enviar(
                        "/rentas/api/v1/transito/internamientos/T2G-418/liberacion",
                        "{\"observacion\":\"El titular retira el vehiculo\","
                                + "\"fechaDeLiberacion\":\"2026-08-13\","
                                + "\"personaQueRetira\":\"DORIS\","
                                + "\"documentoDeQuienRetira\":\"DNI 44218937\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("reciboDeCustodia");
    }

    @Test
    @DisplayName("una fecha mal escrita es 422, no 500: lo mando mal el cliente")
    void unaFechaMalEscritaEs422() throws Exception {
        MvcResult resultado =
                enviar(
                        "/rentas/api/v1/transito/descargos",
                        "{\"observacion\":\"prueba\",\"papeleta\":\"PT-0001\","
                                + "\"nDeExpediente\":\"2026-1188\","
                                + "\"fechaDePresentacion\":\"06/03/2026\","
                                + "\"tipoDeRecurso\":\"DESCARGO\",\"fundamento\":\"x\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("AAAA-MM-DD");
    }

    @Test
    @DisplayName("un tipo de recurso que no existe es 422, y el mensaje dice cuales hay")
    void unTipoDeRecursoQueNoExisteEs422() throws Exception {
        MvcResult resultado =
                enviar(
                        "/rentas/api/v1/transito/descargos",
                        "{\"observacion\":\"prueba\",\"papeleta\":\"PT-0001\","
                                + "\"nDeExpediente\":\"2026-1188\","
                                + "\"fechaDePresentacion\":\"2026-03-06\","
                                + "\"tipoDeRecurso\":\"QUEJA\",\"fundamento\":\"x\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("RECONSIDERACION");
    }

    @Test
    @DisplayName("un estado de deposito que no existe es 422, no un listado silenciosamente vacio")
    void unEstadoDeDepositoQueNoExisteEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/transito/internamientos")
                                        .param("estado", "PERDIDO"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("el cuerpo es lista blanca: un campo que la opcion no declara no entra")
    void elCuerpoEsListaBlanca() throws Exception {
        // `estado` no esta en PeticionDeDescargo: la papeleta no cambia de estado porque alguien
        // lo mande en el JSON. Jackson lo ignora, y lo que falla es lo que de verdad falta.
        MvcResult resultado =
                enviar(
                        "/rentas/api/v1/transito/descargos",
                        "{\"observacion\":\"prueba\",\"papeleta\":\"PT-0001\","
                                + "\"estado\":\"ANULADA\",\"usuarioRegistro\":\"otro\"}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("se queja de lo que falta, no de lo que sobra: lo que sobra ni se lee")
                .contains("nDeExpediente");
    }

    private MvcResult enviar(String ruta, String cuerpo) throws Exception {
        return mvc.perform(post(ruta).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andReturn();
    }
}
