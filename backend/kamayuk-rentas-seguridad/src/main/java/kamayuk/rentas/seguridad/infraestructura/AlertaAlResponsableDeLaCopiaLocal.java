package kamayuk.rentas.seguridad.infraestructura;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kamayuk.rentas.seguridad.dominio.AlertaDeEventosSinAplicar;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
import kamayuk.rentas.seguridad.dominio.EventoPospuesto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * El aviso de que la copia local de la autorizacion se quedo incompleta, ESCRITO SIEMPRE en el
 * registro con nivel ERROR y ADEMAS entregado por {@code POST} cuando el canal es una direccion
 * http(s) (ADR-0026 §4, ADR-0039 etapa 4).
 *
 * <p>El porque de esas dos mitades —y de que el canal no tenga que ser http— esta en {@link
 * ResponsableDelConsumidor}, con la medida que lo decidio.
 *
 * <p><b>Un canal que no contesta NO tumba la corrida</b>: el evento ya esta apartado y acusado, o
 * sea que la cola sigue. Lo que se hace es registrar el fallo de entrega, tambien con ERROR.
 */
public class AlertaAlResponsableDeLaCopiaLocal implements AlertaDeEventosSinAplicar {

    private static final Logger REGISTRO =
            LoggerFactory.getLogger(AlertaAlResponsableDeLaCopiaLocal.class);

    private static final Duration ESPERA = Duration.ofSeconds(10);

    private final HttpClient cliente;
    private final JsonMapper json;
    private final ResponsableDelConsumidor responsable;

    public AlertaAlResponsableDeLaCopiaLocal(
            JsonMapper json, ResponsableDelConsumidor responsable) {
        this.json = json;
        this.responsable = responsable;
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
                        + " evento(s) apartados en esta municipalidad. Mientras esten ahi, alguien"
                        + " tiene en `identidad` un permiso, una cuenta o una afiliacion que en"
                        + " `rentas` no rige, y ninguna cifra lo delata (ADR-0039 etapa 4,"
                        + " ADR-0026 §4).";
        avisar(new Aviso(responsable.nombre(), "APARTADO", motivo, apartados, texto));
    }

    @Override
    public void hayPospuestosQueNoAvanzan(
            List<EventoPospuesto> pospuestos, Instant ahora, Duration umbral) {
        StringBuilder lista = new StringBuilder();
        for (EventoPospuesto pospuesto : pospuestos) {
            lista.append("\n  - ")
                    .append(pospuesto.evento().tipoPublicado())
                    .append(" sujeto ")
                    .append(pospuesto.evento().sujetoId())
                    .append(", secuencia ")
                    .append(pospuesto.evento().secuencia())
                    .append(", esperando desde hace ")
                    .append(enMinutos(pospuesto.edad(ahora)))
                    .append(": ")
                    .append(pospuesto.motivo());
        }
        String texto =
                "LA COPIA LOCAL DE LA AUTORIZACION NO AVANZA: al terminar la corrida quedan "
                        + pospuestos.size()
                        + " evento(s) de `identidad` que llevan mas de "
                        + enMinutos(umbral)
                        + " sin poder aplicarse porque esta copia no conoce todavia aquello de lo"
                        + " que dependen. Un pospuesto no es un fallo mientras su dependencia este"
                        + " en camino; pasado ese tiempo ya no lo esta, y el buzon se los va a"
                        + " seguir sirviendo a esta copia en cada corrida sin que nada cambie"
                        + " (ADR-0039 etapa 4, ADR-0026 §4):"
                        + lista;
        avisar(new Aviso(responsable.nombre(), "POSPUESTO", "no avanza", pospuestos.size(), texto));
    }

    // ------------------------------------------------------------------

    /** Siempre al registro; y ademas al canal, si es de los que reciben. */
    private void avisar(Aviso aviso) {
        REGISTRO.error("{} Responsable: {}", aviso.texto(), responsable);
        if (responsable.seLeEntrega()) {
            entregar(aviso);
        }
    }

    private static String enMinutos(Duration duracion) {
        return duracion.toMinutes() + " minuto(s)";
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

    /** Lo que se manda al canal. */
    record Aviso(String responsable, String clase, String motivo, long cuantos, String texto) {}
}
