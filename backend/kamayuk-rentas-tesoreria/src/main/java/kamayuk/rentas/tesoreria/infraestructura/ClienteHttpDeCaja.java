package kamayuk.rentas.tesoreria.infraestructura;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.MotivoDeInalcanzable;
import kamayuk.rentas.dominio.OperacionTodaviaNoCompletable;
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
 * conserva {@code kamayuk.pruebas-postgres}: lleva un contexto acotado con tablas de verdad, y
 * ademas es el adaptador cliente de {@code caja}.
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
 * <h2>El 404 es una RESPUESTA, y lo que significa lo decide cada lectura</h2>
 *
 * <p>{@code GET /recibos/{numero}} y {@code GET /tasas/{codigo}/cobros/{numero}} contestan 404
 * cuando ese recibo no existe o no cobro ese concepto, y ahi el {@code Optional.empty()} <b>es la
 * respuesta</b> y no una falta de dato: es lo que los dos puertos ya prometian —«vacio si el numero
 * no existe o no tiene la forma de un numero de recibo»—. La diferencia con lo de arriba es que ahi
 * <b>se pregunto y contestaron</b>. Cualquier otro codigo sale como {@link CajaInalcanzable}.
 *
 * <p><b>Y desde #40 hay una tercera que lo pide vacio y NO lo devuelve vacio.</b> {@code GET
 * /recibos/por-id/{reciboId}} contesta 404 cuando ese identificador no existe, y ahi el vacio no
 * puede llegar al puerto: {@code AnulacionesDeRecibo.estaAnulado} devuelve un booleano, y un {@code
 * false} significa «ese recibo sigue vigente». {@code AnulacionesDeReciboHttp} lo traduce a {@code
 * AnulacionesDeRecibo.ReciboQueNoConsta}, que es una tercera respuesta con su propio remedio. Lo
 * que este cliente decide es el TRANSPORTE —que un 404 es una respuesta y no un fallo—; que
 * significa lo decide quien pregunta.
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
    private final JsonMapper json;
    private final String raiz;

    public ClienteHttpDeCaja(JsonMapper json, @Value("${kamayuk.caja.url:}") String raiz) {
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
     *
     * <p><b>Hoy no la lanza ningun adaptador, y eso se dice para que nadie lo suponga al reves</b>:
     * el unico hueco de ruta que quedaba —si un recibo esta anulado, por su identificador interno—
     * lo cerro #40, y el muñon que lo lanzaba se borro con el. Se conserva por lo mismo que su
     * gemela {@code SinRutaEnCatastro}: el proximo puerto que se escriba antes que su ruta la
     * necesita, y desde #40 sale como {@code 501 OPERACION_NO_DISPONIBLE} en vez de como un 500 con
     * numero de incidencia. Lo que no se conserva es la idea de que algun puerto de esta frontera
     * siga sin poder preguntar.
     */
    public static final class SinRutaEnCaja extends OperacionTodaviaNoCompletable {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public SinRutaEnCaja(String que, String operacionQueLoServiria) {
            super(
                    LoQueFalta.LA_RUTA_DEL_VECINO,
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

        // El aviso [serial] no aplica: es un enum, que se serializa por su nombre.
        @SuppressWarnings("serial")
        private final MotivoDeInalcanzable motivo;

        public CajaInalcanzable(String que, @Nullable Throwable causa) {
            this(MotivoDeInalcanzable.NO_CONTESTA, que, causa);
        }

        public CajaInalcanzable(
                MotivoDeInalcanzable motivo, String que, @Nullable Throwable causa) {
            super("No se pudo " + que + ". El sistema del dinero vive en `caja`", causa);
            this.motivo = motivo;
        }

        /**
         * Si falto la variable de entorno o si el vecino no contesto (#25, AC-4).
         *
         * <p>Viaja como dato y no dentro de la frase: quien decide mirando el texto deja de decidir
         * bien en cuanto alguien reescribe el mensaje, y nada se pone rojo.
         */
        public MotivoDeInalcanzable motivo() {
            return motivo;
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
     * Lo que llego por el cable, sin interpretar.
     *
     * <p>Existe para que {@link #enviar} sea la <b>unica</b> costura que un doble de prueba
     * sustituye. Es la leccion de #9 con {@code catastro}: mientras el doble sustituia {@code
     * pedir}, lo que {@code pedir} DECIDE —que un 200 se lee, que un 404 es una respuesta y que un
     * cuerpo ilegible es «la caja no contesta lo que dice contestar»— no lo ejercia ninguna prueba.
     */
    record RespuestaDeCaja(int estado, String cuerpo) {}

    /** Manda la peticion y devuelve lo que llego. Es lo unico que toca la red. */
    RespuestaDeCaja enviar(String ruta, String que) {
        exigirRaiz(que);
        HttpRequest.Builder peticion =
                HttpRequest.newBuilder(URI.create(raiz + ruta))
                        .timeout(ESPERA_DE_LECTURA)
                        .header("Accept", "application/json")
                        .GET();
        return mandar(peticion, que);
    }

    /** Lo mismo para la unica escritura, por lo mismo: que se pueda espiar el cuerpo que sale. */
    RespuestaDeCaja enviarCuerpo(String ruta, String cuerpo, String que) {
        exigirRaiz(que);
        HttpRequest.Builder peticion =
                HttpRequest.newBuilder(URI.create(raiz + ruta))
                        .timeout(ESPERA_DE_LECTURA)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(cuerpo));
        return mandar(peticion, que);
    }

    private void exigirRaiz(String que) {
        if (raiz.isBlank()) {
            throw new CajaInalcanzable(
                    MotivoDeInalcanzable.SIN_CONFIGURAR,
                    que + ": kamayuk.caja.url no esta configurada",
                    null);
        }
    }

    private RespuestaDeCaja mandar(HttpRequest.Builder peticion, String que) {
        token().ifPresent(t -> peticion.header("Authorization", t));
        try {
            HttpResponse<String> respuesta =
                    cliente.send(peticion.build(), HttpResponse.BodyHandlers.ofString());
            return new RespuestaDeCaja(respuesta.statusCode(), respuesta.body());
        } catch (IOException noContesta) {
            throw new CajaInalcanzable(que, noContesta);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new CajaInalcanzable(que, interrumpido);
        }
    }

    // ------------------------------------------------------------------
    //  Leer un campo de la respuesta: o esta, o la lectura falla nombrandolo
    // ------------------------------------------------------------------

    /**
     * Un campo de texto que TIENE que estar, leido por su camino dentro del cuerpo.
     *
     * <p><b>Aqui no hay valor por omision, y ese es todo el punto</b> (#41 AC-2). {@code
     * path("cobrado").asString("0")} sobre un nodo que no es escalar —un objeto {@code {importe,
     * actualizadoA}}, por ejemplo— <b>no da error</b>: devuelve el «0». Y un cero publicado ahi
     * pone el panel de recaudacion en «0,00 cobrado hoy» con la ventanilla cobrando, al lado de
     * tres cifras del libro que si estan bien: es la cifra plausible y falsa de #48, y el sintoma
     * es que no hay sintoma.
     *
     * <p>El camino se escribe con puntos —{@code «cobrado.importe»}— y el mensaje lo nombra entero,
     * porque quien lee el registro necesita saber <b>que campo</b> falta y no que «algo» no cuadro.
     */
    static String exigirTexto(JsonNode cuerpo, String camino, String que) {
        JsonNode nodo = cuerpo;
        for (String paso : camino.split("\\.")) {
            nodo = nodo.path(paso);
        }
        if (nodo.isMissingNode() || nodo.isNull() || !nodo.isValueNode()) {
            throw new CajaInalcanzable(
                    que
                            + ": `caja` no publica «"
                            + camino
                            + "» como un valor de texto (llego "
                            + descripcion(nodo)
                            + "). No se lee con un valor por omision: un importe o una fecha que se"
                            + " degradaran a «0» o a hoy no se distinguirian de los buenos",
                    null);
        }
        return nodo.asString();
    }

    /** Que llego de verdad en ese camino, para que el rojo diga algo. */
    private static String descripcion(JsonNode nodo) {
        if (nodo.isMissingNode()) {
            return "nada: el campo no esta";
        }
        if (nodo.isNull()) {
            return "null";
        }
        String comoTexto = nodo.toString();
        return (comoTexto.length() > 120 ? comoTexto.substring(0, 120) + "…" : comoTexto);
    }

    /**
     * Un booleano que TIENE que estar, leido por su camino dentro del cuerpo.
     *
     * <p><b>Es el sintoma mudo de C-1 en su forma mas cara</b> (#40 AC-2): {@code
     * path("anulado").asBoolean(false)} sobre un nodo que no esta <b>no da error</b> — devuelve
     * {@code false}. Y ahi {@code false} no es «no se sabe»: significa «ese recibo sigue vigente»,
     * que es la respuesta que <b>impide</b> anular el convenio. Un campo renombrado del otro lado
     * de la frontera no se veria como un fallo sino como una regla de negocio que empieza a
     * rechazar siempre, y quien la sufre no tiene forma de distinguirla de la de verdad.
     *
     * <p>Y por el otro lado es peor: si algun dia el valor por omision fuera {@code true}, un campo
     * ausente dejaria anular un convenio con su cuota inicial cobrada y viva. Por eso no hay valor
     * por omision en ninguna direccion.
     */
    static boolean exigirBooleano(JsonNode cuerpo, String camino, String que) {
        JsonNode nodo = cuerpo;
        for (String paso : camino.split("\\.")) {
            nodo = nodo.path(paso);
        }
        if (!nodo.isBoolean()) {
            throw new CajaInalcanzable(
                    que
                            + ": `caja` no publica «"
                            + camino
                            + "» como un booleano (llego "
                            + descripcion(nodo)
                            + "). No se lee con un valor por omision: un «false» inventado dice"
                            + " «ese recibo sigue vigente», que es una respuesta y no una falta de"
                            + " dato",
                    null);
        }
        return nodo.asBoolean();
    }

    /** Un importe de esta frontera. Viaja como cadena (RNF-055, regla 1). */
    static Dinero exigirDinero(JsonNode cuerpo, String camino, String que) {
        String texto = exigirTexto(cuerpo, camino, que);
        try {
            return Dinero.de(texto);
        } catch (NumberFormatException noEsUnImporte) {
            // `NumberFormatException` y no `RuntimeException`: es la que lanza el
            // `BigDecimal` de `Dinero.de`, y Checkstyle prohibe la ancha con razon —taparia
            // ademas cualquier defecto de este metodo—. Sale como «la caja no contesta lo que
            // dice contestar» y no como una excepcion cruda de un objeto de valor: quien lee el
            // registro necesita el campo y el valor, y quien la caza —`PanelDeRecaudacion`—
            // solo conoce el tipo del puerto.
            throw new CajaInalcanzable(
                    que
                            + ": `caja` publica «"
                            + camino
                            + "» = '"
                            + texto
                            + "', que no es un"
                            + " importe",
                    noEsUnImporte);
        }
    }

    /** Una fecha de esta frontera, en ISO. Toda cifra indica su fecha (regla 9, RNF-075). */
    static LocalDate exigirFecha(JsonNode cuerpo, String camino, String que) {
        String texto = exigirTexto(cuerpo, camino, que);
        try {
            return LocalDate.parse(texto);
        } catch (DateTimeParseException malEscrita) {
            throw new CajaInalcanzable(
                    que
                            + ": `caja` publica «"
                            + camino
                            + "» = '"
                            + texto
                            + "', que no es una fecha"
                            + " ISO",
                    malEscrita);
        }
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
     * <p>Lo usan las dos lecturas cuyo puerto promete vacio cuando no existe, y —desde #40— la del
     * estado de un recibo por su identificador, que <b>no</b> lo propaga como vacio sino que lo
     * traduce a su propia respuesta. Ver el javadoc de la clase: lo que este metodo decide es que
     * un 404 no es un fallo; que significa lo decide quien pregunta, y en las demas lecturas un
     * vacio se leeria como un dato.
     */
    Optional<JsonNode> pedirSiExiste(String ruta, String que) {
        RespuestaDeCaja respuesta = enviar(ruta, que);
        if (respuesta.estado() == 404) {
            return Optional.empty();
        }
        if (respuesta.estado() != 200) {
            throw new CajaInalcanzable(que + " (contesto " + respuesta.estado() + ")", null);
        }
        try {
            return Optional.of(json.readTree(respuesta.cuerpo()));
        } catch (JacksonException ilegible) {
            // Jackson 3 no lanza `IOException` sino `JacksonException`, que es NO COMPROBADA
            // (C-7). Sin este `catch` un cuerpo que no es JSON —el HTML de un proxy, por
            // ejemplo— saldria como una excepcion cruda de una libreria en vez de como «caja
            // no contesta lo que dice contestar», que es lo que quien opera necesita leer.
            throw new CajaInalcanzable(que, ilegible);
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
        RespuestaDeCaja respuesta = enviarCuerpo(ruta, cuerpo, que);
        int estado = respuesta.estado();
        if (estado != 200 && estado != 201) {
            throw new CajaInalcanzable(que + " (contesto " + estado + ")", null);
        }
        try {
            return json.readTree(respuesta.cuerpo());
        } catch (JacksonException ilegible) {
            throw new CajaInalcanzable(que, ilegible);
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
