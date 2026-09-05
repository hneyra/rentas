package kamayuk.rentas.rentas.infraestructura.ingestor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import kamayuk.rentas.rentas.aplicacion.AlertaDeHechosSinAplicar;
import kamayuk.rentas.rentas.dominio.proyeccion.HechoRecibido;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * La alerta, ENTREGADA al canal del responsable y ademas escrita con nivel ERROR (ADR-0026 §4).
 *
 * <h2>Las dos cosas, y las dos hacen falta</h2>
 *
 * <ul>
 *   <li><b>Se entrega</b> con un {@code POST} al canal configurado. Es lo que hace que «avisa a una
 *       persona con nombre» se pueda comprobar ejecutandolo, que es exactamente lo que P5D dejo sin
 *       poder comprobar: su alerta «escribe en el registro, no manda un correo… esta construido y
 *       no esta medido».
 *   <li><b>Y se registra</b> con nivel ERROR, con el responsable y su canal dentro. No es
 *       redundante: si el canal esta caido, la unica constancia de que hubo un aviso es esa linea —
 *       y la observabilidad del proyecto (INF-11) alerta sobre ERROR con receptor ya comprobado.
 * </ul>
 *
 * <h2>Un canal que no contesta NO tumba la vuelta</h2>
 *
 * <p>Y eso es una decision, no un descuido: el hecho ya esta apartado y acusado, o sea que la cola
 * sigue corriendo. Dejar que un webhook caido lanzara desde aqui pararia la ingestion entera por no
 * poder avisar de <b>un</b> hecho, que es cambiar un problema pequeño por uno grande. Lo que se
 * hace es registrar el fallo de entrega, tambien con nivel ERROR, para que se vea que hubo un aviso
 * que no llego.
 */
public class AlertaAlCanalDelResponsable implements AlertaDeHechosSinAplicar {

    private static final Logger REGISTRO =
            LoggerFactory.getLogger(AlertaAlCanalDelResponsable.class);

    private static final Duration ESPERA = Duration.ofSeconds(10);

    private final HttpClient cliente;
    private final JsonMapper json;
    private final ResponsableDeLaProyeccion responsable;

    public AlertaAlCanalDelResponsable(JsonMapper json, ResponsableDeLaProyeccion responsable) {
        this.json = json;
        this.responsable = responsable;
        this.cliente = HttpClient.newBuilder().connectTimeout(ESPERA).build();
    }

    @Override
    public void hayUnHechoSinAplicar(HechoRecibido hecho, String motivo, long muertosSinExplicar) {
        String texto =
                "LA PROYECCION DEL PADRON ESTA INCOMPLETA: el hecho "
                        + hecho.eventoId()
                        + " ("
                        + hecho.tipo()
                        + ", predio "
                        + hecho.predioId()
                        + ", ejercicio "
                        + hecho.ejercicio()
                        + ", secuencia "
                        + hecho.secuencia()
                        + ") no se pudo aplicar y se aparto. Motivo: "
                        + motivo
                        + ". Hay "
                        + muertosSinExplicar
                        + " hecho(s) apartados sin explicar. Mientras esten ahi, `rentas` dice del"
                        + " padron algo que `catastro` ya no dice, y ninguna cifra lo delata"
                        + " (ADR-0026 §4).";
        REGISTRO.error("{} Responsable: {}", texto, responsable);
        entregar(
                new Aviso(
                        responsable.nombre(),
                        hecho.eventoId().toString(),
                        motivo,
                        muertosSinExplicar,
                        texto));
    }

    /**
     * Entrega el aviso, y si no se puede lo dice.
     *
     * <p>Se atrapa {@code RuntimeException} a proposito —y Checkstyle lo prohibe con razon casi
     * siempre—: lo que se atrapa aqui no es un defecto sino <b>un canal que no contesta</b>, y la
     * alternativa es que un webhook caido pare la ingestion entera por no poder avisar de un solo
     * hecho. No se traga: se registra con nivel ERROR, que es lo unico honesto que queda.
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
    record Aviso(
            String responsable,
            String eventoId,
            String motivo,
            long muertosSinExplicar,
            String texto) {}
}
