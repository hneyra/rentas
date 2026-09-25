package kamayuk.rentas.nucleo.infraestructura.ingestor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kamayuk.rentas.nucleo.dominio.proyeccion.FuenteDeHechosDeCatastro;
import kamayuk.rentas.nucleo.dominio.proyeccion.HechoRecibido;
import kamayuk.rentas.plataforma.CredencialDeServicio;
import kamayuk.rentas.plataforma.RespuestaAjena;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Trae los hechos del buzon de {@code catastro} y los acusa (C-8, ADR-0026 §3).
 *
 * <h2>Corre SIN USUARIO DELANTE, y eso decide como se autentica</h2>
 *
 * <p>Lo llama un proceso por lotes: no hay ninguna peticion en curso de la que sacar un {@code
 * Authorization}. Desde #21 AC-2 <b>pide el suyo</b> con {@code client_credentials} y la clave de
 * su cliente confidencial —uno por sistema y municipalidad, ADR-0028 §2—, en vez de mandar una
 * cadena configurada que ningun emisor firmo. Quien lo pide y lo guarda es {@link
 * TokenDeServicioDeKeycloak}; este cliente solo sabe que hay una {@link CredencialDeServicio} y que
 * puede tardar, porque un token se renueva.
 *
 * <p>Sin identidad configurada la llamada sale sin credencial y el destino la rechaza, que sigue
 * siendo lo correcto: es lo que hace que el compose sin Keycloak no se pase la vida pidiendo tokens
 * que nadie va a dar.
 *
 * <h2>Todo fallo de aqui es TRANSITORIO</h2>
 *
 * <p>Todo lo que este cliente lanza es {@link FuenteDeHechosDeCatastro.CatastroNoContesta},
 * incluido un cuerpo que no es JSON: desde este lado no se puede distinguir «catastro esta mal» de
 * «hay un proxy delante contestando HTML», y las dos se arreglan mirando el despliegue. Lo que
 * <b>no</b> puede pasar es que un fallo de transporte mate un hecho: eso lo mataria por un motivo
 * que iba a arreglarse solo.
 *
 * <h2>Un tipo de hecho que este sistema no sabe aplicar SI se lee, y se decide despues</h2>
 *
 * <p><b>Hasta #54 se rechazaba aqui, y ese era el defecto.</b> {@code valueOf} lanzaba {@link
 * FuenteDeHechosDeCatastro.CatastroNoContesta} <b>mientras se armaba el lote</b>, o sea antes de
 * que ningun hecho llegara al aplicador; y como el buzon entero viene en una pagina, un solo hecho
 * del territorio se llevaba por delante la vuelta ENTERA —cero aplicados, con los predios que iban
 * delante dentro— y la siguiente traia lo mismo. Encima el tipo de la excepcion enganaba por su
 * cuenta: quien atendiera leia «catastro no contesta» —falso, contesta perfectamente— y miraba el
 * despliegue.
 *
 * <p>Ahora el hecho se lee con su nombre de tipo <b>tal como el emisor lo escribio</b> y quien
 * decide es {@link kamayuk.rentas.nucleo.aplicacion.IngestarHechosDeCatastro}, hecho a hecho: lo
 * que no se sabe aplicar se aparta con {@code SIN_CAPACIDAD:<tipo>} y se acusa (#377), y la vuelta
 * sigue.
 *
 * <p><b>Y el buzon entero NO viene en una pagina</b>, que es lo que #54 dio por hecho y #377 midio
 * falso: se pide como mucho {@code ?limite=}, y el emisor sirve lo no acusado por orden. Por eso
 * nada que no se acuse puede quedarse en la cabeza para siempre.
 *
 * <p>Lo que sigue siendo un fallo de transporte es un hecho <b>sin tipo</b>: eso no es una
 * capacidad que falte sino una respuesta que no tiene la forma de un hecho, y se arregla mirando el
 * despliegue.
 *
 * <h2>Y lo que el otro sistema contesto VIAJA en el mensaje (#166)</h2>
 *
 * <p><b>Hasta #166 aqui se decia el codigo de estado y se tiraba el cuerpo.</b> No es el defecto de
 * su hermano —este nunca invento una causa, {@code ClienteHttpDelBuzonDeIdentidad} si (#66)— pero
 * deja el mismo agujero: medido en `stg`, el {@code CronJob} del ingestor lleva <b>todas</b> sus
 * corridas en rojo con «`catastro` contesto 403 al leer el buzon de catastro», y con eso no se
 * puede saber cual de las tres ramas del 403 de `catastro` es, que se arreglan en tres sitios
 * distintos:
 *
 * <ul>
 *   <li>{@code SIN_MUNICIPALIDAD} — el token no lleva el claim que acota la corrida. Es del emisor
 *       y de la cuenta de servicio del cliente confidencial, no de ningun permiso.
 *   <li>{@code SIN_PRIVILEGIO} + «no esta dada de alta» — la cuenta no tiene ficha en `catastro`.
 *       Concederle un acceso no lo arregla.
 *   <li>{@code SIN_PRIVILEGIO} + «no tiene el privilegio» — la cuenta existe y le falta {@code
 *       consulta_fichas}, que es el acceso con el que `catastro` publica su buzon.
 * </ul>
 *
 * <p>Lo que contesto viaja tachado y recortado por {@link RespuestaAjena}: un eco de la peticion
 * con la cabecera {@code Authorization} dentro seria el token de servicio de esta municipalidad en
 * un registro.
 */
public class ClienteHttpDelBuzonDeCatastro implements FuenteDeHechosDeCatastro {

    private static final Duration ESPERA_DE_CONEXION = Duration.ofSeconds(5);
    private static final Duration ESPERA_DE_LECTURA = Duration.ofSeconds(30);

    /** La ruta del buzon, tal como `catastro` la publica (`EventosController`). */
    private static final String BUZON = "/catastro/api/v1/catastro/eventos";

    private final HttpClient cliente;
    private final JsonMapper json;
    private final String raiz;
    private final CredencialDeServicio credencial;

    public ClienteHttpDelBuzonDeCatastro(
            JsonMapper json, String raiz, CredencialDeServicio credencial) {
        this.json = json;
        this.raiz = raiz.endsWith("/") ? raiz.substring(0, raiz.length() - 1) : raiz;
        this.credencial = credencial;
        this.cliente = HttpClient.newBuilder().connectTimeout(ESPERA_DE_CONEXION).build();
    }

    @Override
    public Lote pendientes(int limite) {
        JsonNode cuerpo = pedir(BUZON + "?limite=" + limite, "leer el buzon de catastro");
        List<HechoRecibido> hechos = new ArrayList<>();
        for (JsonNode evento : cuerpo.path("eventos")) {
            hechos.add(leer(evento));
        }
        return new Lote(List.copyOf(hechos), cuerpo.path("pendientesQueQuedan").asLong(0));
    }

    @Override
    public void acusar(List<UUID> eventoIds) {
        if (eventoIds.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (UUID id : eventoIds) {
            ids.add(id.toString());
        }
        String cuerpo = escribir(new PeticionDeAcuse(List.copyOf(ids)));
        HttpRequest.Builder peticion =
                HttpRequest.newBuilder(URI.create(raiz + BUZON + "/acuse"))
                        .timeout(ESPERA_DE_LECTURA)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(cuerpo));
        conCredencial(peticion);
        HttpResponse<String> respuesta = enviar(peticion, "acusar los hechos aplicados");
        if (respuesta.statusCode() != 200) {
            throw noContesto(
                    respuesta.statusCode(),
                    respuesta.body(),
                    "acusar. Los hechos SI estan aplicados aqui: se volveran a servir y se"
                            + " descartaran por deduplicacion");
        }
    }

    // ------------------------------------------------------------------

    private HechoRecibido leer(JsonNode evento) {
        try {
            return new HechoRecibido(
                    UUID.fromString(evento.path("eventoId").asString()),
                    evento.path("secuencia").asLong(),
                    evento.path("tipo").asString(""),
                    evento.path("predioId").isNull() || evento.path("predioId").isMissingNode()
                            ? null
                            : evento.path("predioId").asLong(),
                    evento.path("ejercicio").isNull() || evento.path("ejercicio").isMissingNode()
                            ? null
                            : evento.path("ejercicio").asInt(),
                    evento.path("cuerpo").asString(""),
                    evento.path("huella").asString(""),
                    Instant.parse(evento.path("emitidoEn").asString()));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException malFormado) {
            throw new CatastroNoContesta(
                    "El buzon de `catastro` contesto algo que no tiene la forma de un hecho: "
                            + malFormado.getMessage());
        }
    }

    private JsonNode pedir(String ruta, String que) {
        if (raiz.isBlank()) {
            throw new CatastroNoContesta(que + ": kamayuk.catastro.url no esta configurada");
        }
        HttpRequest.Builder peticion =
                HttpRequest.newBuilder(URI.create(raiz + ruta))
                        .timeout(ESPERA_DE_LECTURA)
                        .header("Accept", "application/json")
                        .GET();
        conCredencial(peticion);
        HttpResponse<String> respuesta = enviar(peticion, que);
        if (respuesta.statusCode() != 200) {
            throw noContesto(respuesta.statusCode(), respuesta.body(), que);
        }
        try {
            return json.readTree(respuesta.body());
        } catch (JacksonException ilegible) {
            throw new CatastroNoContesta(
                    "`catastro` contesto algo que no es JSON al " + que, ilegible);
        }
    }

    /**
     * El mensaje de un estado que no es 200, con lo que `catastro` contesto DENTRO (#166).
     *
     * <p>No es estatico porque necesita el {@link JsonMapper}: leer el {@code codigo} del {@code
     * problem+json} es la diferencia entre «contesto 403» —que no se puede diagnosticar— y decir
     * cual de sus tres 403 es.
     *
     * <p>Y <b>no inventa</b> una rama cuando el emisor no la dijo: lo dice. Esa es la mitad del
     * defecto de #66 que aqui nunca hubo y que no se va a estrenar.
     */
    private CatastroNoContesta noContesto(int estado, String cuerpo, String que) {
        RespuestaAjena contesto = RespuestaAjena.de(json, cuerpo);
        String mensaje =
                "`catastro` contesto "
                        + estado
                        + " al "
                        + que
                        + ", y contesto "
                        + contesto.comoTexto();
        if (estado == 401) {
            return new CatastroNoContesta(
                    mensaje
                            + ". La credencial de servicio no vale o no se manda"
                            + " (kamayuk.rentas.ingestor.identidad.cliente,"
                            + " kamayuk.catastro.credencial). Se arregla en el despliegue, no en"
                            + " el hecho");
        }
        if (estado == 403) {
            return new CatastroNoContesta(mensaje + ". " + remedioDel403(contesto));
        }
        return new CatastroNoContesta(mensaje);
    }

    /**
     * Que hay que ir a arreglar, segun lo que `catastro` DIJO.
     *
     * <p>Sus dos {@code SIN_PRIVILEGIO} llevan el mismo codigo —los separa su texto, {@code
     * GuardiaDeAcceso}— asi que aqui se mira el detalle. El remedio va <b>detras</b> del cuerpo
     * literal a proposito: si el emisor reescribe su frase, quien lee el registro sigue teniendo
     * delante lo que contesto de verdad.
     */
    private static String remedioDel403(RespuestaAjena contesto) {
        if (contesto.sinDecirPorQue()) {
            return "Y NO dijo por que, asi que aqui no se elige ninguna rama: hay que mirar quien"
                    + " contesto, que puede no ser `catastro` sino un proxy delante";
        }
        if (contesto.dice("SIN_MUNICIPALIDAD")) {
            return "El token NO identifica una municipalidad: no falta ningun permiso — el claim"
                    + " sale de la cuenta de servicio del cliente confidencial"
                    + " kamayuk-rentas-servicio-<ubigeo> en el emisor (ADR-0028 §2)";
        }
        if (contesto.dice("no esta dada de alta")) {
            return "La cuenta de servicio NO tiene ficha en `catastro`: concederle un acceso no lo"
                    + " arregla — o falta el alta en la copia local de `catastro`, o el claim con"
                    + " que la busca no es el que el token trae";
        }
        if (contesto.dice("privilegio")) {
            return "La cuenta existe y le falta el acceso «consulta_fichas», que es con el que"
                    + " `catastro` publica su buzon —LECTURA para leerlo y REGISTRO para"
                    + " acusarlo—. Se concede en `identidad`, no aqui";
        }
        return "El «codigo» que contesto no es ninguno de los que este cliente sabe leer: el"
                + " remedio esta en lo que dijo, arriba";
    }

    /**
     * Pone la cabecera si la hay.
     *
     * <p>Se pide AQUI y no en el constructor: un token caduca, y uno pedido al construir el bean
     * estaria muerto en la vuelta de la noche siguiente.
     */
    private void conCredencial(HttpRequest.Builder peticion) {
        String cabecera;
        try {
            cabecera = credencial.cabecera();
        } catch (CredencialDeServicio.NoSePudoObtener sinToken) {
            // La credencial vive en `plataforma` y no sabe de que buzon es: aqui se vuelve
            // «catastro no contesta», que es la excepcion transitoria de ESTE camino y la que la
            // vuelta reintenta. El mensaje del emisor viaja entero: es el que dice que falta.
            throw new CatastroNoContesta(String.valueOf(sinToken.getMessage()), sinToken);
        }
        if (!cabecera.isBlank()) {
            peticion.header("Authorization", cabecera);
        }
    }

    private HttpResponse<String> enviar(HttpRequest.Builder peticion, String que) {
        try {
            return cliente.send(peticion.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException noContesta) {
            throw new CatastroNoContesta("No se pudo " + que, noContesta);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new CatastroNoContesta("Se interrumpio al " + que, interrumpido);
        }
    }

    private String escribir(Object cuerpo) {
        try {
            return json.writeValueAsString(cuerpo);
        } catch (JacksonException noSePuede) {
            throw new IllegalStateException("No se pudo componer el acuse", noSePuede);
        }
    }

    /** Lo que se manda al acusar. Es la forma que {@code EventosController} de `catastro` lee. */
    private record PeticionDeAcuse(List<String> eventoIds) {}
}
