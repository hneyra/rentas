package kamayuk.rentas.catastro.infraestructura;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.catastro.FichaDelPadron;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * El unico camino de {@code rentas} hacia {@code catastro} (P5C, ADR-0029 y ADR-0030).
 *
 * <h2>Que sustituyo, y que no cambio</h2>
 *
 * <p>Hasta P5C, los nueve puertos del paquete raiz los implementaban clases de este mismo modulo
 * que leian {@code predio}, {@code ficha_catastral} y {@code titularidad} de esta base. `V6` retiro
 * esas tablas: el sistema del predio vive en otro repositorio. <b>Los puertos no se tocaron</b> —ya
 * eran el contrato, y por eso las veintisiete clases de `src/main` que los consumen no cambiaron ni
 * una linea—; lo unico que cambio es quien los implementa.
 *
 * <p>Es exactamente lo que P5B hizo con {@code kamayuk-rentas-parametros}: el modulo se queda como
 * <b>adaptador cliente</b>, con sus puertos y su transporte, sin dominio y sin una sola consulta.
 *
 * <h2>SIETE DE LOS NUEVE PUERTOS NO TIENEN HOY QUIEN LOS CONTESTE, Y HAY QUE DECIRLO</h2>
 *
 * <p>Las rutas que ADR-0030 fija para esta frontera —{@code GET /predios/&#123;id&#125;/titulares},
 * {@code GET /predios/&#123;id&#125;/caracteristicas}, {@code POST
 * /predios/&#123;id&#125;/titularidad}, {@code POST /predios/&#123;id&#125;/transferencia-fiscal},
 * las de valuacion— <b>todavia no las publica `catastro`</b>. Sus controladores sirven hoy la
 * grilla de fichas, el listado de predios, el resumen predial y las escrituras de titularidad e
 * inquilinos, y nada mas.
 *
 * <p>Asi que este cliente hace dos cosas distintas segun el puerto:
 *
 * <ul>
 *   <li><b>Los dos que SI se pueden pedir</b> —la grilla de fichas y el cuadro de valores
 *       unitarios— salen por HTTP contra la ruta que `catastro` publica de verdad.
 *   <li><b>Los siete restantes lanzan {@link SinRutaEnCatastro}</b>, que nombra la operacion de
 *       ADR-0030 que los serviria. <b>No devuelven vacio</b>, y esa es toda la decision: una lista
 *       vacia se lee como «este contribuyente no tiene predios» y un {@code Optional.empty()} como
 *       «este predio no tiene ficha». Las dos son respuestas plausibles y falsas, y la
 *       determinacion predial saldria con la base a cero sin que ninguna cifra pareciera mal. Es el
 *       mismo criterio con que {@code ValorizacionDelFue} devuelve su motivo en vez de un cero
 *       (#48), y el que {@code LectorDeValoresUnitarios} ya tenia escrito: «no devuelve vacio y no
 *       devuelve ceros».
 * </ul>
 *
 * <p>Esto NO es una regresion que introduzca P5C: es la que P5C <b>hace visible</b>. Mientras las
 * tablas seguian aqui, `rentas` era dueno de un catastro que ya vivia en otro repositorio y la
 * frontera era mentira. Lo que falta esta declarado en `P5C-extraccion.md` §13.
 *
 * <h2>El contexto de municipalidad no viaja en ningun parametro</h2>
 *
 * <p>Ni en el cuerpo, ni en la ruta, ni en una cabecera propia (ADR-0028). Ninguno de los nueve
 * puertos recibe {@code municipalidadId} —la regla 2 lo prohibe— y este cliente no lo inventa:
 * reenvia el {@code Authorization} de la peticion que se atiende, y `catastro` valida ESE token y
 * fija su propio {@code SET LOCAL}. El intercambio por un token delegado (RFC 8693) no esta
 * construido; ver el hueco 6 del entregable.
 */
@Component
public class ClienteHttpDeCatastro {

    private static final Duration ESPERA_DE_CONEXION = Duration.ofSeconds(5);
    private static final Duration ESPERA_DE_LECTURA = Duration.ofSeconds(30);

    private final HttpClient cliente;
    private final ObjectMapper json;
    private final String raiz;

    public ClienteHttpDeCatastro(
            ObjectMapper json, @Value("${kamayuk.catastro.url:}") String raiz) {
        this.json = json;
        this.raiz = raiz.endsWith("/") ? raiz.substring(0, raiz.length() - 1) : raiz;
        this.cliente = HttpClient.newBuilder().connectTimeout(ESPERA_DE_CONEXION).build();
    }

    // ------------------------------------------------------------------

    /**
     * `catastro` no publica todavia la ruta que serviria esta pregunta.
     *
     * <p>No es «no hay dato» y no es «catastro esta caido»: es que la operacion no existe. Se
     * distingue de las otras dos a proposito, porque se arregla de otra manera —publicandola— y
     * decir cualquiera de las otras dos mandaria a mirar una cola o un despliegue.
     */
    public static final class SinRutaEnCatastro extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public SinRutaEnCatastro(String que, String operacionQueLoServiria) {
            super(
                    "No se puede pedir "
                            + que
                            + ": `catastro` todavia no publica la operacion que lo serviria ("
                            + operacionQueLoServiria
                            + ", ADR-0030). Hasta que la publique, esta lectura no tiene de donde"
                            + " salir — y devolver vacio diria que el predio no tiene lo que se"
                            + " pregunta, que es otra cosa");
        }
    }

    /** `catastro` no contesta. No es «eso no existe»: es que no se pudo preguntar. */
    public static final class CatastroInalcanzable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public CatastroInalcanzable(String que, @Nullable Throwable causa) {
            super("No se pudo " + que + ". El sistema del predio vive en `catastro`", causa);
        }
    }

    static void anadir(StringBuilder ruta, String nombre, @Nullable String valor) {
        if (valor != null && !valor.isBlank()) {
            ruta.append('&')
                    .append(nombre)
                    .append('=')
                    .append(URLEncoder.encode(valor, StandardCharsets.UTF_8));
        }
    }

    static FichaDelPadron ficha(JsonNode fila) {
        return new FichaDelPadron(
                fila.path("fichaId").asLong(),
                fila.path("predioId").asLong(),
                fila.path("codRefCatastral").asText(""),
                fila.path("direccion").asText(""),
                texto(fila, "manzana"),
                texto(fila, "lote"),
                fila.path("tipo").asText(""),
                fila.path("version").asInt(),
                AreaM2.de(fila.path("areaTerreno").asText("0")),
                fila.path("areaConstruida").isNull() || fila.path("areaConstruida").isMissingNode()
                        ? null
                        : AreaM2.de(fila.path("areaConstruida").asText()),
                fila.path("uso").asText(""),
                LocalDate.parse(fila.path("vigenciaDesde").asText()),
                texto(fila, "titular"));
    }

    private static @Nullable String texto(JsonNode fila, String campo) {
        JsonNode nodo = fila.path(campo);
        return nodo.isNull() || nodo.isMissingNode() ? null : nodo.asText();
    }

    JsonNode pedir(String ruta, String que) {
        if (raiz.isBlank()) {
            throw new CatastroInalcanzable(
                    que + ": kamayuk.catastro.url no esta configurada", null);
        }
        HttpRequest.Builder peticion =
                HttpRequest.newBuilder(URI.create(raiz + ruta))
                        .timeout(ESPERA_DE_LECTURA)
                        .header("Accept", "application/json")
                        .GET();
        token().ifPresent(t -> peticion.header("Authorization", t));
        try {
            HttpResponse<String> respuesta =
                    cliente.send(peticion.build(), HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() != 200) {
                throw new CatastroInalcanzable(
                        que + " (contesto " + respuesta.statusCode() + ")", null);
            }
            return json.readTree(respuesta.body());
        } catch (IOException noContesta) {
            throw new CatastroInalcanzable(que, noContesta);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new CatastroInalcanzable(que, interrumpido);
        }
    }

    private static Optional<String> token() {
        RequestAttributes atributos = RequestContextHolder.getRequestAttributes();
        if (!(atributos instanceof ServletRequestAttributes servlet)) {
            return Optional.empty();
        }
        return Optional.ofNullable(servlet.getRequest().getHeader("Authorization"));
    }
}
