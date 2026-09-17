package kamayuk.rentas.nucleo.aplicacion;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;

/**
 * <b>FIXTURE DE PRUEBA</b>: un servidor HTTP diminuto, para medir el transporte de verdad (C-8).
 *
 * <h2>Por que un servidor y no un doble del puerto</h2>
 *
 * <p>Por lo mismo que {@code EmisorDeMentira}, del que este copia la forma: sustituir el cliente
 * HTTP por un doble deja sin medir justamente lo que se quiere medir — que el cliente compone la
 * ruta, lee el sobre, distingue un cuerpo que no es JSON de uno que si, y manda el acuse con la
 * forma que el otro lado espera. Un doble se los salta todos y deja verde un transporte que no
 * existe.
 *
 * <h2>Sobre {@code ServerSocket} y no sobre {@code com.sun.net.httpserver}</h2>
 *
 * <p>Checkstyle prohibe importar de {@code com.sun} —con razon: es API interna del JDK— y lo dijo
 * en la primera corrida completa. {@code EmisorDeMentira} ya habia resuelto esto antes con un
 * socket pelado, asi que aqui se hace igual en vez de abrir una excepcion a la regla.
 *
 * <h2>El cuerpo se lee en BYTES, y eso viene de un rojo (#212)</h2>
 *
 * <p>{@code Content-Length} cuenta <b>bytes</b>. Leyendo ese numero de <b>caracteres</b> con un
 * {@code Reader}, un cuerpo con {@code §} o {@code «»} —y el aviso al responsable de catastro acaba
 * en «(ADR-0026 §4).»— pide mas de los que van a llegar: el hilo se queda bloqueado en el {@code
 * read}, este servidor <b>no contesta nunca</b>, y el cliente muere a los diez segundos con {@code
 * HttpTimeoutException}. El cuerpo entraba igual en la lista de la prueba, pero solo despues de que
 * el cliente se rindiera y cerrara — o sea que la asercion media una carrera. El gemelo de esto en
 * {@code seguridad} fallaba una de cada dos pasadas.
 */
final class ServidorDeMentira implements AutoCloseable {

    /** Lo que se espera a que termine de llegar lo anunciado, antes de rendirse (#212). */
    private static final java.time.Duration ESPERA_DE_LECTURA = java.time.Duration.ofSeconds(2);

    private final ServerSocket puerta;
    private final Thread atencion;
    private final BiFunction<String, String, String> responder;

    private ServidorDeMentira(ServerSocket puerta, BiFunction<String, String, String> responder) {
        this.puerta = puerta;
        this.responder = responder;
        this.atencion = Thread.ofVirtual().name("servidor-de-mentira").unstarted(this::atender);
    }

    /**
     * Arranca uno en un puerto que elige el sistema.
     *
     * @param responder recibe la ruta pedida y el cuerpo, y devuelve el JSON de la respuesta
     */
    static ServidorDeMentira arrancar(BiFunction<String, String, String> responder)
            throws IOException {
        ServerSocket puerta = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
        ServidorDeMentira instancia = new ServidorDeMentira(puerta, responder);
        instancia.atencion.start();
        return instancia;
    }

    String raiz() {
        return "http://127.0.0.1:" + puerta.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        puerta.close();
    }

    private void atender() {
        while (!puerta.isClosed()) {
            try {
                Socket conexion = puerta.accept();
                Thread.ofVirtual().start(() -> contestar(conexion));
            } catch (IOException cerrada) {
                // La puerta cerrada es como termina esto: no es un fallo.
                return;
            }
        }
    }

    /**
     * Lee la peticion y contesta.
     *
     * <p>Se atrapa {@code RuntimeException} porque esto corre en un hilo suyo: una excepcion que
     * escapara aqui moriria en silencio y la prueba se quedaria esperando una respuesta que no
     * llega, que es la peor forma de fallar. Se contesta 500 y la asercion del cliente lo dira.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    private void contestar(Socket conexion) {
        try (Socket abierta = conexion;
                InputStream entrada = new BufferedInputStream(abierta.getInputStream());
                OutputStream salida = abierta.getOutputStream()) {
            // Que nunca se quede callado: si lo anunciado no termina de llegar, este servidor se
            // rinde antes de que el cliente agote SU plazo, y el rojo lo pone la prueba (#212).
            abierta.setSoTimeout((int) ESPERA_DE_LECTURA.toMillis());
            String peticion = leerLinea(entrada);
            if (peticion.isEmpty()) {
                return;
            }
            String ruta = peticion.split(" ")[1];
            int largo = 0;
            String linea = leerLinea(entrada);
            while (!linea.isEmpty()) {
                if (linea.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    largo = Integer.parseInt(linea.substring(linea.indexOf(':') + 1).strip());
                }
                linea = leerLinea(entrada);
            }
            String cuerpo = new String(entrada.readNBytes(largo), StandardCharsets.UTF_8);
            String respuesta;
            int estado = 200;
            try {
                respuesta = responder.apply(ruta, cuerpo);
            } catch (RuntimeException fallo) {
                estado = 500;
                respuesta = "{\"error\":\"" + fallo.getClass().getSimpleName() + "\"}";
            }
            byte[] bytes = respuesta.getBytes(StandardCharsets.UTF_8);
            salida.write(
                    ("HTTP/1.1 "
                                    + estado
                                    + " OK\r\nContent-Type: application/json\r\nContent-Length: "
                                    + bytes.length
                                    + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            salida.write(bytes);
            salida.flush();
        } catch (IOException seCorto) {
            // El cliente cerro antes de leer. No es un fallo de la prueba.
        }
    }

    /**
     * Una linea de la cabecera, leida octeto a octeto sobre el flujo de BYTES.
     *
     * <p>No vale envolverlo en un {@code Reader} ni para esto: el decodificador se llevaria por
     * delante los bytes del cuerpo que ya estan en el buffer.
     *
     * @return la linea sin su fin de linea; cadena vacia tanto en la linea en blanco como al final
     */
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
}
