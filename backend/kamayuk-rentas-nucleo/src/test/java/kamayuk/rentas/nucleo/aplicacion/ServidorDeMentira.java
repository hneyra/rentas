package kamayuk.rentas.nucleo.aplicacion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
 */
final class ServidorDeMentira implements AutoCloseable {

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
                BufferedReader entrada =
                        new BufferedReader(
                                new InputStreamReader(
                                        abierta.getInputStream(), StandardCharsets.UTF_8));
                OutputStream salida = abierta.getOutputStream()) {
            String peticion = entrada.readLine();
            if (peticion == null) {
                return;
            }
            String ruta = peticion.split(" ")[1];
            int largo = 0;
            String linea;
            while ((linea = entrada.readLine()) != null && !linea.isEmpty()) {
                if (linea.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    largo = Integer.parseInt(linea.substring(linea.indexOf(':') + 1).strip());
                }
            }
            char[] cuerpo = new char[largo];
            if (largo > 0) {
                int leidos = 0;
                while (leidos < largo) {
                    int ahora = entrada.read(cuerpo, leidos, largo - leidos);
                    if (ahora < 0) {
                        break;
                    }
                    leidos += ahora;
                }
            }
            String respuesta;
            int estado = 200;
            try {
                respuesta = responder.apply(ruta, new String(cuerpo));
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
}
