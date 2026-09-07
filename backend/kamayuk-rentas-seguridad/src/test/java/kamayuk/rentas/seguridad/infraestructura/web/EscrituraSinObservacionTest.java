package kamayuk.rentas.seguridad.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.seguridad.aplicacion.AdministrarSesion;
import kamayuk.rentas.seguridad.dominio.Sesion;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #30 — una escritura sin observacion contesta 422 nombrando el campo, y no 500.
 *
 * <p><b>Lo que medía el issue, contra la instalacion levantada</b>: {@code PUT
 * /seguridad/sesion/ejercicio} con {@code {"ejercicio":2026}} contestaba {@code 500 ERROR_INTERNO}
 * con un identificador de incidencia, y la frase que el cliente necesita —«Toda escritura exige una
 * observacion»— se quedaba en el registro del servidor dentro de un {@code NullPointerException}.
 * Las tres operaciones vecinas a las que les falta un obligatorio contestan {@code 422}
 * nombrandolo.
 *
 * <p><b>Por que se mide aqui y con el manejador de errores puesto.</b> El defecto no vive en el
 * dominio ni en el caso de uso: vive en la traduccion de la excepcion a estado HTTP, que es cosa
 * del borde. Un {@code assertThatThrownBy} sobre {@code Observacion.de(null)} dice que tipo de
 * excepcion sale y <b>no</b> dice con que estado se contesta, que es justo lo que estaba mal.
 *
 * <p><b>Y no basta con afirmar «contesta 4xx».</b> Este repositorio ya se ha encontrado tres veces
 * con la misma trampa: una prueba que solo mira la clase del estado pasa en verde con el codigo que
 * contesta 400 por otro motivo —un cuerpo ilegible, un verbo equivocado—. Lo que muerde es exigir
 * <b>las dos cosas a la vez</b>: el estado exacto Y que el cuerpo nombre el campo que falta. Por
 * eso cada caso afirma {@code 422}, el codigo del catalogo y la cadena {@code 'observacion'}.
 *
 * <p>El caso de uso es un doble que <b>revienta si lo llaman</b>: sin observacion no se escribe
 * nada, y eso es la regla 10 medida y no supuesta.
 */
@DisplayName("#30 — una escritura sin observacion es 422 y nombra el campo")
class EscrituraSinObservacionTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC);

    /** Si esto se llama, la escritura ocurrio sin observacion: es la regla 10 rota. */
    private final AdministrarSesion nadieEscribe =
            new AdministrarSesion(null, null, null, RELOJ) {
                @Override
                public Sesion cambiarEjercicioDeTrabajo(
                        Ejercicio ejercicio, Observacion observacion) {
                    throw new AssertionError(
                            "Sin observacion no se guarda (regla 10): el caso de uso no tenia que"
                                    + " llegar a ejecutarse");
                }

                @Override
                public String iniciarCambioDeClave(long usuarioId, Observacion observacion) {
                    throw new AssertionError(
                            "Sin observacion no se guarda (regla 10): el caso de uso no tenia que"
                                    + " llegar a ejecutarse");
                }
            };

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new SesionController(nadieEscribe, null, null, null))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    @Nested
    @DisplayName("La operacion que el issue midio")
    class LaQueElIssueMidio {

        @Test
        @DisplayName("sin el campo: 422, y el cuerpo dice cual falta")
        void sinElCampo() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    put("/rentas/api/v1/seguridad/sesion/ejercicio")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"ejercicio\":2026}"))
                            .andReturn();

            String cuerpo = respuesta.getResponse().getContentAsString();
            assertThat(respuesta.getResponse().getStatus())
                    .as("un cuerpo incompleto es del cliente, no del servidor: %s", cuerpo)
                    .isEqualTo(422);
            assertThat(cuerpo)
                    .as("el estado solo no basta: el cliente tiene que saber QUE campo falta")
                    .contains("'observacion'")
                    .contains("VALIDACION");
            assertThat(cuerpo)
                    .as("un 500 con incidencia manda a leer el registro del servidor")
                    .doesNotContain("ERROR_INTERNO")
                    .doesNotContain("incidencia");
        }

        @Test
        @DisplayName("con el campo a nulo explicito: la misma respuesta")
        void conElCampoANuloExplicito() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    put("/rentas/api/v1/seguridad/sesion/ejercicio")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"ejercicio\":2026,\"observacion\":null}"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString()).contains("'observacion'");
        }
    }

    @Nested
    @DisplayName("La otra escritura del mismo controlador, que tenia el mismo defecto")
    class LaOtraEscritura {

        @Test
        @DisplayName("el cambio de clave sin observacion: 422 y nombra el campo")
        void elCambioDeClave() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    put("/rentas/api/v1/seguridad/usuarios/7/clave")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{}"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString())
                    .contains("'observacion'")
                    .doesNotContain("ERROR_INTERNO");
        }
    }

    @Nested
    @DisplayName("El contraste, sin el cual lo de arriba no significa nada")
    class ElContraste {

        /**
         * Una observacion demasiado corta ya contestaba 422 antes de #30, y tiene que seguir
         * contestando lo suyo: si el arreglo hubiera convertido <i>todo</i> en «falta el campo»,
         * quien escribe cuatro caracteres recibiria que no escribio ninguno.
         */
        @Test
        @DisplayName("una observacion corta sigue diciendo que es corta, no que falta")
        void unaObservacionCortaSigueDiciendoQueEsCorta() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    put("/rentas/api/v1/seguridad/sesion/ejercicio")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"ejercicio\":2026,\"observacion\":\"ab\"}"))
                            .andReturn();

            String cuerpo = respuesta.getResponse().getContentAsString();
            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            assertThat(cuerpo).contains("al menos 5 caracteres");
            assertThat(cuerpo)
                    .as("no es lo mismo un campo que falta que uno que no vale")
                    .doesNotContain("Falta el campo");
        }
    }
}
