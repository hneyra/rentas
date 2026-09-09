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
 * que no se sabe aplicar se ignora con un aviso {@code WARN} que lo nombra, sin acusarlo, y la
 * vuelta sigue.
 *
 * <p>Lo que sigue siendo un fallo de transporte es un hecho <b>sin tipo</b>: eso no es una
 * capacidad que falte sino una respuesta que no tiene la forma de un hecho, y se arregla mirando el
 * despliegue.
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
            throw new CatastroNoContesta(
                    "`catastro` contesto "
                            + respuesta.statusCode()
                            + " al acusar. Los hechos SI estan aplicados aqui: se volveran a"
                            + " servir y se descartaran por deduplicacion");
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
