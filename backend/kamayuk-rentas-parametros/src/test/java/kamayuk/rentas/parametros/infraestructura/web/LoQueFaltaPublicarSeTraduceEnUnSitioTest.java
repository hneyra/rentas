package kamayuk.rentas.parametros.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.CifraSinPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #435 — una cifra sin publicar que <b>ningun</b> {@code catch} nombra contesta su 422 con {@code
 * parametroQueFalta}, y no el 500 con incidencia de {@code ManejadorDeErrores.cualquierOtra}.
 *
 * <p>Los dos <i>advice</i> se registran en el orden que <b>no</b> ayuda —{@code ManejadorDeErrores}
 * primero, con su manejador de {@code Exception.class}—: si el nuevo gana es por su {@code @Order},
 * no por la suerte del registro. Quitarle el orden o quitar el <i>advice</i> la pone roja.
 */
@DisplayName("#435 — la familia «falta publicar» se traduce en un sitio, la nombre un catch o no")
class LoQueFaltaPublicarSeTraduceEnUnSitioTest {

    private static final JsonMapper JSON = new JsonMapper();

    /** Una cifra de mañana: extiende la base y nadie la ha escrito en ningun {@code catch}. */
    static final class CifraDeManana extends CifraSinPublicar {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CifraDeManana() {
            super(
                    "El conjunto sellado del ejercicio 2027 no tiene el parametro TIM:MENSUAL",
                    new Ejercicio(2027),
                    "TIM:MENSUAL");
        }
    }

    /** Un controlador que deja pasar lo que su caso de uso lanza, sin enumerarlo. */
    @RestController
    static class ControladorQueNoLaNombra {
        @GetMapping("/cifra-de-manana")
        String cifraDeManana() {
            throw new CifraDeManana();
        }

        @GetMapping("/ejercicio-sin-sellar")
        String ejercicioSinSellar() {
            throw new LectorDeParametros.EjercicioSinSellar(new Ejercicio(2027));
        }
    }

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new ControladorQueNoLaNombra())
                    .setControllerAdvice(
                            new ManejadorDeErrores(),
                            new ManejadorDeLoQueFaltaPublicar(new ManejadorDeErrores()))
                    .build();

    @Test
    @DisplayName("una cifra que ningun catch nombra sale 422 con su llave, no 500")
    void laQueNadieNombraSaleComo422() throws Exception {
        MvcResult resultado = mvc.perform(get("/cifra-de-manana")).andReturn();

        JsonNode cuerpo = JSON.readTree(resultado.getResponse().getContentAsString());
        assertThat(resultado.getResponse().getStatus())
                .as(
                        "hasta #435 caia en cualquierOtra: 500 ERROR_INTERNO con incidencia — %s",
                        cuerpo)
                .isEqualTo(422);
        assertThat(cuerpo.path("codigo").asString()).isEqualTo("VALIDACION");
        assertThat(cuerpo.path("parametroQueFalta").path("llave").asString())
                .isEqualTo("TIM:MENSUAL");
        assertThat(cuerpo.path("parametroQueFalta").path("ejercicio").asInt()).isEqualTo(2027);
        assertThat(cuerpo.has("incidencia")).isFalse();
    }

    @Test
    @DisplayName("y el conjunto que falta entero tambien, sin inventar una llave")
    void elConjuntoEnteroSinLlave() throws Exception {
        MvcResult resultado = mvc.perform(get("/ejercicio-sin-sellar")).andReturn();

        JsonNode cuerpo = JSON.readTree(resultado.getResponse().getContentAsString());
        assertThat(resultado.getResponse().getStatus()).as("%s", cuerpo).isEqualTo(422);
        assertThat(cuerpo.path("parametroQueFalta").path("ejercicio").asInt()).isEqualTo(2027);
        assertThat(cuerpo.path("parametroQueFalta").has("llave"))
                .as("falta el conjunto: no hay fila que nombrar")
                .isFalse();
    }
}
