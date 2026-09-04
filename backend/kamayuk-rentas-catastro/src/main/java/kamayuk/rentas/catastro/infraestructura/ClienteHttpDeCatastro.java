package kamayuk.rentas.catastro.infraestructura;

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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
 * <h2>Que de `catastro` se puede pedir hoy, y que no (C-5)</h2>
 *
 * <p>P5C dejo <b>siete de los nueve puertos sin ninguna ruta que los contestara</b>, y lo llamo lo
 * mas caro que dejaba aquella etapa. C-5 publico las cinco lecturas que faltaban —{@code GET
 * /catastro/predios/&#123;id&#125;}, {@code &#8230;/caracteristicas}, {@code
 * /catastro/fichas/&#123;id&#125;/area}, {@code /catastro/titularidad} y sus dos hermanas— y las
 * conecto. Con las tres que ya salian —la grilla de fichas, el cuadro de valores unitarios y las
 * huellas del padron—, este cliente pide hoy <b>nueve operaciones</b>.
 *
 * <p>Lo que queda son las <b>dos escrituras</b>, y ya no lanzan {@link SinRutaEnCatastro}: eso
 * seria mentir, porque publicar la ruta no las arregla. Lanzan {@link
 * EscrituraSinTransaccionCompartida}, que dice lo que de verdad falta — que la escritura de {@code
 * catastro} y las que la rodean en este backend confirmen o se deshagan juntas. El motivo entero,
 * con la medida de #52 que lo respalda, esta en {@link TitularidadHttp} y en {@link
 * SinRutaTodavia}.
 *
 * <p><b>Y lo que no cambia es que ninguna de las dos devuelve vacio.</b> Una lista vacia se lee
 * como «este contribuyente no tiene predios» y un {@code Optional.empty()} como «este predio no
 * tiene ficha»: las dos son respuestas plausibles y falsas, y la determinacion predial saldria con
 * la base a cero sin que ninguna cifra pareciera mal. Es el mismo criterio con que {@code
 * ValorizacionDelFue} devuelve su motivo en vez de un cero (#48).
 *
 * <p>Lo que si cambia, ahora que las lecturas contestan, es <b>cuando</b> una lista vacia es un
 * dato: lo es cuando la respuesta viene de quien se pregunto y de la fecha que se pregunto, y las
 * dos cosas se comprueban antes de leer una fila (ver {@link #exigirQueContesteALaFecha} y el
 * guardia de {@link PrediosDelContribuyenteHttp}).
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
    private final JsonMapper json;
    private final String raiz;

    public ClienteHttpDeCatastro(JsonMapper json, @Value("${kamayuk.catastro.url:}") String raiz) {
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

    /**
     * La operacion existe y no se puede pedir por HTTP <b>sin perder la atomicidad</b> (C-5).
     *
     * <p>No es {@link SinRutaEnCatastro} y esa distincion es todo el motivo de que sea otra clase:
     * publicar la ruta no la arregla. Lo que falta es que la escritura de {@code catastro} y las
     * que la rodean en {@code rentas} confirmen o se deshagan juntas, y dos bases y dos procesos no
     * comparten transaccion. Se arregla con un protocolo —reserva y confirmacion, o el buzon de
     * eventos de ADR-0027, que todavia no existe—, no con un controlador.
     *
     * <p>Se lanza en vez de escribir a medias, y eso es deliberado: #52 midio la mutacion contraria
     * —dejar que la ficha nueva sobreviviera al fallo de un paso posterior— y salieron <b>12 fichas
     * donde debe haber 11</b>, o sea el padron cambiado sin resolucion que lo justifique y sin
     * cargo que cobrar.
     */
    public static final class EscrituraSinTransaccionCompartida extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public EscrituraSinTransaccionCompartida(String que, String conQuePasoTendriaQueConfirmar) {
            super(
                    "No se puede "
                            + que
                            + " por HTTP: `catastro` confirmaria su escritura por su cuenta y "
                            + conQuePasoTendriaQueConfirmar
                            + " ocurre despues, en otra base y en otra transaccion. Un fallo entre"
                            + " las dos dejaria el padron cambiado sin el acto que lo justifica"
                            + " (#52). Lo que falta no es la ruta: es el protocolo que las hace"
                            + " confirmar juntas (ADR-0027)");
        }
    }

    /**
     * Que la respuesta este resuelta con la fecha que se pidio, y no con otra.
     *
     * <p>Las lecturas de esta frontera devuelven {@code aLaFecha}: la fecha con la que {@code
     * catastro} resolvio, no la que llego en la URL. Compararla aqui es lo unico que caza desde
     * este lado el defecto que C-1 encontro —el parametro viajaba con otro nombre, se descartaba en
     * silencio y la respuesta salia con el reloj del servidor—, porque el unico que sabe que fecha
     * se pidio es quien la pidio.
     */
    static void exigirQueContesteALaFecha(JsonNode cuerpo, LocalDate pedida, String que) {
        String contestada = cuerpo.path("aLaFecha").asString("");
        if (!pedida.toString().equals(contestada)) {
            throw new CatastroInalcanzable(
                    que
                            + ": se pidio al "
                            + pedida
                            + " y la respuesta dice estar resuelta al «"
                            + contestada
                            + "». Leerla seria contestar con lo vigente en otra fecha, que es lo"
                            + " que la regla 9 existe para impedir",
                    null);
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
                fila.path("codRefCatastral").asString(""),
                fila.path("direccion").asString(""),
                texto(fila, "manzana"),
                texto(fila, "lote"),
                fila.path("tipo").asString(""),
                fila.path("version").asInt(),
                AreaM2.de(fila.path("areaTerreno").asString("0")),
                fila.path("areaConstruida").isNull() || fila.path("areaConstruida").isMissingNode()
                        ? null
                        : AreaM2.de(fila.path("areaConstruida").asString()),
                fila.path("uso").asString(""),
                LocalDate.parse(fila.path("vigenciaDesde").asString()),
                texto(fila, "titular"));
    }

    private static @Nullable String texto(JsonNode fila, String campo) {
        JsonNode nodo = fila.path(campo);
        return nodo.isNull() || nodo.isMissingNode() ? null : nodo.asString();
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
        } catch (JacksonException ilegible) {
            // Jackson 3 no lanza `IOException` sino `JacksonException`, que es NO COMPROBADA
            // (C-7). Sin este `catch` un cuerpo que no es JSON —el HTML de un proxy, por
            // ejemplo— saldria como una excepcion cruda de una libreria en vez de como «catastro
            // no contesta lo que dice contestar», que es lo que quien opera necesita leer.
            throw new CatastroInalcanzable(que, ilegible);
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
