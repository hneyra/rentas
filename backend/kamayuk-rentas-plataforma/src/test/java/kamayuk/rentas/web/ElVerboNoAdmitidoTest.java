package kamayuk.rentas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Un verbo que la ruta no admite contesta <b>405 con {@code Allow}</b>, y no 500 con incidencia
 * (#556).
 *
 * <h2>Lo que se midio antes de escribir nada</h2>
 *
 * <p>Contra la instalacion en marcha, tres peticiones con el verbo equivocado —{@code GET} sobre
 * rutas que son {@code PUT} o {@code POST}— contestaban <b>500 {@code ERROR_INTERNO} con su UUID de
 * incidencia</b>. La causa no esta en ningun controlador: Spring lanza {@link
 * org.springframework.web.HttpRequestMethodNotSupportedException} al <i>buscar el handler</i>,
 * antes de entrar a ninguno, y caia en el {@code @ExceptionHandler(Exception.class)} de {@link
 * ManejadorDeErrores}. Es el caso vecino exacto del que cerro #486, y por eso el arreglo tiene la
 * misma forma: <b>un manejador, no un cambio en las ~84 operaciones de escritura del contrato</b>
 * —cualquiera de ellas pedida con {@code GET} producia esto—.
 *
 * <h2>Las tres consecuencias, y la que no se ve</h2>
 *
 * <p>El estado miente, el mensaje no dice que arreglar, y —la peor— <b>cada peticion escribe una
 * incidencia de nivel ERROR en el registro</b>. Un cliente mal escrito o un rastreador ensucian el
 * registro de errores del servicio sin que haya ningun error del servicio, y con eso el registro de
 * incidencias deja de servir para encontrar defectos reales. Por eso una de estas pruebas no mira
 * el codigo de estado sino el registro, igual que la sexta de {@link ElBordeContestaLoMismoTest}.
 *
 * <h2>Los dos contrastes</h2>
 *
 * <p>Convertirlo <b>todo</b> en 405 dejaria las cuatro primeras pruebas en verde y seria peor que
 * el defecto que arregla. Las dos ultimas lo impiden: una ruta que no existe sigue siendo {@code
 * 404} —lo unico que ya funcionaba bien—, y un defecto de verdad del servidor sigue siendo {@code
 * 500} con su incidencia y su linea de ERROR.
 */
@DisplayName("RNF-033 — Un verbo que la ruta no admite es 405 con Allow (#556)")
class ElVerboNoAdmitidoTest {

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new Sonda())
                    .setControllerAdvice(new ManejadorDeErrores())
                    .build();

    @Test
    @DisplayName("AC 1 — el verbo equivocado es 405 con codigo propio, no 500 ERROR_INTERNO")
    void elVerboEquivocadoEs405() throws Exception {
        MvcResult respuesta = mvc.perform(get("/sonda/ejercicio")).andReturn();

        assertThat(respuesta.getResponse().getStatus())
                .as(
                        "un 500 dice «el servidor se rompio»; lo que pasa es que el cliente pidio"
                                + " con el verbo equivocado, y eso es un 405")
                .isEqualTo(405);

        String cuerpo = respuesta.getResponse().getContentAsString();
        assertThat(cuerpo)
                .as(
                        "el codigo tiene que ser propio y estable: la interfaz ofrece «Reintentar»"
                                + " sobre ERROR_INTERNO, y reintentar un verbo equivocado no puede"
                                + " funcionar nunca")
                .contains(CodigoDeError.METODO_NO_ADMITIDO.name())
                .doesNotContain(CodigoDeError.ERROR_INTERNO.name());
        assertThat(cuerpo)
                .as("no es una incidencia: no lleva identificador que buscar en el registro")
                .doesNotContain(ManejadorDeErrores.CAMPO_INCIDENCIA);
    }

    @Test
    @DisplayName("AC 2 — la respuesta lleva la cabecera Allow con los verbos que si se admiten")
    void laRespuestaLlevaAllow() throws Exception {
        MvcResult respuesta = mvc.perform(get("/sonda/domicilios")).andReturn();

        assertThat(respuesta.getResponse().getStatus()).isEqualTo(405);
        assertThat(verbosDe(respuesta.getResponse().getHeader("Allow")))
                .as(
                        "es lo que un 405 tiene que decir por contrato HTTP, y lo unico que un"
                                + " cliente puede leer sin leer prosa")
                .containsExactlyInAnyOrder("POST", "DELETE");
    }

    @Test
    @DisplayName("AC 3 — y no escribe ninguna incidencia ni ninguna linea de nivel ERROR")
    void noEnsuciaElRegistroDeErrores() throws Exception {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ListAppender<ILoggingEvent> anotados = new ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        try {
            mvc.perform(get("/sonda/ejercicio"));
            mvc.perform(get("/sonda/domicilios"));
            mvc.perform(get("/sonda/contribuyente"));
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(anotados.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList())
                .as(
                        "es la consecuencia que no se ve en la respuesta y la que hace esto"
                                + " explotable como ruido: un rastreador dejaba una incidencia con"
                                + " su UUID por cada peticion, y con eso el registro de errores deja"
                                + " de servir para encontrar defectos reales")
                .isEmpty();
    }

    @Test
    @DisplayName("AC 4 — el mensaje nombra el verbo pedido y los admitidos, y nada mas")
    void elMensajeNoFiltraNada() throws Exception {
        MvcResult respuesta = mvc.perform(get("/sonda/ejercicio")).andReturn();

        String cuerpo = respuesta.getResponse().getContentAsString();
        assertThat(cuerpo)
                .as(
                        "los dos son del contrato publico de la ruta, asi que devolverlos no revela"
                                + " nada del esquema")
                .contains("GET")
                .contains("PUT");
        assertThat(cuerpo)
                .as("ni clase, ni paquete, ni pila, ni tabla, ni SQL (RNF-033)")
                .doesNotContain("kamayuk.rentas")
                .doesNotContain("org.springframework")
                .doesNotContain("java.")
                .doesNotContain("Exception")
                .doesNotContain("Sonda");
    }

    @Test
    @DisplayName("AC 5 — una ruta que NO existe sigue siendo 404, no 405")
    void laRutaInexistenteSigueSiendo404() throws Exception {
        MvcResult respuesta = mvc.perform(get("/sonda/no/existe")).andReturn();

        assertThat(respuesta.getResponse().getStatus())
                .as(
                        "es el contraste que impide pasarse: convertir todo en 405 dejaria el AC 1"
                                + " en verde y romperia lo unico que hoy funciona bien")
                .isEqualTo(404);
        assertThat(respuesta.getResponse().getContentAsString())
                .contains(CodigoDeError.NO_ENCONTRADO.name());
    }

    @Test
    @DisplayName("AC 6 — un defecto de verdad del servidor sigue siendo 500 con su incidencia")
    void loQueSiEsInternoNoSeDisfraza() throws Exception {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ListAppender<ILoggingEvent> anotados = new ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        MvcResult respuesta;
        try {
            respuesta = mvc.perform(get("/sonda/revienta")).andReturn();
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(respuesta.getResponse().getStatus())
                .as(
                        "el segundo contraste: el arreglo no puede comerse el caso que el manejador"
                                + " generico existe para atender")
                .isEqualTo(500);
        assertThat(respuesta.getResponse().getContentAsString())
                .contains(ManejadorDeErrores.CAMPO_INCIDENCIA);
        assertThat(anotados.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList())
                .as("y sigue dejando su rastro, que es con lo que se diagnostica")
                .isNotEmpty();
    }

    /** La cabecera se compara por su contenido y no letra a letra: el orden no significa nada. */
    private static java.util.List<String> verbosDe(String cabecera) {
        return Arrays.stream(Objects.requireNonNullElse(cabecera, "").split(","))
                .map(String::trim)
                .filter(verbo -> !verbo.isEmpty())
                .toList();
    }

    // ------------------------------------------------------------------

    /** Las tres formas que el issue midio contra la instalacion en marcha, y los dos contrastes. */
    @RestController
    static class Sonda {

        /** Como {@code PUT /seguridad/sesion/ejercicio}: un solo verbo admitido. */
        @PutMapping("/sonda/ejercicio")
        String ejercicio() {
            return "ok";
        }

        /** Como {@code POST /rentas/contribuyentes/{codigo}/domicilios}, aqui con dos verbos. */
        @PostMapping("/sonda/domicilios")
        String altaDeDomicilio() {
            return "ok";
        }

        @DeleteMapping("/sonda/domicilios")
        String bajaDeDomicilio() {
            return "ok";
        }

        /** Como {@code PUT /rentas/contribuyentes/{codigo}}. */
        @PutMapping("/sonda/contribuyente")
        String contribuyente() {
            return "ok";
        }

        @GetMapping("/sonda/revienta")
        String revienta() {
            throw new IllegalStateException("un defecto de verdad, con su rastro");
        }
    }
}
