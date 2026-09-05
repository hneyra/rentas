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
import kamayuk.rentas.nucleo.dominio.proyeccion.TipoDeHechoDeCatastro;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Trae los hechos del buzon de {@code catastro} y los acusa (C-8, ADR-0026 §3).
 *
 * <h2>Corre SIN USUARIO DELANTE, y eso decide como se autentica</h2>
 *
 * <p>Lo llama un proceso por lotes: no hay ninguna peticion en curso de la que sacar un {@code
 * Authorization}, asi que se manda una credencial de servicio configurada. <b>Si no la hay, la
 * llamada sale sin credencial y el destino la rechaza</b>, que es deliberado. El intercambio por un
 * token delegado (RFC 8693, ADR-0028 §2) no esta construido en ninguno de los cuatro repositorios y
 * queda declarado como hueco.
 *
 * <h2>Todo fallo de aqui es TRANSITORIO</h2>
 *
 * <p>Todo lo que este cliente lanza es {@link FuenteDeHechosDeCatastro.CatastroNoContesta},
 * incluido un cuerpo que no es JSON: desde este lado no se puede distinguir «catastro esta mal» de
 * «hay un proxy delante contestando HTML», y las dos se arreglan mirando el despliegue. Lo que
 * <b>no</b> puede pasar es que un fallo de transporte mate un hecho: eso lo mataria por un motivo
 * que iba a arreglarse solo.
 *
 * <h2>Un tipo de hecho desconocido NO se lee, y se dice</h2>
 *
 * <p>Si {@code catastro} publica un cuarto tipo, este lado lo rechaza nombrandolo en vez de
 * ignorarlo. Ignorarlo lo dejaria en el buzon del emisor para siempre —nunca se acusaria— o, peor,
 * lo acusaria sin aplicarlo. Es el motivo por el que el enumerado esta copiado en los dos lados.
 */
public class ClienteHttpDelBuzonDeCatastro implements FuenteDeHechosDeCatastro {

    private static final Duration ESPERA_DE_CONEXION = Duration.ofSeconds(5);
    private static final Duration ESPERA_DE_LECTURA = Duration.ofSeconds(30);

    /** La ruta del buzon, tal como `catastro` la publica (`EventosController`). */
    private static final String BUZON = "/catastro/api/v1/catastro/eventos";

    private final HttpClient cliente;
    private final JsonMapper json;
    private final String raiz;
    private final String credencial;

    public ClienteHttpDelBuzonDeCatastro(JsonMapper json, String raiz, String credencial) {
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
            throw new CatastroNoContesta(
                    "`catastro` contesto "
                            + respuesta.statusCode()
                            + " al acusar. Los hechos SI estan aplicados aqui: se volveran a"
                            + " servir y se descartaran por deduplicacion");
        }
    }

    // ------------------------------------------------------------------

    private HechoRecibido leer(JsonNode evento) {
        String tipo = evento.path("tipo").asString("");
        TipoDeHechoDeCatastro conocido;
        try {
            conocido = TipoDeHechoDeCatastro.valueOf(tipo);
        } catch (IllegalArgumentException desconocido) {
            throw new CatastroNoContesta(
                    "`catastro` publica un hecho de tipo «"
                            + tipo
                            + "», que este sistema no sabe aplicar. No se acusa: aplicarlo a"
                            + " medias dejaria la proyeccion diciendo algo que nadie escribio, y"
                            + " acusarlo sin aplicarlo lo perderia. Hay que anadir el tipo a"
                            + " TipoDeHechoDeCatastro y decidir que hace con el la proyeccion");
        }
        try {
            return new HechoRecibido(
                    UUID.fromString(evento.path("eventoId").asString()),
                    evento.path("secuencia").asLong(),
                    conocido,
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
            throw new CatastroNoContesta(
                    "`catastro` contesto " + respuesta.statusCode() + " al " + que);
        }
        try {
            return json.readTree(respuesta.body());
        } catch (JacksonException ilegible) {
            throw new CatastroNoContesta(
                    "`catastro` contesto algo que no es JSON al " + que, ilegible);
        }
    }

    private void conCredencial(HttpRequest.Builder peticion) {
        if (!credencial.isBlank()) {
            peticion.header("Authorization", credencial);
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
