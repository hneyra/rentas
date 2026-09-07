package kamayuk.rentas.parametros.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MotivoDeInalcanzable;
import kamayuk.rentas.parametros.dominio.PublicadorDeNormativa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * #25 — {@code rentas} le reenvia a {@code normativa} el token del llamante, como a los otros dos.
 *
 * <h2>El defecto que cierra, medido contra la instalacion antes de escribir una linea</h2>
 *
 * <p>{@code ClienteHttpDeCaja} y {@code ClienteHttpDeCatastro} reenvian el {@code Authorization} de
 * la peticion que se atiende desde P5C y P5D —la misma linea, el mismo ayudante, el mismo parrafo
 * de javadoc—. {@code ClienteHttpDeNormativa} <b>era el unico de los tres que no lo hacia</b>:
 * mandaba la peticion con {@code Accept} y nada mas.
 *
 * <p>Lo medido: {@code GET /normativa/api/v1/conjuntos?ejercicio=2026} desde dentro del contenedor
 * de {@code rentas} contesta <b>401 {@code NO_AUTENTICADO}</b> sin cabecera y <b>404</b> con el
 * token del llamante — y ese 404 es un hecho del dominio, «ese ejercicio no tiene conjunto
 * sellado», que {@link ClienteHttpDeNormativa#conjuntoVigenteEn} ya traducia a {@code
 * EjercicioSinSellar}.
 *
 * <p><b>Por eso el 401 no era el hueco de identidad de servicio de ADR-0028 §2</b>, que es como se
 * habia leido: era una cabecera que faltaba. El hueco de ADR-0028 §2 sigue existiendo y sigue sin
 * cerrarse, pero es el del perfil {@code batch} —donde no hay peticion de la que tomar el token—,
 * no el de una lectura que siempre corre dentro de una peticion HTTP.
 */
