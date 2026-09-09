package kamayuk.rentas.nucleo.infraestructura.ingestor;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import kamayuk.rentas.nucleo.dominio.proyeccion.FuenteDeHechosDeCatastro.CatastroNoContesta;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El token con el que el ingestor le pide el buzon a {@code catastro} (#21 AC-2, ADR-0028 §2).
 *
 * <h2>Por que `client_credentials` y no el token de nadie</h2>
 *
 * <p>El ingestor lo despierta un {@code CronJob}, no una peticion: no hay ningun {@code
 * Authorization} del que tirar. Lo que hay es un cliente confidencial suyo —{@code
 * kamayuk-rentas-servicio-<ubigeo>}, uno por municipalidad— cuya cuenta de servicio lleva el
 * atributo {@code municipalidad_id}, y de ahi sale el claim que acota la corrida.
 *
 * <p><b>Uno por municipalidad y no uno por sistema</b>, y eso lo decide ADR-0028 §2: «no hay un
 * proceso con permiso sobre todas».
 *
 * <h2>Se guarda, y se renueva antes de que caduque</h2>
 *
 * <p>Una vuelta del ingestor son varias llamadas —traer y acusar, pagina a pagina—, asi que un
 * token por llamada multiplicaria los viajes al emisor. Se conserva hasta {@link #MARGEN} antes de
 * su vencimiento; el margen existe porque entre pedirlo y usarlo pasa tiempo, y un token que caduca
 * en vuelo se ve exactamente igual que una credencial mala: un 401.
 *
 * <h2>Lo que hace cuando no puede</h2>
 *
 * <p>Lanza {@link CatastroNoContesta}, como todo lo de este camino: no poder pedir el token —el
 * emisor caido, la clave equivocada, el cliente sin crear— se arregla mirando el despliegue. Lo que
 * <b>no</b> puede pasar es que un fallo de transporte mate un hecho.
 */
public class TokenDeServicioDeKeycloak implements CredencialDeServicio {

    /**
     * Cuanto antes de su vencimiento se pide otro.
     *
     * <p>Treinta segundos: mas que cualquier viaje de red razonable y mucho menos que la vigencia
     * habitual de un token de servicio, que Keycloak da en minutos.
     */
    private static final Duration MARGEN = Duration.ofSeconds(30);

    private static final Duration ESPERA_DE_CONEXION = Duration.ofSeconds(5);
    private static final Duration ESPERA_DE_LECTURA = Duration.ofSeconds(10);

    private final HttpClient cliente;
    private final JsonMapper json;
    private final Clock reloj;
    private final String punto;
    private final String clienteDeServicio;
    private final String clave;

    private volatile String cabecera = "";
    private volatile Instant vence = Instant.EPOCH;

    public TokenDeServicioDeKeycloak(
            JsonMapper json, Clock reloj, String punto, String clienteDeServicio, String clave) {
        this.json = json;
        this.reloj = reloj;
        this.punto = punto;
        this.clienteDeServicio = clienteDeServicio;
        this.clave = clave;
        this.cliente = HttpClient.newBuilder().connectTimeout(ESPERA_DE_CONEXION).build();
    }

    /** Si este despliegue tiene identidad de servicio configurada. */
    public boolean configurada() {
        return !punto.isBlank() && !clienteDeServicio.isBlank() && !clave.isBlank();
    }

    @Override
    public String cabecera() {
        // Sin configurar, cadena vacia y no una excepcion: el cliente HTTP ya sabe decir «esta
        // caja no manda ninguna», y con el nombre de la propiedad que falta. Lanzar aqui
        // cambiaria ese diagnostico por uno peor y en otro sitio.
        if (!configurada()) {
            return "";
        }
        Instant ahora = reloj.instant();
        String guardado = cabecera;
        if (!guardado.isEmpty() && ahora.isBefore(vence)) {
            return guardado;
        }
        synchronized (this) {
            // Otro hilo pudo renovarlo mientras se esperaba. Se vuelve a mirar, o dos pagos
            // simultaneos pedirian dos tokens.
            if (!cabecera.isEmpty() && ahora.isBefore(vence)) {
                return cabecera;
            }
            JsonNode respuesta = pedir();
            String token = respuesta.path("access_token").asString("");
            if (token.isBlank()) {
                throw new CatastroNoContesta(
                        "El emisor contesto 200 al pedir el token de «"
                                + clienteDeServicio
                                + "» y su respuesta no trae `access_token`. Es un emisor que no"
                                + " esta emitiendo, no un buzon que falle");
            }
            long vigencia = respuesta.path("expires_in").asLong(0L);
            cabecera = "Bearer " + token;
            // Sin `expires_in` no se guarda: pedirlo cada vez cuesta un viaje, y suponer una
            // vigencia que el emisor no dijo cuesta un 401 a mitad de una tanda de pagos.
            vence = vigencia <= 0 ? Instant.EPOCH : ahora.plusSeconds(vigencia).minus(MARGEN);
            return cabecera;
        }
    }

    private JsonNode pedir() {
        String cuerpo =
                "grant_type=client_credentials&client_id="
                        + URLEncoder.encode(clienteDeServicio, StandardCharsets.UTF_8)
                        + "&client_secret="
                        + URLEncoder.encode(clave, StandardCharsets.UTF_8);
        HttpRequest peticion =
                HttpRequest.newBuilder(URI.create(punto))
                        .timeout(ESPERA_DE_LECTURA)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                        .build();
        HttpResponse<String> respuesta;
        try {
            respuesta = cliente.send(peticion, HttpResponse.BodyHandlers.ofString());
        } catch (IOException noContesta) {
            throw new CatastroNoContesta(
                    "No se pudo pedir el token de servicio a «" + punto + "»", noContesta);
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new CatastroNoContesta(
                    "Se interrumpio al pedir el token de servicio", interrumpido);
        }
        if (respuesta.statusCode() != 200) {
            // El cuerpo NO se copia al mensaje: lo escribe el emisor y en un error de cliente
            // trae `error_description`, que puede repetir lo que se le mando — incluida la clave.
            throw new CatastroNoContesta(
                    "El emisor contesto "
                            + respuesta.statusCode()
                            + " al pedir el token de «"
                            + clienteDeServicio
                            + "». Ese cliente tiene que existir en el realm con esa clave"
                            + " (`reconciliar-identidades.sh servicios`, #21). No es `catastro`:"
                            + " es quien llama, y se arregla en el despliegue");
        }
        try {
            return json.readTree(respuesta.body());
        } catch (JacksonException ilegible) {
            throw new CatastroNoContesta(
                    "El emisor contesto algo que no es JSON al pedir el token", ilegible);
        }
    }
}
