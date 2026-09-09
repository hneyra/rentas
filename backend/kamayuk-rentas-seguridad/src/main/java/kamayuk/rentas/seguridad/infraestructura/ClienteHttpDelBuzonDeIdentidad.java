package kamayuk.rentas.seguridad.infraestructura;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kamayuk.rentas.plataforma.CredencialDeServicio;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
import kamayuk.rentas.seguridad.dominio.FuenteDeEventosDeIdentidad;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El cliente HTTP del buzon de {@code identidad} (ADR-0039 etapa 4; {@code EventosController} de
 * {@code identidad}): {@code GET /eventos/pendientes?limite=} y {@code POST /eventos/acuses}.
 *
 * <h2>Con la misma credencial de servicio que el ingestor, y una segunda clave</h2>
 *
 * <p>El token lo pide {@code TokenDeServicioDeKeycloak} con {@code client_credentials}, igual que
 * el ingestor del padron hacia {@code catastro}: el mismo cliente confidencial —{@code
 * kamayuk-rentas-servicio-<ubigeo>}, uno por municipalidad (ADR-0028 §2)— y la clave que el
 * despliegue le da para ESTE destino ({@code KAMAYUK_IDENTIDAD_CREDENCIAL}). Por eso el proveedor
 * del token se mudo a {@code plataforma}: dos contextos lo consumen y Spring Modulith no expone los
 * sub-paquetes de {@code nucleo}. Lo que cambia entre los dos es el secreto, no el mecanismo.
 *
 * <h2>Todo fallo es transitorio, incluido el 401 y el 403</h2>
 *
 * <p>Lo que aqui no puede pasar es que un fallo de transporte se lleve un evento. Un 401 es una
 * clave que no vale o que no esta; un 403 es una cuenta de servicio que {@code identidad} conoce y
 * que no esta afiliada a su grupo «Consumidores del buzon» —nace sin miembros, a proposito, y
 * afiliarla es del despliegue—. Los dos se arreglan del lado del despliegue y cambian solos;
 * mientras tanto la corrida sale en rojo diciendo cual de los dos es, y la copia se queda como
 * estaba.
 *
 * <p>Lo unico que no es transitorio es un acuse que el emisor rechaza por su contenido (422): eso
 * es {@link FuenteDeEventosDeIdentidad.AcuseRechazado}, se registra y no se reintenta.
 */
public class ClienteHttpDelBuzonDeIdentidad implements FuenteDeEventosDeIdentidad {

    private static final Duration ESPERA_DE_CONEXION = Duration.ofSeconds(5);
    private static final Duration ESPERA_DE_LECTURA = Duration.ofSeconds(30);

    /** Las dos rutas del buzon, relativas a la raiz de la API de `identidad`. */
    static final String PENDIENTES = "/eventos/pendientes";

    static final String ACUSES = "/eventos/acuses";

    private final HttpClient cliente;
    private final JsonMapper json;
    private final String raiz;
    private final CredencialDeServicio credencial;

    /**
     * @param raiz la raiz de la API de `identidad`, con su prefijo: {@code
     *     http://…/identidad/api/v1}
     */
    public ClienteHttpDelBuzonDeIdentidad(
            JsonMapper json, String raiz, CredencialDeServicio credencial) {
        this.json = json;
        this.raiz = raiz.endsWith("/") ? raiz.substring(0, raiz.length() - 1) : raiz;
        this.credencial = credencial;
        this.cliente = HttpClient.newBuilder().connectTimeout(ESPERA_DE_CONEXION).build();
    }

    @Override
    public Lote pendientes(int limite) {
        HttpRequest.Builder peticion =
                HttpRequest.newBuilder(URI.create(raiz + PENDIENTES + "?limite=" + limite))
                        .timeout(ESPERA_DE_LECTURA)
                        .header("Accept", "application/json")
                        .GET();
        conCredencial(peticion);
        HttpResponse<String> respuesta = enviar(peticion, "leer el buzon de identidad");
        if (respuesta.statusCode() != 200) {
            throw noContesto(respuesta.statusCode(), "leer el buzon");
        }
        JsonNode cuerpo = arbol(respuesta.body(), "leer el buzon");
        List<EventoDeIdentidadRecibido> eventos = new ArrayList<>();
        for (JsonNode evento : cuerpo.path("eventos")) {
            eventos.add(leer(evento));
        }
        return new Lote(List.copyOf(eventos), cuerpo.path("quedan").asLong(0));
    }

