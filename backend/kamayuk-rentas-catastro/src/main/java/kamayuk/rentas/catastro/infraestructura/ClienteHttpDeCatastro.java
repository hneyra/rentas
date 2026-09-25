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
import java.util.function.Function;
import kamayuk.rentas.catastro.FichaDelPadron;
import kamayuk.rentas.dominio.AreaM2;
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
 * huellas del padron—, este cliente pedia <b>nueve operaciones</b>.
 *
 * <p><b>Desde #9 son catorce</b>, y los puertos trece: la etapa 1 anadio las cuatro lecturas nuevas
 * que {@code catastro} publico en sus #4, #5, #6 y #7 —la zona urbanistica, el riesgo del suelo con
 * el ITSE, los frentes y los hallazgos de la fiscalizacion catastral—. Son cuatro puertos y cinco
 * operaciones porque el riesgo y el ITSE son dos rutas de ese sistema; el motivo esta en {@link
 * kamayuk.rentas.catastro.RiesgoYItseDelPredio}.
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
     *
     * <p><b>Hoy no la lanza ningun adaptador, y eso se dice para que nadie lo suponga al reves</b>:
     * el ultimo hueco de ruta que quedaba —los hallazgos de un predio— lo cerro `catastro`#17. Se
     * conserva porque la distincion que nombra es la que {@link EscrituraSinTransaccionCompartida}
     * usa de contraste, y porque el proximo puerto que se escriba antes que su ruta la necesita: lo
     * que no se conserva es la idea de que algun puerto siga sin poder preguntar.
     */
    public static final class SinRutaEnCatastro extends OperacionTodaviaNoCompletable {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public SinRutaEnCatastro(String que, String operacionQueLoServiria) {
            super(
                    LoQueFalta.LA_RUTA_DEL_VECINO,
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
    public static final class CatastroInalcanzable
            extends kamayuk.rentas.catastro.TerritorioInalcanzable {
        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: es un enum, que se serializa por su nombre.
        @SuppressWarnings("serial")
        private final MotivoDeInalcanzable motivo;

        public CatastroInalcanzable(String que, @Nullable Throwable causa) {
            this(MotivoDeInalcanzable.NO_CONTESTA, que, causa);
        }

        public CatastroInalcanzable(
                MotivoDeInalcanzable motivo, String que, @Nullable Throwable causa) {
            super("No se pudo " + que + ". El sistema del predio vive en `catastro`", causa);
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
    public static final class EscrituraSinTransaccionCompartida
            extends OperacionTodaviaNoCompletable {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public EscrituraSinTransaccionCompartida(String que, String conQuePasoTendriaQueConfirmar) {
            super(
                    LoQueFalta.LA_TRANSACCION_COMPARTIDA,
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
     * `catastro` contesto, y lo que contesta es que ese hecho del territorio <b>no consta</b>.
     *
     * <p>No es {@link CatastroInalcanzable} y esa distincion es todo el motivo de que sea otra
     * clase: se pregunto, se contesto, y la respuesta es un hecho. Las cuatro lecturas de C-5 no la
     * necesitan —ahi la ausencia viaja como campo, {@code enElPadron: false}—, pero las de
     * `catastro`#4 y #5 si: ese sistema contesta <b>422</b> cuando el predio esta y no tiene
     * poligono y <b>404</b> cuando ningun plan vigente lo cubre, y lo hace a proposito, porque un
     * 200 con la zona nula seria indistinguible de «este predio esta en zona nula» —que no admite
     * ningun giro—.
     *
     * <p>Colapsarlas en «catastro no responde» borraria de este lado justo la distincion que el
     * proveedor construyo, y mandaria a mirar un despliegue cuando lo que falta es cargar un plano
     * o aprobar una ordenanza.
     *
     * <p>El {@link #codigo()} es el del catalogo estable de {@code catastro} —{@code VALIDACION},
     * {@code NO_ENCONTRADO}—, y viaja como dato y no dentro de la frase por lo mismo que alli: un
     * texto en castellano se reescribe en cuanto alguien lo lee en voz alta.
     */
    public static final class NoConstaEnCatastro
            extends kamayuk.rentas.catastro.HechoDelTerritorioQueNoConsta {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String codigo;

        public NoConstaEnCatastro(String que, String codigo, String detalle) {
            super(
                    "No se puede "
                            + que
                            + ": `catastro` contesto «"
                            + codigo
                            + "» — "
                            + detalle
                            + ". No es que no se pudiera preguntar: se pregunto, contesto, y lo que"
                            + " contesta es que ese hecho del territorio no consta. Devolver vacio"
                            + " aqui diria que el predio no tiene lo que se pregunta, que es otra"
                            + " cosa");
            this.codigo = codigo;
        }

        /** El codigo del catalogo de `catastro`, para decidir sin analizar el mensaje. */
        @Override
        public String codigo() {
            return codigo;
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

    static @Nullable String texto(JsonNode fila, String campo) {
        JsonNode nodo = fila.path(campo);
        return nodo.isNull() || nodo.isMissingNode() ? null : nodo.asString();
    }

    /** Una fecha que puede no venir. Vacia y ausente son lo mismo aqui: no la hay. */
    static @Nullable LocalDate fecha(JsonNode fila, String campo) {
        String valor = texto(fila, campo);
        return valor == null || valor.isBlank() ? null : LocalDate.parse(valor);
    }

    /**
     * Una fecha que la respuesta TIENE que traer.
     *
     * <p>Falta la fecha ⇒ falla nombrandola, en vez de tomar una por omision: lo que llega sin su
     * fecha es una cifra que dentro de un mes es otra y nadie puede decir cual se dio (regla 9).
     */
    static LocalDate fechaObligatoria(JsonNode fila, String campo, String que) {
        String valor = texto(fila, campo);
        if (valor == null || valor.isBlank()) {
            throw new CatastroInalcanzable(
                    que
                            + ": la respuesta no trae «"
                            + campo
                            + "», y sin esa fecha lo que llega no se sabe de que dia es (regla 9)",
                    null);
        }
        try {
            return LocalDate.parse(valor);
        } catch (java.time.format.DateTimeParseException malFormada) {
            throw new CatastroInalcanzable(
                    que + ": «" + campo + "» llego como «" + valor + "», que no es una fecha",
                    null);
        }
    }

    /**
     * Lo que `catastro` contesto, antes de interpretarlo: su estado y su cuerpo.
     *
     * <p>Existe para que la interpretacion —que un 200 se lee, que un 4xx con codigo es un hecho y
     * que uno sin codigo es una averia— sea <b>codigo de produccion bajo prueba</b>: el doble de
     * las pruebas sustituye {@link #enviar}, que es lo unico que habla por la red, y no {@link
     * #pedir}. Con el doble puesto un escalon mas arriba, cada rama de esa interpretacion se
     * saltaba entera y ninguna prueba podia verla.
     */
    record RespuestaDeCatastro(int estado, String cuerpo) {}

    /** Manda la peticion y devuelve lo que llego. Es lo unico que toca la red. */
    RespuestaDeCatastro enviar(String ruta, String que) {
        if (raiz.isBlank()) {
            throw new CatastroInalcanzable(
                    MotivoDeInalcanzable.SIN_CONFIGURAR,
                    que + ": kamayuk.catastro.url no esta configurada",
                    null);
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
            return new RespuestaDeCatastro(respuesta.statusCode(), respuesta.body());
        } catch (IOException noContesta) {
            throw new CatastroInalcanzable(que, noContesta);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new CatastroInalcanzable(que, interrumpido);
        }
    }

    /**
     * Una lectura en la que todo lo que no sea 200 es una averia.
     *
     * <p>Es lo que corresponde a las nueve operaciones de P5C y C-5: se disenaron para que la
     * ausencia viajara <b>como campo</b>, asi que ahi un 404 no es «no hay», es que algo esta mal.
     *
     * <p><b>El cuadro de valores unitarios salio de aqui en #350</b>: su ausencia no viaja como
     * campo sino como 404 con codigo, y ese 404 es {@code EjercicioSinSellar} (ver {@link
     * ValoresUnitariosHttp}).
     */
    JsonNode pedir(String ruta, String que) {
        RespuestaDeCatastro respuesta = enviar(ruta, que);
        if (respuesta.estado() != 200) {
            throw new CatastroInalcanzable(que + " (contesto " + respuesta.estado() + ")", null);
        }
        return leer(respuesta.cuerpo(), que);
    }

    /**
     * Una lectura en la que un 4xx <b>con codigo</b> es un hecho del territorio y no una averia.
     *
     * <p>La usan las lecturas de `catastro`#4, #5 y #7, que contestan con el catalogo de errores de
     * ese sistema: ver {@link NoConstaEnCatastro}. Un 4xx <b>sin</b> codigo —el HTML de un proxy,
     * una ruta que no existe— sigue siendo una averia, y ese contraste es lo que impide que «no
     * consta» se trague tambien los fallos de verdad.
     */
    JsonNode pedirHechoDelTerritorio(String ruta, String que) {
        return pedirTraduciendoLosHechos(
                ruta, que, hecho -> new NoConstaEnCatastro(que, hecho.codigo(), hecho.detalle()));
    }

    /**
     * Una lectura cuyo adaptador sabe que hecho del dominio es cada respuesta de {@code catastro}
     * distinta de 200 (#350).
     *
     * <p>Lo que decide <b>si</b> una respuesta es un hecho y no una averia es {@link
     * #hechoContestado}, y solo el; lo que el adaptador decide es <b>que</b> hecho es —el 404 del
     * cuadro de valores unitarios es {@code EjercicioSinSellar}, el de la zonificacion es «no
     * consta»—. Lo que no es un hecho sale como {@link CatastroInalcanzable} sin pasar por la
     * traduccion, asi que ningun adaptador puede convertir una caida en un dato.
     *
     * @param traduccion el hecho que {@code catastro} contesto, convertido en la excepcion del
     *     puerto; la que devuelve se lanza
     */
    JsonNode pedirTraduciendoLosHechos(
            String ruta, String que, Function<HechoContestado, RuntimeException> traduccion) {
        RespuestaDeCatastro respuesta = enviar(ruta, que);
        if (respuesta.estado() == 200) {
            return leer(respuesta.cuerpo(), que);
        }
        HechoContestado hecho =
                hechoContestado(respuesta)
                        .orElseThrow(
                                () ->
                                        new CatastroInalcanzable(
                                                que + " (contesto " + respuesta.estado() + ")",
                                                null));
        throw traduccion.apply(hecho);
    }

    /**
     * El criterio, en UN solo sitio, de que respuesta distinta de 200 es un hecho del dominio y no
     * una averia (#350).
     *
     * <p>Hoy lo es la que trae el {@code codigo} del catalogo de errores de {@code catastro}: un
     * cuerpo sin el —la pagina de un proxy, una ruta que no existe— no es una respuesta de ese
     * sistema. Hasta #350 este criterio vivia dentro de {@link #pedirHechoDelTerritorio} y el
     * cuadro de valores unitarios no lo usaba: cualquier cosa distinta de 200 era averia, y el 404
     * de un ejercicio sin sellar llegaba a la ficha del FUE como 500. Tenerlo aqui es lo que deja
     * afinarlo una vez para todas las lecturas que lo usan (relacionado con #352: que un codigo con
     * un estado de averia no cuente como hecho).
     */
    Optional<HechoContestado> hechoContestado(RespuestaDeCatastro respuesta) {
        JsonNode cuerpo = leerSiSePuede(respuesta.cuerpo());
        String codigo = cuerpo.path("codigo").asString("");
        if (codigo.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(
                new HechoContestado(
                        respuesta.estado(), codigo, cuerpo.path("detail").asString("")));
    }

    /**
     * Lo que {@code catastro} contesto cuando lo que contesta es un hecho: su estado, su codigo y
     * su detalle.
     *
     * <p>Viaja el estado porque el mismo codigo no dice lo mismo en todas las rutas: el adaptador
     * que lo traduce es el que sabe que significa un 404 en la suya.
     */
    record HechoContestado(int estado, String codigo, String detalle) {

        /**
         * El hecho, cuando el adaptador no tiene ninguna traduccion para el: una averia que lo
         * nombra entero, para que quien opera sepa que contesto {@code catastro}.
         */
        CatastroInalcanzable comoAveria(String que) {
            return new CatastroInalcanzable(
                    que + " (contesto " + estado + " «" + codigo + "»: " + detalle + ")", null);
        }
    }

    private JsonNode leer(String cuerpo, String que) {
        try {
            return json.readTree(cuerpo);
        } catch (JacksonException ilegible) {
            // Jackson 3 no lanza `IOException` sino `JacksonException`, que es NO COMPROBADA
            // (C-7). Sin este `catch` un cuerpo que no es JSON —el HTML de un proxy, por
            // ejemplo— saldria como una excepcion cruda de una libreria en vez de como «catastro
            // no contesta lo que dice contestar», que es lo que quien opera necesita leer.
            throw new CatastroInalcanzable(que, ilegible);
        }
    }

    /** El cuerpo de un error, cuando se puede leer. Si no es JSON, no hay codigo que sacar. */
    private JsonNode leerSiSePuede(String cuerpo) {
        try {
            return json.readTree(cuerpo);
        } catch (JacksonException noEsJson) {
            return json.createObjectNode();
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
