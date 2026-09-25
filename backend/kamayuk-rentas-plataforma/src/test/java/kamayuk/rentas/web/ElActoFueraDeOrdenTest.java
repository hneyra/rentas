package kamayuk.rentas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import kamayuk.rentas.dominio.OrdenDeLosActos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * #402 — {@code ActoFueraDeOrden} sale como 422 en un solo sitio, y no en un {@code catch} por
 * controlador.
 *
 * <p>La sonda no la traduce: la deja subir, como la deja subir cada uno de los once casos de uso
 * que llaman a {@link OrdenDeLosActos}. Si el manejador no la conociera, caeria en {@code
 * cualquierOtra} y saldria 500 con su incidencia y su linea ERROR, que es lo que dice un servidor
 * roto y no una fecha mal tecleada.
 */
@DisplayName("#402 — un acto fuera de orden es 422 y nombra lo que incumple")
class ElActoFueraDeOrdenTest {

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new Sonda())
                    .setControllerAdvice(new ManejadorDeErrores())
                    .build();

    @Test
    @DisplayName("anterior al acto previo: 422 VALIDACION, con el acto previo y su fecha")
    void anteriorAlActoPrevio() throws Exception {
        MvcResult respuesta = mvc.perform(post("/sonda/anterior")).andReturn();

        assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
        assertThat(respuesta.getResponse().getContentAsString())
                .contains(CodigoDeError.VALIDACION.name())
                .contains("la infraccion de la papeleta T-003, del 2026-03-04")
                .doesNotContain("incidencia");
    }

    @Test
    @DisplayName("posterior a hoy: 422 VALIDACION, diciendolo")
    void posteriorAHoy() throws Exception {
        MvcResult respuesta = mvc.perform(post("/sonda/futuro")).andReturn();

        assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
        assertThat(respuesta.getResponse().getContentAsString())
                .contains(CodigoDeError.VALIDACION.name())
                .contains("posterior a hoy, 2026-09-23")
                .doesNotContain("incidencia");
    }

    @RestController
    static class Sonda {

        private static final LocalDate HOY = LocalDate.of(2026, 9, 23);

        @PostMapping("/sonda/anterior")
        void anterior() {
            OrdenDeLosActos.exigir(
                    "la anulacion de la papeleta T-003",
                    LocalDate.of(2026, 3, 1),
                    HOY,
                    new OrdenDeLosActos.ActoPrevio(
                            "la infraccion de la papeleta T-003", LocalDate.of(2026, 3, 4)));
        }

        @PostMapping("/sonda/futuro")
        void futuro() {
            OrdenDeLosActos.exigir("el pase a coactiva", HOY.plusDays(1), HOY);
        }
    }
}
