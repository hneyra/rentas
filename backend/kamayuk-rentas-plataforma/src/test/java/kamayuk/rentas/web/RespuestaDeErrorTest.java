package kamayuk.rentas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #456 — El error que se escribe fuera del {@code DispatcherServlet} tiene la misma forma que el
 * que escribe {@link ManejadorDeErrores}.
 *
 * <p>El javadoc de {@link RespuestaDeError} afirmaba que habia una prueba que comparaba las dos
 * formas, y no la habia —ni aqui ni en {@code sgtm}—. Lo midio el frontend contra la instalacion:
 * el 401 de la cadena de seguridad traia cuatro miembros y el 404 de un controlador seis, asi que
 * el mismo {@code SIN_PRIVILEGIO} llegaba sin {@code type} ni {@code detail} segun quien lo
 * rechazara. La siembra que no distingue es comparar solo {@code codigo} y {@code mensaje}, que los
 * dos caminos ya emitian: se compara el conjunto entero de miembros, codigo a codigo.
 */
@DisplayName("#456 — Una sola forma de problem+json, se escriba desde donde se escriba")
class RespuestaDeErrorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new Sonda())
                    .setControllerAdvice(new ManejadorDeErrores())
                    .build();

    @Test
    @DisplayName("para cada codigo del catalogo, los mismos miembros por los dos caminos")
    void losMismosMiembrosPorLosDosCaminos() throws Exception {
        List<String> distintos = new ArrayList<>();
        for (CodigoDeError codigo : CodigoDeError.values()) {
            String delManejador =
                    mvc.perform(get("/sonda/error").param("codigo", codigo.name()))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            Set<String> delFiltro =
                    miembros(
                            JSON.readTree(
                                    RespuestaDeError.cuerpo(
                                            codigo, codigo.mensaje(), "/sonda/error")));
            Set<String> esperados = miembros(JSON.readTree(delManejador));
            if (!delFiltro.equals(esperados)) {
                distintos.add(codigo + ": filtro " + delFiltro + " / manejador " + esperados);
            }
        }

        assertThat(distintos)
                .as("el mismo codigo no puede llegar con dos formas segun quien lo rechace")
                .isEmpty();
    }

    @Test
    @DisplayName("y el cuerpo es JSON valido aunque el mensaje lleve comillas")
    void unMensajeConComillasSigueSiendoJson() throws Exception {
        JsonNode cuerpo =
                JSON.readTree(
                        RespuestaDeError.cuerpo(
                                CodigoDeError.SIN_PRIVILEGIO,
                                "falta el acceso \"caja\" y \\ otro",
                                "/sonda/error"));

        assertThat(cuerpo.path("detail").asString())
                .isEqualTo("falta el acceso \"caja\" y \\ otro");
        assertThat(cuerpo.path("codigo").asString()).isEqualTo("SIN_PRIVILEGIO");
    }

    private static Set<String> miembros(JsonNode cuerpo) {
        Set<String> nombres = new LinkedHashSet<>();
        cuerpo.propertyNames().forEach(nombres::add);
        return nombres;
    }

    /** Lanza el error del codigo pedido, para ver que forma le da {@link ManejadorDeErrores}. */
    @RestController
    static class Sonda {
        @GetMapping("/sonda/error")
        String error(@RequestParam String codigo) {
            CodigoDeError elegido = CodigoDeError.valueOf(codigo);
            throw new ProblemaDeNegocio(elegido, elegido.mensaje());
        }
    }
}
