package kamayuk.rentas.tesoreria.infraestructura;

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
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * El unico camino de {@code rentas} hacia {@code caja} (P5D, ADR-0026 y ADR-0029).
 *
 * <h2>Que sustituyo, y que no cambio</h2>
 *
 * <p>Hasta P5D, los tres puertos del paquete raiz que publican la caja —{@link
 * kamayuk.rentas.tesoreria.RecibosDeTramite}, {@link kamayuk.rentas.tesoreria.AvanceDeCaja} y
 * {@link kamayuk.rentas.tesoreria.CobrosDeTasas}— los implementaban clases de este mismo modulo que
 * leian {@code recibo}, {@code recibo_detalle}, {@code recibo_movimiento} y {@code cierre_caja} de
 * esta base. `V7` retiro esas diez tablas: el sistema del dinero vive en otro repositorio.
 *
 * <p><b>Los puertos no se tocaron</b> —ya eran el contrato desde #44, #50 y #56, y por eso las
 * clases de {@code licencias}, {@code sanciones} e {@code indicadores} que los consumen no
 * cambiaron ni una linea—; lo unico que cambio es quien los implementa. Es exactamente lo que P5B
 * hizo con {@code kamayuk-rentas-parametros} y P5C con {@code kamayuk-rentas-catastro}.
 *
 * <h2>PERO ESTE MODULO NO QUEDA COMO ADAPTADOR CLIENTE A SECAS, Y ES LA DIFERENCIA CON P5C</h2>
 *
 * <p>{@code kamayuk-rentas-tesoreria} <b>se partio</b>, no se fue: el CONVENIO DE FRACCIONAMIENTO
 * se queda en {@code rentas} con su dominio, sus cinco tablas y sus dos repositorios, porque un
 * convenio es <b>deuda reprogramada</b> —tiene interes, tiene quiebre y tiene consecuencias
 * coactivas— y si viajara a {@code caja}, {@code caja} adquiriria reglas tributarias y dejaria de
 * poder cobrar un puesto de mercado (ADR-0026 §5). Asi que el modulo hace hoy DOS cosas, y por eso
 * conserva {@code sgtm.pruebas-postgres}: lleva un contexto acotado con tablas de verdad, y ademas
 * es el adaptador cliente de {@code caja}.
 *
 * <h2>Nunca vacio y nunca cero</h2>
 *
 * <p>Cuando {@code caja} no contesta, este cliente <b>lanza</b>. La alternativa —devolver {@code
 * Optional.empty()} o {@code Dinero.CERO}— es exactamente lo que no se puede hacer: un {@code
 * Optional.empty()} de {@link kamayuk.rentas.tesoreria.RecibosDeTramite#porNumeroImpreso} significa
 * <b>«ese recibo no existe»</b>, y quien lo consume —{@code licencias}, RF-110— emitiria la
 * licencia de funcionamiento <b>sin haber cobrado el derecho de tramite</b>, con su correlativo
 * gastado y su papel firmado. Un cero de {@link kamayuk.rentas.tesoreria.AvanceDeCaja} diria que la
 * ventanilla no ha cobrado nada hoy. Las dos son respuestas plausibles y falsas, y ninguna cifra
 * pareceria mal: es el criterio de #48 con la licencia que salia con «valor de obra 0,00», y el que
 * los propios puertos ya llevaban escrito.
 *
 * <p>Por eso hay <b>dos</b> excepciones y no una, y se distinguen porque se arreglan de manera
 * distinta:
 *
 * <ul>
 *   <li>{@link CajaInalcanzable} — {@code caja} no contesta. Se arregla levantando un despliegue, o
 *       mirando la red. Mandar a alguien a publicar una ruta que ya existe seria perder el dia.
 *   <li>{@link SinRutaEnCaja} — la operacion no existe todavia. Se arregla publicandola en {@code
 *       caja}, y el mensaje la nombra. Mandar a alguien a mirar una cola o un despliegue seria
 *       perder el dia al reves.
 * </ul>
 *
 * <h2>El 404 es la UNICA respuesta que puede volver vacia, y solo en dos sitios</h2>
 *
 * <p>{@code GET /recibos/{numero}} y {@code GET /tasas/{codigo}/cobros/{numero}} contestan 404
 * cuando ese recibo no existe o no cobro ese concepto, y ahi el {@code Optional.empty()} <b>es la
 * respuesta</b> y no una falta de dato: es lo que los dos puertos ya prometian —«vacio si el numero
 * no existe o no tiene la forma de un numero de recibo»—. La diferencia con lo de arriba es que ahi
 * <b>se pregunto y contestaron</b>. Cualquier otro codigo sale como {@link CajaInalcanzable}.
 *
 * <h2>Los importes llegan como CADENA</h2>
 *
 * <p>{@code Dinero} se serializa con {@code writeString} (RNF-055, regla 1), asi que {@code
 * "35.00"} llega entrecomillado y se lee con {@link kamayuk.rentas.dominio.Dinero#de(String)}.
 * Leerlo como numero JSON lo haria pasar por un {@code double} y la precision monetaria se perderia
 * en el transporte, que es el sitio donde nadie mira.
 *
 * <h2>El contexto de municipalidad no viaja en ningun parametro</h2>
 *
 * <p>Ni en el cuerpo, ni en la ruta, ni en una cabecera propia (ADR-0028). Ninguno de los tres
 * puertos recibe {@code municipalidadId} —la regla 2 lo prohibe— y este cliente no lo inventa:
 * reenvia el {@code Authorization} de la peticion que se atiende, y {@code caja} valida ESE token y
 * fija su propio {@code SET LOCAL}. El intercambio por un token delegado (RFC 8693) no esta
 * construido; es el mismo hueco que P5C declaro para {@code catastro}.
 */
@Component
public class ClienteHttpDeCaja {

    private static final Duration ESPERA_DE_CONEXION = Duration.ofSeconds(5);
    private static final Duration ESPERA_DE_LECTURA = Duration.ofSeconds(30);

    private final HttpClient cliente;
    private final ObjectMapper json;
    private final String raiz;

    public ClienteHttpDeCaja(ObjectMapper json, @Value("${kamayuk.caja.url:}") String raiz) {
        this.json = json;
        this.raiz = raiz.endsWith("/") ? raiz.substring(0, raiz.length() - 1) : raiz;
        this.cliente = HttpClient.newBuilder().connectTimeout(ESPERA_DE_CONEXION).build();
    }

    // ------------------------------------------------------------------

    /**
     * `caja` no publica todavia la ruta que serviria esta pregunta.
     *
     * <p>No es «no hay dato» y no es «caja esta caida»: es que la operacion no existe. Se distingue
     * de las otras dos a proposito, porque se arregla de otra manera —publicandola— y decir
     * cualquiera de las otras dos mandaria a mirar una cola o un despliegue.
     */
    public static final class SinRutaEnCaja extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public SinRutaEnCaja(String que, String operacionQueLoServiria) {
            super(
                    "No se puede pedir "
                            + que
                            + ": `caja` todavia no publica la operacion que lo serviria ("
                            + operacionQueLoServiria
                            + ", ADR-0026). Hasta que la publique, esta lectura no tiene de donde"
                            + " salir — y devolver vacio diria que el recibo no existe, que es otra"
                            + " cosa");
        }
    }

    /** `caja` no contesta. No es «eso no existe»: es que no se pudo preguntar. */
    public static final class CajaInalcanzable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public CajaInalcanzable(String que, @Nullable Throwable causa) {
            super("No se pudo " + que + ". El sistema del dinero vive en `caja`", causa);
        }
    }

    // ------------------------------------------------------------------

    /**
     * Un segmento de ruta, codificado.
     *
     * <p>Hace falta de verdad y no por prudencia: un numero de recibo se imprime {@code
     * 001-0000123} pero lo que llega a ventanilla es lo que el administrado teclea, y un espacio o
     * una barra partirian la ruta en dos segmentos y {@code caja} contestaria 404 —«ese recibo no
     * existe»— sobre un recibo que si existe. {@code URLEncoder} escribe {@code +} por el espacio,
     * que en una ruta no significa espacio, asi que se corrige a {@code %20}.
     */
    static String segmento(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Un valor de parametro de consulta, codificado. Ahi el {@code +} SI significa espacio. */
    static String parametro(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8);
    }

    /**
     * Pide, y devuelve el cuerpo.
     *
     * @throws CajaInalcanzable si no contesta, o si contesta cualquier cosa que no sea 200
     */
    JsonNode pedir(String ruta, String que) {
        return pedirSiExiste(ruta, que)
                .orElseThrow(() -> new CajaInalcanzable(que + " (contesto 404)", null));
    }

    /**
     * Pide, y trata el 404 como una respuesta y no como un fallo.
     *
     * <p>Solo lo usan las dos lecturas cuyo puerto promete vacio cuando no existe. Ver el javadoc
     * de la clase: en las demas, un vacio se leeria como un dato.
     */
    Optional<JsonNode> pedirSiExiste(String ruta, String que) {
        if (raiz.isBlank()) {
            throw new CajaInalcanzable(que + ": kamayuk.caja.url no esta configurada", null);
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
            if (respuesta.statusCode() == 404) {
                return Optional.empty();
            }
            if (respuesta.statusCode() != 200) {
                throw new CajaInalcanzable(
                        que + " (contesto " + respuesta.statusCode() + ")", null);
            }
            return Optional.of(json.readTree(respuesta.body()));
        } catch (IOException noContesta) {
            throw new CajaInalcanzable(que, noContesta);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new CajaInalcanzable(que, interrumpido);
        }
    }

    /**
     * Manda un cuerpo JSON, y devuelve lo que la caja conteste.
     *
     * <p>Es la UNICA escritura de este cliente. Admite {@code 200} y {@code 201} por igual: la caja
     * usa el codigo para decir si la orden era nueva o ya estaba, y las dos son exito. Cualquier
     * otra cosa sale como {@link CajaInalcanzable} — incluido un 4xx, y a proposito: una orden que
     * la caja rechaza es un defecto de este sistema al componerla, y devolver un identificador
     * inventado dejaria al contribuyente delante de una ventanilla que no encuentra su deuda.
     */
    JsonNode publicar(String ruta, String cuerpo, String que) {
        if (raiz.isBlank()) {
            throw new CajaInalcanzable(que + ": kamayuk.caja.url no esta configurada", null);
        }
        HttpRequest.Builder peticion =
                HttpRequest.newBuilder(URI.create(raiz + ruta))
                        .timeout(ESPERA_DE_LECTURA)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(cuerpo));
        token().ifPresent(t -> peticion.header("Authorization", t));
        try {
            HttpResponse<String> respuesta =
                    cliente.send(peticion.build(), HttpResponse.BodyHandlers.ofString());
            int estado = respuesta.statusCode();
            if (estado != 200 && estado != 201) {
                throw new CajaInalcanzable(que + " (contesto " + estado + ")", null);
            }
            return json.readTree(respuesta.body());
        } catch (IOException noContesta) {
            throw new CajaInalcanzable(que, noContesta);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new CajaInalcanzable(que, interrumpido);
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