    @Override
    public Acuse acusar(List<UUID> eventoIds) {
        if (eventoIds.isEmpty()) {
            return new Acuse(0, 0, 0);
        }
        List<String> ids = new ArrayList<>();
        for (UUID id : eventoIds) {
            ids.add(id.toString());
        }
        String cuerpo = escribir(new PeticionDeAcuse(List.copyOf(ids)));
        HttpRequest.Builder peticion =
                HttpRequest.newBuilder(URI.create(raiz + ACUSES))
                        .timeout(ESPERA_DE_LECTURA)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(cuerpo));
        conCredencial(peticion);
        HttpResponse<String> respuesta = enviar(peticion, "acusar los eventos aplicados");
        int estado = respuesta.statusCode();
        if (estado == 200) {
            JsonNode acuse = arbol(respuesta.body(), "acusar");
            return new Acuse(
                    acuse.path("recibidos").asInt(0),
                    acuse.path("escritos").asInt(0),
                    acuse.path("quedan").asLong(0));
        }
        if (esDeNegocio(estado)) {
            // Lo escribio `identidad` para decir que rechazo; se guarda entero porque es lo unico
            // que dice CUAL de los identificadores no le cuadro. Es un `problem+json`, no un
            // eco de lo que se le mando.
            throw new AcuseRechazado(estado, respuesta.body());
        }
        throw noContesto(
                estado,
                "acusar. Los eventos SI estan resueltos aqui: se volveran a servir y se"
                        + " descartaran por deduplicacion");
    }

    // ------------------------------------------------------------------

    /** Un 4xx que no es de credencial: el emisor entendio la peticion y la rechazo. */
    private static boolean esDeNegocio(int estado) {
        return estado >= 400 && estado < 500 && estado != 401 && estado != 403;
    }

    private static IdentidadNoContesta noContesto(int estado, String que) {
        return switch (estado) {
            case 401 ->
                    new IdentidadNoContesta(
                            "`identidad` contesto 401 al "
                                    + que
                                    + ": la credencial de servicio no vale o no se manda"
                                    + " (kamayuk.identidad.cliente, kamayuk.identidad.credencial)."
                                    + " Se arregla en el despliegue, no en el evento");
            case 403 ->
                    new IdentidadNoContesta(
                            "`identidad` contesto 403 al "
                                    + que
                                    + ": la cuenta de servicio de `rentas` existe y no tiene el"
                                    + " acceso «eventos» — falta afiliarla al grupo «Consumidores"
                                    + " del buzon» de su municipalidad en `identidad`. Se arregla"
                                    + " en el despliegue, no en el evento");
            default -> new IdentidadNoContesta("`identidad` contesto " + estado + " al " + que);
        };
    }

    private EventoDeIdentidadRecibido leer(JsonNode evento) {
        try {
            return new EventoDeIdentidadRecibido(
                    UUID.fromString(evento.path("eventoId").asString()),
                    evento.path("secuencia").asLong(),
                    evento.path("tipo").asString(""),
                    evento.path("sujetoId").asLong(),
                    evento.path("cuerpo").asString(""),
                    evento.path("huella").asString(""),
                    Instant.parse(evento.path("creadoEn").asString()));
        } catch (IllegalArgumentException | DateTimeParseException malFormado) {
            throw new IdentidadNoContesta(
                    "El buzon de `identidad` contesto algo que no tiene la forma de un evento: "
                            + malFormado.getMessage(),
                    malFormado);
        }
    }

    private JsonNode arbol(String cuerpo, String que) {
        try {
            return json.readTree(cuerpo);
        } catch (JacksonException ilegible) {
            throw new IdentidadNoContesta(
                    "`identidad` contesto algo que no es JSON al " + que, ilegible);
        }
    }

    /**
     * Pone la cabecera si la hay. Se pide en cada peticion y no al construir: un token caduca.
     *
     * <p>Un fallo al pedir el token llega como {@link CredencialDeServicio.NoSePudoObtener}, que es
     * de quien llama y no de a quien se llama; aqui se traduce a la excepcion de este puerto con el
     * mismo significado: transitorio, se arregla en el despliegue.
     */
    private void conCredencial(HttpRequest.Builder peticion) {
        String cabecera;
        try {
            cabecera = credencial.cabecera();
        } catch (CredencialDeServicio.NoSePudoObtener sinToken) {
            throw new IdentidadNoContesta(
                    "No se pudo pedir el token de servicio para leer el buzon de `identidad`: "
                            + sinToken.getMessage(),
                    sinToken);
        }
        if (!cabecera.isBlank()) {
            peticion.header("Authorization", cabecera);
        }
    }

    private HttpResponse<String> enviar(HttpRequest.Builder peticion, String que) {
        try {
            return cliente.send(peticion.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException noContesta) {
            throw new IdentidadNoContesta("No se pudo " + que, noContesta);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new IdentidadNoContesta("Se interrumpio al " + que, interrumpido);
        }
    }

    private String escribir(Object cuerpo) {
        try {
            return json.writeValueAsString(cuerpo);
        } catch (JacksonException noSePuede) {
            throw new IllegalStateException("No se pudo componer el acuse", noSePuede);
        }
    }

    /** Lo que se manda al acusar: la forma de {@code EventosController.PeticionDeAcuse}. */
    private record PeticionDeAcuse(List<String> eventos) {}
}