@DisplayName("#25 — `normativa` recibe el token del llamante")
class NormativaRecibeElTokenTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private @org.jspecify.annotations.Nullable ServidorQueApunta servidor;

    @AfterEach
    void limpiar() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        if (servidor != null) {
            servidor.cerrar();
            servidor = null;
        }
    }

    private ClienteHttpDeNormativa clienteContra(ServidorQueApunta servidor) {
        return new ClienteHttpDeNormativa(
                new JsonMapper(), "http://127.0.0.1:" + servidor.puerto() + "/normativa/api/v1");
    }

    /** Deja una peticion HTTP «en curso» con esa cabecera, como la que el borde esta atendiendo. */
    private static void atendiendoUnaPeticionCon(String autorizacion) {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.addHeader("Authorization", autorizacion);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(peticion));
    }

    @Nested
    @DisplayName("La cabecera que faltaba")
    class LaCabecera {

        @Test
        @DisplayName("reenvia el `Authorization` de la peticion que se esta atendiendo")
        void reenviaElAuthorization() throws Exception {
            servidor = ServidorQueApunta.queContesta("{\"conjuntoId\":42}");
            atendiendoUnaPeticionCon("Bearer el-token-del-llamante");

            long conjunto = clienteContra(servidor).conjuntoVigenteEn(EJERCICIO);

            assertThat(conjunto).isEqualTo(42L);
            assertThat(servidor.cabeceras())
                    .as("sin esta cabecera `normativa` contesta 401 y el ejercicio no se resuelve")
                    .anyMatch(
                            c -> c.equalsIgnoreCase("Authorization: Bearer el-token-del-llamante"));
        }

        @Test
        @DisplayName("EL CONTRASTE: sin peticion en curso —perfil `batch`— no se inventa ninguna")
        void sinPeticionNoSeInventaNinguna() throws Exception {
            servidor = ServidorQueApunta.queContesta("{\"conjuntoId\":42}");
            // Sin `RequestContextHolder`: es el perfil `batch`, donde no hay token que tomar.
            // Ahi el 401 SI es el hueco de identidad de servicio de ADR-0028 §2, y no se tapa
            // mandando una credencial inventada.

            clienteContra(servidor).conjuntoVigenteEn(EJERCICIO);

            assertThat(servidor.cabeceras())
                    .noneMatch(
                            c -> c.toLowerCase(java.util.Locale.ROOT).startsWith("authorization"));
        }
    }

    @Nested
    @DisplayName("AC-4 — la ausencia se distingue de la averia")
    class ElMotivo {

        @Test
        @DisplayName("sin la URL configurada el motivo es SIN_CONFIGURAR")
        void sinConfigurar() {
            ClienteHttpDeNormativa sinUrl = new ClienteHttpDeNormativa(new JsonMapper(), "");

            assertThatThrownBy(() -> sinUrl.conjuntoVigenteEn(EJERCICIO))
                    .isInstanceOf(PublicadorDeNormativa.NormativaInalcanzable.class)
                    .extracting(
                            fallo -> ((PublicadorDeNormativa.NormativaInalcanzable) fallo).motivo())
                    .isEqualTo(MotivoDeInalcanzable.SIN_CONFIGURAR);
        }

        @Test
        @DisplayName("y con la URL puesta y nadie escuchando, NO_CONTESTA")
        void noContesta() throws Exception {
            int puertoMuerto = unPuertoQueNadieEscucha();
            ClienteHttpDeNormativa caido =
                    new ClienteHttpDeNormativa(
                            new JsonMapper(),
                            "http://127.0.0.1:" + puertoMuerto + "/normativa/api/v1");

            assertThatThrownBy(() -> caido.conjuntoVigenteEn(EJERCICIO))
                    .isInstanceOf(PublicadorDeNormativa.NormativaInalcanzable.class)
                    .extracting(
                            fallo -> ((PublicadorDeNormativa.NormativaInalcanzable) fallo).motivo())
                    .isEqualTo(MotivoDeInalcanzable.NO_CONTESTA);
        }

        @Test
        @DisplayName(
                "las dos son el MISMO tipo: quien decide lo hace por el motivo, no por el texto")
        void elMismoTipo() throws Exception {
            ClienteHttpDeNormativa sinUrl = new ClienteHttpDeNormativa(new JsonMapper(), "");
            int puertoMuerto = unPuertoQueNadieEscucha();
            ClienteHttpDeNormativa caido =
                    new ClienteHttpDeNormativa(
                            new JsonMapper(),
                            "http://127.0.0.1:" + puertoMuerto + "/normativa/api/v1");

            // El tipo es el mismo a proposito —los doce sitios que cazan `NormativaInalcanzable`
            // siguen cazandola igual—, y lo que los separa es un dato y no una frase en castellano,
            // que es lo que `NoConstaEnCatastro.codigo()` establecio como forma en este
            // repositorio.
            assertThatThrownBy(() -> sinUrl.conjuntoVigenteEn(EJERCICIO))
                    .isInstanceOf(PublicadorDeNormativa.NormativaInalcanzable.class);
            assertThatThrownBy(() -> caido.conjuntoVigenteEn(EJERCICIO))
                    .isInstanceOf(PublicadorDeNormativa.NormativaInalcanzable.class);
        }
    }

    /** Un puerto reservado y soltado: nadie escucha ahi, y conectarse falla en el acto. */
    private static int unPuertoQueNadieEscucha() throws IOException {
        try (ServerSocket reservado = new ServerSocket(0)) {
            return reservado.getLocalPort();
        }
    }

    /**
     * Un servidor de una sola peticion que <b>guarda las cabeceras que le llegaron</b>.
     *
     * <p>Es un {@code ServerSocket} a mano y no un doble del cliente, porque lo que se mide es
     * exactamente lo que viaja por el cable: un doble que sustituyera {@code pedir} no podria
     * demostrar que la cabecera sale.
     */
    private static final class ServidorQueApunta implements AutoCloseable {

        private final ServerSocket socket;
        private final List<String> cabeceras = new ArrayList<>();
        private final CountDownLatch atendida = new CountDownLatch(1);
        private final Thread hilo;

        private ServidorQueApunta(String cuerpo) throws IOException {
            this.socket = new ServerSocket(0);
            this.hilo =
                    new Thread(
                            () -> {
                                try (Socket cliente = socket.accept()) {
                                    BufferedReader entrada =
                                            new BufferedReader(
                                                    new InputStreamReader(
                                                            cliente.getInputStream(),
                                                            StandardCharsets.UTF_8));
                                    String linea;
                                    while ((linea = entrada.readLine()) != null
                                            && !linea.isEmpty()) {
                                        cabeceras.add(linea);
                                    }
                                    byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
                                    OutputStream salida = cliente.getOutputStream();
                                    salida.write(
                                            ("HTTP/1.1 200 OK\r\nContent-Type:"
                                                            + " application/json\r\nContent-Length: "
                                                            + bytes.length
                                                            + "\r\n\r\n")
                                                    .getBytes(StandardCharsets.UTF_8));
                                    salida.write(bytes);
                                    salida.flush();
                                } catch (IOException cerrado) {
                                    // El socket se cerro: es como termina este servidor.
                                } finally {
                                    atendida.countDown();
                                }
                            });
            this.hilo.setDaemon(true);
            this.hilo.start();
        }

        static ServidorQueApunta queContesta(String cuerpo) throws IOException {
            return new ServidorQueApunta(cuerpo);
        }

        int puerto() {
            return socket.getLocalPort();
        }

        /** Las lineas de cabecera que llegaron, ya atendida la peticion. */
        List<String> cabeceras() throws InterruptedException {
            atendida.await(10, TimeUnit.SECONDS);
            return List.copyOf(cabeceras);
        }

        void cerrar() throws Exception {
            close();
        }

        @Override
        public void close() throws Exception {
            socket.close();
            hilo.join(TimeUnit.SECONDS.toMillis(5));
        }
    }
}
