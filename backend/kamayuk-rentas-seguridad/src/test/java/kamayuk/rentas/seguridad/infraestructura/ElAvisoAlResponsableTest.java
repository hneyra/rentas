package kamayuk.rentas.seguridad.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
import kamayuk.rentas.seguridad.dominio.EventoPospuesto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A quien se avisa y por donde (ADR-0026 §4, ADR-0039 etapa 4).
 *
 * <p>Lo que esta clase existe para fijar es la doctrina que la etapa 4 pago al levantarse de
 * verdad: <b>el canal NO tiene que ser una direccion http(s)</b>. Exigirlo tumbaba el contexto de
 * Spring con el canal que el {@code .env.ejemplo} y los dos stacks declaran —un correo—, y como la
 * implantacion termina con una pasada del consumidor, el {@code Job} de implantacion de `rentas` no
 * arrancaba en ningun ambiente.
 */
@DisplayName("Etapa 4 — el aviso al responsable de la copia local")
class ElAvisoAlResponsableTest {

    /** El valor literal de `KAMAYUK_CANAL_DE_OPERACION` en el `.env.ejemplo` de la plataforma. */
    private static final String CANAL_DEL_AMBIENTE = "operaciones@example.pe";

    private static final Instant AHORA = Instant.parse("2026-09-09T15:00:00Z");

    private CanalDeMentira canal;

    @BeforeEach
    void levantar() throws IOException {
        canal = CanalDeMentira.arranca();
    }

    @AfterEach
    void apagar() throws IOException {
        canal.close();
    }

    @Test
    @DisplayName("un CORREO es un canal valido: es el que declaran el .env.ejemplo y los stacks")
    void unCorreoEsUnCanalValido() {
        ResponsableDelConsumidor responsable =
                new ResponsableDelConsumidor("Guardia de plataforma", CANAL_DEL_AMBIENTE);

        assertThat(responsable.canal()).isEqualTo(CANAL_DEL_AMBIENTE);
        assertThat(responsable.seLeEntrega())
                .as("no se le puede ENTREGAR el aviso; se le nombra en la linea de ERROR")
                .isFalse();
    }

    @Test
    @DisplayName("y con el, avisar NO revienta ni intenta hablar con nadie")
    void conUnCorreoElAvisoNoRevienta() {
        AlertaAlResponsableDeLaCopiaLocal alerta = alertaCon(CANAL_DEL_AMBIENTE);

        assertThatCode(() -> alerta.hayUnEventoSinAplicar(evento(), "un cuerpo ilegible", 1))
                .as(
                        "[con la exigencia de http(s) esto no llegaba ni a construirse: el contexto"
                                + " de Spring se caia al arrancar con «kamayuk.identidad.canal tiene"
                                + " que ser una direccion http(s) … y llego"
                                + " «operaciones@example.pe»», y la implantacion —que TERMINA con una"
                                + " pasada del consumidor— no arrancaba en ningun ambiente (C-7)]")
                .doesNotThrowAnyException();
        assertThat(canal.recibidos()).isEmpty();
    }

    @Test
    @DisplayName("un canal http(s) ADEMAS recibe el aviso, con el texto entero dentro")
    void unCanalHttpRecibeElAviso() {
        alertaCon(canal.raiz()).hayUnEventoSinAplicar(evento(), "un cuerpo ilegible", 2);

        assertThat(canal.recibidos()).hasSize(1);
        assertThat(canal.recibidos().getFirst())
                .contains("LA COPIA LOCAL DE LA AUTORIZACION ESTA INCOMPLETA")
                .contains("un cuerpo ilegible")
                .contains("Guardia de plataforma");
    }

    @Test
    @DisplayName("y el aviso de los pospuestos va por el mismo canal, con su lista")
    void elAvisoDeLosPospuestosVaPorElMismoCanal() {
        alertaCon(canal.raiz())
                .hayPospuestosQueNoAvanzan(
                        List.of(new EventoPospuesto(evento(), "no conoce el grupo «Caja»")),
                        AHORA,
                        Duration.ofMinutes(15));

        assertThat(canal.recibidos()).hasSize(1);
        assertThat(canal.recibidos().getFirst())
                .contains("LA COPIA LOCAL DE LA AUTORIZACION NO AVANZA")
                .contains("MIEMBRO_AFILIADO")
                .contains("no conoce el grupo")
                .contains("15 minuto(s)");
    }

    @Test
    @DisplayName("sin nombre o sin canal el consumidor no arranca, y el mensaje dice las dos")
    void sinResponsableNoArranca() {
        assertThatThrownBy(() -> new ResponsableDelConsumidor("", CANAL_DEL_AMBIENTE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kamayuk.identidad.responsable")
                .hasMessageContaining("kamayuk.identidad.canal")
                .hasMessageContaining("A UNA PERSONA CON NOMBRE");
        assertThatThrownBy(() -> new ResponsableDelConsumidor("Guardia de plataforma", "  "))
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------

    private static AlertaAlResponsableDeLaCopiaLocal alertaCon(String canal) {
        return new AlertaAlResponsableDeLaCopiaLocal(
                JsonMapper.builder().build(),
                new ResponsableDelConsumidor("Guardia de plataforma", canal));
    }

    private static EventoDeIdentidadRecibido evento() {
        return new EventoDeIdentidadRecibido(
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                7,
                "MIEMBRO_AFILIADO",
                3,
                "{}",
                "c".repeat(64),
                AHORA.minus(Duration.ofMinutes(20)));
    }

    /** Un canal que acepta cualquier POST y guarda el cuerpo, como el webhook del responsable. */
    private static final class CanalDeMentira implements AutoCloseable {

        private final ServerSocket socket;
        private final Thread hilo;
        private final List<String> cuerpos = Collections.synchronizedList(new ArrayList<>());

        private CanalDeMentira(ServerSocket socket) {
            this.socket = socket;
            this.hilo =
                    new Thread(
                            () -> {
                                while (!socket.isClosed()) {
                                    try (Socket cliente = socket.accept()) {
                                        cuerpos.add(leerCuerpo(cliente));
                                        responder(cliente);
                                    } catch (IOException cerrado) {
                                        return;
                                    }
                                }
                            },
                            "canal-del-responsable-de-mentira");
            this.hilo.setDaemon(true);
        }

        static CanalDeMentira arranca() throws IOException {
            ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
            CanalDeMentira canal = new CanalDeMentira(socket);
            canal.hilo.start();
            return canal;
        }

        String raiz() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/avisos";
        }

        List<String> recibidos() {
            return List.copyOf(cuerpos);
        }

        private static String leerCuerpo(Socket cliente) throws IOException {
            BufferedReader lector =
                    new BufferedReader(
                            new InputStreamReader(
                                    cliente.getInputStream(), StandardCharsets.UTF_8));
            lector.readLine();
            int longitud = 0;
            String linea = lector.readLine();
            while (linea != null && !linea.isEmpty()) {
                if (linea.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    longitud = Integer.parseInt(linea.substring(linea.indexOf(':') + 1).trim());
                }
                linea = lector.readLine();
            }
            char[] datos = new char[longitud];
            int leidos = 0;
            while (leidos < longitud) {
                int n = lector.read(datos, leidos, longitud - leidos);
                if (n < 0) {
                    break;
                }
                leidos += n;
            }
            return new String(datos, 0, Math.max(leidos, 0));
        }

        private static void responder(Socket cliente) throws IOException {
            OutputStream salida = cliente.getOutputStream();
            salida.write(
                    "HTTP/1.1 204 \r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            salida.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
