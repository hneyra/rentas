package kamayuk.rentas.seguridad.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

        assertThat(canal.esperaElAviso())
                .contains("LA COPIA LOCAL DE LA AUTORIZACION ESTA INCOMPLETA")
                .contains("un cuerpo ilegible")
                .contains("Guardia de plataforma")
                .as(
                        "y llega ENTERO: la cola del texto, con su caracter no ASCII y la llave que"
                                + " cierra el JSON [si el canal contara los caracteres del `Content-Length`"
                                + " en vez de sus bytes, aqui llegaria un cuerpo cortado]")
                .contains("ADR-0026 §4).")
                .endsWith("}");
        assertThat(canal.recibidos()).as("y no llego un segundo aviso").isEmpty();
    }

    @Test
    @DisplayName("y el aviso de los pospuestos va por el mismo canal, con su lista")
    void elAvisoDeLosPospuestosVaPorElMismoCanal() {
        alertaCon(canal.raiz())
                .hayPospuestosQueNoAvanzan(
                        List.of(new EventoPospuesto(evento(), "no conoce el grupo «Caja»")),
                        AHORA,
                        Duration.ofMinutes(15));

        assertThat(canal.esperaElAviso())
                .contains("LA COPIA LOCAL DE LA AUTORIZACION NO AVANZA")
                .contains("MIEMBRO_AFILIADO")
                .contains("no conoce el grupo «Caja»")
                .contains("15 minuto(s)")
                .endsWith("}");
        assertThat(canal.recibidos()).as("y no llego un segundo aviso").isEmpty();
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

    /**
     * Un canal que acepta cualquier POST y guarda el cuerpo, como el webhook del responsable.
     *
     * <p><b>Dos cosas de aqui vienen de un rojo intermitente (#212), y ninguna es decorativa.</b>
     *
     * <p>La primera: el cuerpo se lee en <b>BYTES</b> y se decodifica al final. {@code
     * Content-Length} cuenta bytes, y el texto del aviso lleva {@code §} y {@code «»}; leyendo ese
     * numero de CARACTERES con un {@code Reader} se pedian mas de los que iban a llegar —medido, 44
     * bytes contra 41 caracteres—, el hilo se quedaba bloqueado en el {@code read}, el canal <b>no
     * contestaba nunca</b> y el cliente moria con {@code HttpTimeoutException} a los diez segundos.
     * El cuerpo acababa en la lista igualmente, pero cortado y solo porque el cliente se rendia y
     * cerraba: o sea que la prueba daba verde sin que el canal hubiera contestado.
     *
     * <p>La segunda: {@link #esperaElAviso()}. La entrega la hace <b>otro hilo</b>, y afirmar sobre
     * la lista en cuanto vuelve la llamada es afirmar sobre una carrera — con el {@code read}
     * bloqueado, la ventana era de diez segundos y el rojo salia una de cada dos pasadas, {@code
     * Expected size: 1 but was: 0 in: []}, sin decir de que. Un {@code sleep} cambiaria la
     * probabilidad; esto cambia la condicion.
     */
    private static final class CanalDeMentira implements AutoCloseable {

        /** Lo que se espera a la entrega antes de decir que NO llego. */
        private static final Duration PLAZO = Duration.ofSeconds(5);

        /**
         * Lo que el canal espera a que termine de llegar lo que le anunciaron.
         *
         * <p>Mas corto que {@link #PLAZO}, y MUCHO mas corto que los diez segundos que espera el
         * cliente: un canal que se queda callado es peor que uno que falla, porque entonces el rojo
         * lo pone el plazo del emisor y la prueba se cuelga diez segundos de un reloj ajeno.
         * Rindiendose antes, en la cola no entra nada y el rojo lo pone {@link #esperaElAviso()},
         * que sabe decir de que va (#212).
         */
        private static final Duration LECTURA = Duration.ofSeconds(2);

        private final ServerSocket socket;
        private final Thread hilo;
        private final BlockingQueue<String> cuerpos = new LinkedBlockingQueue<>();

        private CanalDeMentira(ServerSocket socket) {
            this.socket = socket;
            this.hilo =
                    new Thread(
                            () -> {
                                while (!socket.isClosed()) {
                                    try (Socket cliente = socket.accept()) {
                                        cliente.setSoTimeout((int) LECTURA.toMillis());
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
            // El backlog explicito es el que `ServerSocket` pone de todas formas cuando se le da
            // cero o menos; escrito a mano para que no vuelva a parecer la causa de nada (#212).
            ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            CanalDeMentira canal = new CanalDeMentira(socket);
            canal.hilo.start();
            return canal;
        }

        String raiz() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/avisos";
        }

        /**
         * El aviso, esperando a que llegue; y si no llega, un rojo que dice eso y no un {@code []}.
         *
         * @return el cuerpo entregado, sacado de la cola
         */
        String esperaElAviso() {
            try {
                String cuerpo = cuerpos.poll(PLAZO.toMillis(), TimeUnit.MILLISECONDS);
                if (cuerpo == null) {
                    throw new AssertionError(
                            "EL AVISO NO LLEGO al canal "
                                    + raiz()
                                    + " en "
                                    + PLAZO.toSeconds()
                                    + " s. El canal esta levantado y escuchando, asi que o no se"
                                    + " hizo el POST, o se hizo a otra direccion, o el canal no"
                                    + " pudo leer el cuerpo que se le mando (ADR-0039 etapa 4).");
                }
                return cuerpo;
            } catch (InterruptedException interrumpido) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Se interrumpio esperando el aviso", interrumpido);
            }
        }

        /** Lo que hay en la cola AHORA MISMO, sin esperar: sirve para afirmar que no llego nada. */
        List<String> recibidos() {
            return List.copyOf(cuerpos);
        }

        /**
         * El cuerpo del POST, contando BYTES y no caracteres.
         *
         * <p>La cabecera es ASCII y se lee linea a linea sobre el flujo de bytes: envolverla en un
         * {@code Reader} no vale, porque el decodificador se lleva por delante los bytes del cuerpo
         * que ya estan en el buffer.
         */
        private static String leerCuerpo(Socket cliente) throws IOException {
            InputStream entrada = new BufferedInputStream(cliente.getInputStream());
            leerLinea(entrada);
            int longitud = 0;
            String linea = leerLinea(entrada);
            while (!linea.isEmpty()) {
                if (linea.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    longitud = Integer.parseInt(linea.substring(linea.indexOf(':') + 1).trim());
                }
                linea = leerLinea(entrada);
            }
            return new String(entrada.readNBytes(longitud), StandardCharsets.UTF_8);
        }

        /** Una linea de la cabecera. Cadena vacia tanto en la linea en blanco como al final. */
        private static String leerLinea(InputStream entrada) throws IOException {
            StringBuilder linea = new StringBuilder();
            int octeto = entrada.read();
            while (octeto >= 0 && octeto != '\n') {
                if (octeto != '\r') {
                    linea.append((char) octeto);
                }
                octeto = entrada.read();
            }
            return linea.toString();
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
