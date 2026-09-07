package kamayuk.rentas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.LinkedHashMap;
import java.util.Map;
import kamayuk.rentas.catastro.infraestructura.ClienteHttpDeCatastro;
import kamayuk.rentas.tesoreria.infraestructura.ClienteHttpDeCaja;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las tres escrituras imposibles dejan de disfrazarse del mismo error interno (#40, AC-4).
 *
 * <h2>Lo que hay que medir NO es el codigo de estado</h2>
 *
 * <p>Este issue existe porque tres caminos distintos —una ruta que el vecino no publica, una
 * escritura que necesita el protocolo de ADR-0027, y una averia de verdad— salian <b>byte a byte
 * iguales</b>: {@code 500} con {@code codigo: ERROR_INTERNO}, el mensaje del catalogo y un numero
 * de incidencia. Una prueba que solo comprobara «sale un error» no separa ninguna de las tres, y
 * una que comprobara solo el estado tampoco separaria las dos primeras entre si.
 *
 * <p>Asi que lo que se afirma es la <b>distinguibilidad</b>: los cuatro escenarios se piden por el
 * mismo camino, con el mismo manejador de errores, y ninguna pareja puede salir igual. Es el
 * criterio de {@code errores.mjs} del frontend de `catastro`, aplicado al borde HTTP de este
 * backend.
 *
 * <h2>Y por que un controlador de mentira</h2>
 *
 * <p>Porque los tres endpoints de verdad viven en tres modulos distintos y cada uno arrastra medio
 * contexto —{@code ConvenioController} pide seis colaboradores, {@code ResolucionController} otros
 * tantos—. Lo que se mide aqui es el <b>manejador</b>: que las tres excepciones de produccion,
 * lanzadas tal cual las lanza produccion, salgan por {@code ManejadorDeErrores} distinguibles. Que
 * cada endpoint las lance lo miden sus propias pruebas y el censo de {@code
 * EscriturasQueNoPuedenTerminarTest}.
 */
@DisplayName("#40 — las tres escrituras imposibles no salen todas como ERROR_INTERNO")
class EscriturasImposiblesSeDistinguenTest {

    /** Un borde con las tres excepciones de produccion y una averia de verdad al lado. */
    @RestController
    static class BordeDeMentira {

        @PostMapping("/sin-ruta-en-caja")
        public String sinRutaEnCaja() {
            throw new ClienteHttpDeCaja.SinRutaEnCaja(
                    "si el recibo 77 esta anulado", "GET caja/api/v1/recibos/por-id/{reciboId}");
        }

        @PostMapping("/sin-ruta-en-catastro")
        public String sinRutaEnCatastro() {
            throw new ClienteHttpDeCatastro.SinRutaEnCatastro(
                    "los hallazgos del predio 11", "GET catastro/api/v1/hallazgos/{predioId}");
        }

        @PostMapping("/sin-transaccion-compartida")
        public String sinTransaccionCompartida() {
            throw new ClienteHttpDeCatastro.EscrituraSinTransaccionCompartida(
                    "inscribir lo hallado en el predio 11",
                    "la resolucion de determinacion, sus cargos y la fila que los ata");
        }

        /** El contraste: una averia de verdad, que SI tiene que salir como ERROR_INTERNO. */
        @PostMapping("/una-averia-de-verdad")
        public String unaAveriaDeVerdad() {
            throw new IllegalStateException("el pool se quedo sin conexiones");
        }
    }

    private static final MockMvc MVC =
            MockMvcBuilders.standaloneSetup(new BordeDeMentira())
                    .setControllerAdvice(new ManejadorDeErrores())
                    .build();

    private static final Map<String, String> ESCENARIOS =
            new LinkedHashMap<>(
                    Map.of(
                            "sin-ruta-en-caja", "falta publicar la ruta en `caja`",
                            "sin-ruta-en-catastro", "falta publicar la ruta en `catastro`",
                            "sin-transaccion-compartida", "falta el protocolo de ADR-0027",
                            "una-averia-de-verdad", "una averia de verdad"));

    // ------------------------------------------------------------------

    @Test
    @DisplayName("las tres salen 501 OPERACION_NO_DISPONIBLE, y la averia sigue saliendo 500")
    void lasTresSalenConSuCodigo() throws Exception {
        for (String ruta : ESCENARIOS.keySet()) {
            boolean esAveria = ruta.equals("una-averia-de-verdad");
            var respuesta = MVC.perform(post("/" + ruta)).andReturn().getResponse();

            assertThat(respuesta.getStatus())
                    .as(
                            "«%s» (%s): hasta #40 los cuatro salian 500 y quien opera recibia"
                                    + " «avise a soporte» ante una limitacion de diseno conocida,"
                                    + " escrita y argumentada",
                            ruta, ESCENARIOS.get(ruta))
                    .isEqualTo(esAveria ? 500 : 501);
            assertThat(respuesta.getContentAsString())
                    .as(
                            "«%s»: el codigo es lo unico estable, y es a lo que reacciona la interfaz",
                            ruta)
                    .contains(
                            esAveria
                                    ? "\"codigo\":\"ERROR_INTERNO\""
                                    : "\"codigo\":\"OPERACION_NO_DISPONIBLE\"");
        }
    }

    @Test
    @DisplayName("y el mensaje NOMBRA que falta: la ruta que lo serviria, o el protocolo")
    void elMensajeNombraQueFalta() throws Exception {
        assertThat(cuerpoDe("sin-ruta-en-caja"))
                .as(
                        "sin esto, el unico texto que explica la causa se sustituye por «No se pudo"
                                + " completar la operacion», que es deliberadamente inutil (#40)")
                .contains("GET caja/api/v1/recibos/por-id/{reciboId}")
                .contains("LA_RUTA_DEL_VECINO");
        assertThat(cuerpoDe("sin-ruta-en-catastro"))
                .contains("GET catastro/api/v1/hallazgos/{predioId}")
                .contains("LA_RUTA_DEL_VECINO");
        assertThat(cuerpoDe("sin-transaccion-compartida"))
                .contains("ADR-0027")
                .contains("LA_TRANSACCION_COMPARTIDA");
    }

    @Test
    @DisplayName("y la averia NO dice ni una palabra de la causa, pero SI su incidencia")
    void laAveriaSigueSiendoOpaca() throws Exception {
        String cuerpo = cuerpoDe("una-averia-de-verdad");

        assertThat(cuerpo)
                .as(
                        "el detalle de un 500 va al registro y no a la respuesta: es la unica forma"
                                + " de no filtrar el esquema sin renunciar a diagnosticar")
                .doesNotContain("pool")
                .contains("\"incidencia\"");
        assertThat(cuerpoDe("sin-transaccion-compartida"))
                .as(
                        "y al reves: una limitacion de diseno NO lleva incidencia, porque no hay"
                                + " nada que investigar en ningun registro")
                .doesNotContain("\"incidencia\"");
    }

    @Test
    @DisplayName("EL ENTREGABLE: ninguna pareja de los cuatro sale byte a byte igual")
    void ningunaParejaSaleIgual() throws Exception {
        Map<String, String> cuerpos = new LinkedHashMap<>();
        for (String ruta : ESCENARIOS.keySet()) {
            cuerpos.put(ruta, sinLoQueVariaSolo(cuerpoDe(ruta)));
        }

        assertThat(cuerpos)
                .as("sin escenarios esta comprobacion se cumpliria sola sobre el conjunto vacio")
                .hasSize(4);

        for (Map.Entry<String, String> uno : cuerpos.entrySet()) {
            for (Map.Entry<String, String> otro : cuerpos.entrySet()) {
                if (uno.getKey().compareTo(otro.getKey()) >= 0) {
                    continue;
                }
                assertThat(uno.getValue())
                        .as(
                                "«%s» (%s) y «%s» (%s) salen IGUALES, que es exactamente el defecto"
                                        + " de #40: tres cosas que se arreglan de tres maneras"
                                        + " distintas —publicar una ruta en otro repositorio,"
                                        + " construir ADR-0027, o llamar a soporte— indistinguibles"
                                        + " para quien las recibe",
                                uno.getKey(),
                                ESCENARIOS.get(uno.getKey()),
                                otro.getKey(),
                                ESCENARIOS.get(otro.getKey()))
                        .isNotEqualTo(otro.getValue());
            }
        }
    }

    // ------------------------------------------------------------------

    private static String cuerpoDe(String ruta) throws Exception {
        return MVC.perform(post("/" + ruta)).andReturn().getResponse().getContentAsString();
    }

    /**
     * Fuera lo que varia por si solo: el numero de incidencia y la ruta que se pidio.
     *
     * <p><b>Los dos hubo que medirlos, y el segundo se descubrio con la rotura puesta.</b> El
     * numero de incidencia es un UUID nuevo en cada peticion, asi que sin quitarlo dos 500 seguidos
     * ya salen distintos. Y {@code instance} lo rellena Spring con el {@code requestURI}: con el
     * {@code @ExceptionHandler} de AC-4 retirado —o sea con el defecto de #40 puesto sobre {@code
     * src/main}— las cuatro respuestas salen <b>identicas salvo por la ruta que quien pregunta ya
     * conocia</b>:
     *
     * <pre>{@code
     * {"detail":"No se pudo completar la operacion","instance":"/sin-ruta-en-caja",
     *  "status":500,"codigo":"ERROR_INTERNO", ...}
     * {"detail":"No se pudo completar la operacion","instance":"/una-averia-de-verdad",
     *  "status":500,"codigo":"ERROR_INTERNO", ...}
     * }</pre>
     *
     * <p>Y esta prueba —que es EL ENTREGABLE de #40— salia <b>PASSED</b>. O sea que sin esta
     * segunda sustitucion, «ninguna pareja sale byte a byte igual» se cumple con las cuatro
     * diciendo exactamente lo mismo sobre cuatro problemas distintos, y lo unico que las separa es
     * el dato que no aporta nada: a que URL se llamo. Es la trampa que la comparacion existe para
     * no caer, y la comparacion habia caido en ella.
     */
    private static String sinLoQueVariaSolo(String cuerpo) {
        return cuerpo.replaceAll("\"incidencia\":\"[^\"]*\"", "\"incidencia\":\"(cualquiera)\"")
                .replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"(cualquiera)\"");
    }
}
