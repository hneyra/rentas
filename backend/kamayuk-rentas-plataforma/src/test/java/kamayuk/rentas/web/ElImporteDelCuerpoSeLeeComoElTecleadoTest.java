package kamayuk.rentas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import kamayuk.rentas.dominio.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Un importe que llega en el CUERPO se lee con la misma regla que el tecleado en un parametro
 * (#395, anotado en #629), y el 422 dice lo mismo: el campo y cuantos decimales trae.
 *
 * <p>El deserializador lanza el {@link ProblemaDeNegocio} de {@link EntradaNumerica} dentro de
 * Jackson, y Spring lo entrega envuelto en un {@code HttpMessageNotReadableException}. Sin mirar la
 * causa, el borde contestaba el 422 del cuerpo ilegible —«no es JSON valido»—, que es falso: el
 * JSON es valido, lo que sobra es un decimal.
 */
@DisplayName("#395 — Un importe del cuerpo con tres decimales: el mismo 422 que el tecleado")
class ElImporteDelCuerpoSeLeeComoElTecleadoTest {

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new Sonda())
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
    @DisplayName("tres decimales en el cuerpo: 422 nombrando el campo, no un redondeo en silencio")
    void tresDecimalesEsUn422QueNombraElCampo() throws Exception {
        MvcResult respuesta = enviar("{\"insoluto\":\"33.333\"}");

        assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
        assertThat(respuesta.getResponse().getContentAsString())
                .as("lo que el cliente tiene que corregir, y no «no es JSON valido»")
                .contains("insoluto")
                .contains("3 decimales")
                .doesNotContain("no es JSON valido")
                .doesNotContain("kamayuk.rentas");
    }

    @Test
    @DisplayName("dos decimales pasan, y un JSON que no lo es sigue diciendo que no lo es")
    void loDemasNoCambia() throws Exception {
        MvcResult bien = enviar("{\"insoluto\":\"33.33\"}");
        assertThat(bien.getResponse().getStatus()).isEqualTo(200);
        assertThat(bien.getResponse().getContentAsString()).contains("33.33");

        MvcResult ilegible = enviar("{no-json");
        assertThat(ilegible.getResponse().getStatus()).isEqualTo(422);
        assertThat(ilegible.getResponse().getContentAsString()).contains("no es JSON valido");
    }

    private MvcResult enviar(String cuerpo) throws Exception {
        return mvc.perform(
                        post("/sonda/importe")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }

    /** Un controlador cuyo cuerpo lleva un {@link Dinero}: hoy ninguno de produccion lo lleva. */
    @RestController
    static class Sonda {

        @PostMapping("/sonda/importe")
        String importe(@RequestBody CuerpoConImporte cuerpo) {
            return cuerpo.insoluto().valor().toPlainString();
        }
    }

    record CuerpoConImporte(Dinero insoluto) {}
}
