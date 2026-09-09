package kamayuk.rentas.seguridad.infraestructura;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import kamayuk.rentas.seguridad.dominio.AlertaDeEventosSinAplicar;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * El aviso de un evento de la autorizacion que no se pudo aplicar, ENTREGADO al canal del
 * responsable y ademas escrito con nivel ERROR (ADR-0026 §4). Es la forma de {@code
 * AlertaAlCanalDelResponsable}, la del ingestor del padron, con otro sujeto: no un predio del que
 * `rentas` dice algo que `catastro` ya no dice, sino un permiso, una cuenta o una afiliacion que en
 * `identidad` rige y aqui no.
 *
 * <p>Son las mismas dos variables que el ingestor del padron —el responsable y su canal, {@code
 * KAMAYUK_IDENTIDAD_RESPONSABLE} y {@code KAMAYUK_IDENTIDAD_CANAL}, que el descriptor rellena con
 * {@code operacion.responsable} y {@code operacion.canal}— y a proposito: la municipalidad tiene
 * UNA persona que responde por lo que este despliegue no consigue hacer solo. El canal tiene que
 * ser una direccion http(s), por lo mismo que en el ingestor: si es texto libre, lo unico que se
 * puede comprobar de la alerta es que la linea exista.
 *
 * <p><b>Un canal que no contesta NO tumba la vuelta</b>: el evento ya esta apartado y acusado, o
 * sea que la cola sigue. Lo que se hace es registrar el fallo de entrega, tambien con ERROR.
 */
public class AlertaAlResponsableDeLaCopiaLocal implements AlertaDeEventosSinAplicar {

    private static final Logger REGISTRO =
            LoggerFactory.getLogger(AlertaAlResponsableDeLaCopiaLocal.class);

    private static final Duration ESPERA = Duration.ofSeconds(10);

    private final HttpClient cliente;
    private final JsonMapper json;
    private final String responsable;
    private final String canal;

    public AlertaAlResponsableDeLaCopiaLocal(JsonMapper json, String responsable, String canal) {
        this.json = json;
        this.responsable = responsable.strip();
        this.canal = canal.strip();
        if (this.responsable.isEmpty() || this.canal.isEmpty()) {
            throw new IllegalStateException(
                    "El consumidor de identidad necesita a quien avisar: KAMAYUK_IDENTIDAD_RESPONSABLE"
                            + " y KAMAYUK_IDENTIDAD_CANAL (ADR-0026 §4). Sin ellos un permiso que"
                            + " no llega se apartaria en silencio, y mientras este apartado alguien"
                            + " puede en `identidad` lo que aqui no puede");
        }
        if (!this.canal.startsWith("http://") && !this.canal.startsWith("https://")) {
            throw new IllegalStateException(
                    "kamayuk.identidad.canal tiene que ser una direccion http(s) a la que se pueda"
                            + " entregar el aviso, y llego «"
                            + this.canal
                            + "». Con texto libre lo unico comprobable seria que la linea exista");
        }
        this.cliente = HttpClient.newBuilder().connectTimeout(ESPERA).build();
    }

    @Override
    public void hayUnEventoSinAplicar(
            EventoDeIdentidadRecibido evento, String motivo, long apartados) {
        String texto =
                "LA COPIA LOCAL DE LA AUTORIZACION ESTA INCOMPLETA: el evento "
                        + evento.eventoId()
                        + " ("
                        + evento.tipoPublicado()
                        + ", sujeto "
                        + evento.sujetoId()
                        + ", secuencia "
                        + evento.secuencia()
                        + ") de `identidad` no se pudo aplicar y se aparto. Motivo: "
                        + motivo
                        + ". Hay "
                        + apartados
                        + " evento(s) apartados en esta municipalidad. Mientras esten ahi,"
                        + " alguien tiene en `identidad` un permiso, una cuenta o una afiliacion"
                        + " que en `rentas` no rige, y ninguna cifra lo delata (ADR-0039 etapa 4,"
                        + " ADR-0026 §4).";
        REGISTRO.error("{} Responsable: {} <{}>", texto, responsable, canal);
        entregar(new Aviso(responsable, evento.eventoId().toString(), motivo, apartados, texto));
    }

    /**
     * Entrega el aviso, y si no se puede lo dice.
     *
     * <p>Se atrapa {@code RuntimeException} a proposito: lo que se atrapa no es un defecto sino un
     * canal que no contesta, y la alternativa es que un webhook caido pare el consumidor entero por
     * no poder avisar de un solo evento. No se traga: se registra con ERROR.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private void entregar(Aviso aviso) {
        try {
            HttpRequest peticion =
                    HttpRequest.newBuilder(URI.create(canal))
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
                        canal,
                        respuesta.statusCode());
            }
        } catch (IOException | RuntimeException noSePudo) {
            REGISTRO.error(
                    "Y el aviso NO se pudo entregar en {}: {}. La unica constancia es la linea de"
                            + " arriba",
                    canal,
                    noSePudo.toString());
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            REGISTRO.error("Se interrumpio al entregar el aviso en {}", canal);
        }
    }

    /** Lo que se manda al canal. */
    record Aviso(
            String responsable, String eventoId, String motivo, long apartados, String texto) {}
}
