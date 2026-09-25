package kamayuk.rentas.plataforma;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entrega un aviso al canal de un {@link ResponsableDeOperacion} con un {@code POST}, cuando ese
 * canal es de los que reciben (ADR-0026 §4).
 *
 * <h2>Por que vive aqui (#377)</h2>
 *
 * <p>{@code AlertaAlCanalDelResponsable.entregar} ({@code nucleo}) y {@code
 * AlertaAlResponsableDeLaCopiaLocal.entregar} ({@code seguridad}) eran iguales byte a byte salvo el
 * {@code record} que serializaban, y cada cambio del aviso eran dos cambios — el de #70 se hizo en
 * una y no en la otra. Las dos alertas conservan su texto y su linea de ERROR, que son de cada
 * consumidor; lo que delegan aqui es solo la entrega.
 *
 * <h2>Un canal que no contesta NO tumba la vuelta</h2>
 *
 * <p>Y eso es una decision, no un descuido: lo que se avisa ya esta apartado y acusado, o sea que
 * la cola sigue corriendo. Dejar que un webhook caido lanzara desde aqui pararia el consumidor
 * entero por no poder avisar de <b>un</b> evento, que es cambiar un problema pequeño por uno
 * grande. Lo que se hace es registrar el fallo de entrega con nivel ERROR, para que se vea que hubo
 * un aviso que no llego.
 */
public final class CanalDeAvisos {

    private static final Logger REGISTRO = LoggerFactory.getLogger(CanalDeAvisos.class);

    private static final Duration ESPERA = Duration.ofSeconds(10);

    private final HttpClient cliente;
    private final JsonMapper json;
    private final ResponsableDeOperacion responsable;

    public CanalDeAvisos(JsonMapper json, ResponsableDeOperacion responsable) {
        this.json = json;
        this.responsable = responsable;
        this.cliente = HttpClient.newBuilder().connectTimeout(ESPERA).build();
    }

    /**
     * Entrega el aviso, serializado como JSON, si el canal es http(s); y si no se puede, lo dice.
     *
     * <p>Con un canal que no recibe —un correo— no hace nada: la constancia es la linea de ERROR
     * que la alerta escribe ANTES de llamar aqui, con el responsable y su canal dentro.
     *
     * <p>Se atrapa {@code RuntimeException} a proposito —y Checkstyle lo prohibe con razon casi
     * siempre—: lo que se atrapa aqui no es un defecto sino <b>un canal que no contesta</b>, y la
     * alternativa es que un webhook caido pare el consumidor entero. No se traga: se registra con
     * nivel ERROR, que es lo unico honesto que queda.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    public void entregar(Object aviso) {
        if (!responsable.seLeEntrega()) {
            return;
        }
        try {
            HttpRequest peticion =
                    HttpRequest.newBuilder(URI.create(responsable.canal()))
                            .timeout(ESPERA)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(aviso)))
                            .build();
            HttpResponse<String> respuesta =
                    cliente.send(peticion, HttpResponse.BodyHandlers.ofString());
            if (respuesta.statusCode() >= 300) {
                REGISTRO.error(
                        "El canal {} contesto {} al aviso: el responsable NO se ha enterado por"
                                + " ahi, y la unica constancia es la linea de arriba",
                        responsable.canal(),
                        respuesta.statusCode());
            }
        } catch (IOException | RuntimeException noSePudo) {
            REGISTRO.error(
                    "Y el aviso NO se pudo entregar en {}: {}. La unica constancia es la linea de"
                            + " arriba",
                    responsable.canal(),
                    noSePudo.toString());
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            REGISTRO.error("Se interrumpio al entregar el aviso en {}", responsable.canal());
        }
    }
}
