package kamayuk.rentas.nucleo.infraestructura.ingestor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import kamayuk.rentas.nucleo.dominio.proyeccion.FuenteDeHechosDeCatastro.CatastroNoContesta;
import kamayuk.rentas.plataforma.CredencialDeServicio;
import kamayuk.rentas.plataforma.TokenDeServicioDeKeycloak;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * #21 AC-2 — <b>el ingestor pide un token de verdad, y no manda una cadena configurada.</b>
 *
 * <h2>El defecto del que sale</h2>
 *
 * <p>Hasta #21 lo que el ingestor mandaba en el {@code Authorization} era el valor de {@code
 * kamayuk.catastro.credencial}, y lo que el descriptor pone ahi es lo que {@code
 * bootstrap-secretos.sh} genera: <b>una cadena aleatoria que ningun emisor firmo</b>. {@code
 * catastro} contestaba 401, y por eso el {@code CronJob} nacia suspendido — con la proyeccion
 * vacia, {@code CandadoDeEmision} no deja emitir nunca.
 *
 * <h2>El instrumento: un emisor de verdad, no un doble</h2>
 *
 * <p>Se levanta un servidor HTTP que contesta como el punto de emision de Keycloak. Un doble que
 * devolviera «Bearer x» probaria que el codigo llama a un metodo; un emisor de verdad prueba que la
 * peticion se compone bien —{@code grant_type=client_credentials}, el cliente y su clave por
 * cuerpo— y que del JSON sale el token que despues viaja. Es la doctrina que {@code
 * CobrarConElOrigenApagadoTest} escribio y {@code UnPagoNoMuereSinCredencialTest} siguio.
 *
 * <p>Y sobre un {@link ServerSocket} a mano, no con {@code com.sun.net.httpserver}: Checkstyle lo
 * prohibe —es un paquete de la implementacion— y lo caza el checkstyle de las PRUEBAS, que no corre
 * con {@code :test}.
 *
 * <h2>Lo que se mide, y por que hace falta cada pieza</h2>
 *
 * <p>Que el token llegue al destino; que se GUARDE, porque una vuelta son varias llamadas —traer y
 * acusar, pagina a pagina—; que se RENUEVE, porque un token guardado para siempre es un 401
 * diferido; y que un emisor que no emite se trate como {@code CatastroNoContesta}, porque en este
 * camino todo fallo es transitorio a proposito: lo que no puede pasar es que un fallo de transporte
 * mate un hecho.
 */
@DisplayName("#21 AC-2 — el ingestor pide su token con client_credentials")
class ElTokenDeServicioTest {

    private static final Instant AHORA = Instant.parse("2026-09-09T12:00:00Z");
    private static final String CLIENTE = "kamayuk-rentas-servicio-200105";

    private EmisorDeMentira emisor;
    private Instant ahora = AHORA;

    @BeforeEach
    void levantar() throws IOException {
        emisor = EmisorDeMentira.arranca();
    }

    @AfterEach
    void apagar() throws IOException {
        emisor.close();
    }

    private TokenDeServicioDeKeycloak proveedor() {
        return new TokenDeServicioDeKeycloak(
                JsonMapper.builder().build(),
                Clock.fixed(AHORA, ZoneOffset.UTC),
                emisor.raiz(),
                CLIENTE,
                "la-clave-del-cliente");
    }

    /** El mismo, pero con un reloj que se puede mover: es lo que mide la renovacion. */
    private TokenDeServicioDeKeycloak proveedorConRelojMovil() {
        Clock movil =
                new Clock() {
                    @Override
                    public ZoneOffset getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(java.time.ZoneId zona) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return ahora;
                    }
                };
        return new TokenDeServicioDeKeycloak(
                JsonMapper.builder().build(),
                movil,
                emisor.raiz(),
                CLIENTE,
                "la-clave-del-cliente");
    }

    @Test
    @DisplayName("pide el token y devuelve la cabecera con el que el emisor emitio")
    void pideElToken() {
        emisor.responde(200, "{\"access_token\":\"el-token-de-verdad\",\"expires_in\":300}");

        assertThat(proveedor().cabecera()).isEqualTo("Bearer el-token-de-verdad");
    }

    @Test
    @DisplayName("y la peticion lleva client_credentials, el cliente y su clave")
    void componeLaPeticion() {
        emisor.responde(200, "{\"access_token\":\"t\",\"expires_in\":300}");

        proveedor().cabecera();

        // Se mira el cuerpo que llego, no el que se penso mandar: un emisor real rechaza una
        // peticion sin `grant_type`, y ese 400 se leeria como «la clave no vale».
        assertThat(emisor.cuerpos()).hasSize(1);
        assertThat(emisor.cuerpos().get(0))
                .contains("grant_type=client_credentials")
                .contains("client_id=kamayuk-rentas-servicio-200105")
                .contains("client_secret=la-clave-del-cliente");
    }

    @Test
    @DisplayName("lo GUARDA: dos llamadas, un solo viaje al emisor")
    void loGuarda() {
        emisor.responde(200, "{\"access_token\":\"t\",\"expires_in\":300}");
        TokenDeServicioDeKeycloak proveedor = proveedor();

        proveedor.cabecera();
        proveedor.cabecera();

        assertThat(emisor.peticiones()).isEqualTo(1);
    }

    @Test
    @DisplayName("y lo RENUEVA antes de que caduque, que es lo que impide un 401 diferido")
    void loRenueva() {
        emisor.responde(200, "{\"access_token\":\"t\",\"expires_in\":300}");
        TokenDeServicioDeKeycloak proveedor = proveedorConRelojMovil();
        proveedor.cabecera();

        // 300 s de vigencia menos 30 de margen: a los 271 ya hay que pedir otro. El margen no es
        // cosmetico — entre pedirlo y usarlo pasa tiempo, y un token que caduca EN VUELO se ve
        // igual que una credencial mala: un 401.
        ahora = AHORA.plus(Duration.ofSeconds(271));
        proveedor.cabecera();

        assertThat(emisor.peticiones()).isEqualTo(2);
    }

    @Test
    @DisplayName("EL CONTRASTE: antes del margen no lo renueva")
    void antesDelMargenNoLoRenueva() {
        emisor.responde(200, "{\"access_token\":\"t\",\"expires_in\":300}");
        TokenDeServicioDeKeycloak proveedor = proveedorConRelojMovil();
        proveedor.cabecera();

        ahora = AHORA.plus(Duration.ofSeconds(269));
        proveedor.cabecera();

        // Sin este contraste, «lo renueva» se cumpliria pidiendolo siempre, que es exactamente lo
        // que la prueba de arriba dice que no hay que hacer.
        assertThat(emisor.peticiones()).isEqualTo(1);
    }

    @Test
    @DisplayName("sin `expires_in` no lo guarda: suponer una vigencia cuesta un 401 a media tanda")
    void sinVigenciaNoLoGuarda() {
        emisor.responde(200, "{\"access_token\":\"t\"}");
        TokenDeServicioDeKeycloak proveedor = proveedor();

        proveedor.cabecera();
        proveedor.cabecera();

        assertThat(emisor.peticiones()).isEqualTo(2);
    }

    @Test
    @DisplayName("un emisor que rechaza la clave es transitorio: se reintenta la vuelta")
    void unEmisorQueRechazaSeReintenta() {
        emisor.responde(401, "{\"error\":\"invalid_client\"}");

        // El proveedor vive en `plataforma` desde la etapa 4 de ADR-0039 y no sabe de que buzon
        // es: lanza su propia excepcion, que es transitoria por definicion.
        assertThatThrownBy(() -> proveedor().cabecera())
                .isInstanceOf(CredencialDeServicio.NoSePudoObtener.class)
                // El mensaje nombra al cliente y dice donde se arregla. Acaba en
                // el aviso de la vuelta: «el emisor contesto 401» manda al despliegue,
                // «catastro no contesta» manda a mirar un sistema que esta perfectamente.
                .hasMessageContaining(CLIENTE)
                .hasMessageContaining("es quien llama");
    }

    @Test
    @DisplayName("y visto desde el buzon de `catastro` es CatastroNoContesta, con el mismo mensaje")
    void desdeElBuzonDeCatastroEsCatastroNoContesta() throws IOException {
        emisor.responde(401, "{\"error\":\"invalid_client\"}");
        try (EmisorDeMentira catastro = EmisorDeMentira.arranca()) {
            ClienteHttpDelBuzonDeCatastro cliente =
                    new ClienteHttpDelBuzonDeCatastro(
                            JsonMapper.builder().build(),
                            "http://127.0.0.1:" + catastro.puerto(),
                            proveedor());

            // Es lo que la vuelta reintenta: sin esta traduccion, `IngestarHechosDeCatastro`
            // veria una excepcion que no conoce y la vuelta moriria sin su aviso.
            assertThatThrownBy(() -> cliente.pendientes(10))
                    .isInstanceOf(CatastroNoContesta.class)
                    .hasMessageContaining(CLIENTE);
            assertThat(catastro.peticiones()).isZero();
        }
    }

    @Test
    @DisplayName("un 200 sin `access_token` tampoco pasa por bueno")
    void unDoscientosVacioNoPasa() {
        emisor.responde(200, "{\"scope\":\"kamayuk-servicio\"}");

        assertThatThrownBy(() -> proveedor().cabecera())
                .isInstanceOf(CredencialDeServicio.NoSePudoObtener.class)
                .hasMessageContaining("no esta emitiendo");
    }

    @Test
    @DisplayName("EL CONTRASTE: sin identidad configurada no llama a nadie y devuelve vacio")
    void sinConfigurarNoLlama() {
        TokenDeServicioDeKeycloak proveedor =
                new TokenDeServicioDeKeycloak(
                        JsonMapper.builder().build(),
                        Clock.fixed(AHORA, ZoneOffset.UTC),
                        emisor.raiz(),
                        CLIENTE,
                        "");

        // Vacio y no una excepcion: sin identidad la llamada sale sin credencial y `catastro` la
        // rechaza, que sigue siendo lo correcto. Y sobre todo: NO se llama al emisor, o el compose
        // sin Keycloak se pasaria la vida pidiendo tokens que nadie va a dar.
        assertThat(proveedor.cabecera()).isEmpty();
        assertThat(proveedor.configurada()).isFalse();
        assertThat(emisor.peticiones()).isZero();
    }

    /**
     * <b>La afirmacion entera del AC-2</b>: el token que el emisor emite es el que llega al otro
     * sistema.
     *
     * <p>Las de arriba miden al proveedor por separado; esta mide el circuito. Sin ella, un
     * proveedor perfecto y un cliente HTTP que no lo consultara pasarian las nueve — que es
     * exactamente el estado del que se sale, porque hasta #21 el cliente mandaba una cadena
     * configurada y nadie comparaba las dos puntas.
     */
    @Test
    @DisplayName("y el token que el emisor emitio es el que le llega a `catastro`")
    void elTokenLlegaAlDestino() throws IOException {
        emisor.responde(200, "{\"access_token\":\"el-token-de-verdad\",\"expires_in\":300}");
        try (EmisorDeMentira catastro = EmisorDeMentira.arranca()) {
            catastro.responde(200, "{\"eventos\":[],\"pendientesQueQuedan\":0}");
            ClienteHttpDelBuzonDeCatastro cliente =
                    new ClienteHttpDelBuzonDeCatastro(
                            JsonMapper.builder().build(),
                            "http://127.0.0.1:" + catastro.puerto(),
                            proveedor());

            cliente.pendientes(10);

            assertThat(catastro.autorizaciones()).containsExactly("Bearer el-token-de-verdad");
        }
    }

    /** El punto de emision fabricado: contesta lo que se le pida y guarda lo que le llega. */
    private static final class EmisorDeMentira implements AutoCloseable {

        private final ServerSocket socket;
        private final Thread hilo;
        private final List<String> cuerpos = Collections.synchronizedList(new ArrayList<>());
        private final List<String> cabeceras = Collections.synchronizedList(new ArrayList<>());
        private volatile int estado = 200;
        private volatile String cuerpo = "{}";

        private EmisorDeMentira(ServerSocket socket) {
            this.socket = socket;
            this.hilo =
                    new Thread(
                            () -> {
                                while (!socket.isClosed()) {
                                    try (Socket cliente = socket.accept()) {
                                        Peticion recibida = leerPeticion(cliente);
                                        cabeceras.add(recibida.autorizacion());
                                        cuerpos.add(recibida.cuerpo());
                                        responder(cliente, estado, cuerpo);
                                    } catch (IOException cerrado) {
                                        return;
                                    }
                                }
                            },
                            "emisor-de-mentira");
            this.hilo.setDaemon(true);
        }

        static EmisorDeMentira arranca() throws IOException {
            ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
            EmisorDeMentira emisor = new EmisorDeMentira(socket);
            emisor.hilo.start();
            return emisor;
        }

        void responde(int estado, String cuerpo) {
            this.estado = estado;
            this.cuerpo = cuerpo;
        }

        String raiz() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/realms/kamayuk/token";
        }

        int puerto() {
            return socket.getLocalPort();
        }

        int peticiones() {
            return cuerpos.size();
        }

        List<String> cuerpos() {
            return List.copyOf(cuerpos);
        }

        List<String> autorizaciones() {
            return List.copyOf(cabeceras);
        }

        /**
         * Lee la peticion entera y devuelve su cuerpo.
         *
         * <p>Hay que DRENAR el cuerpo del POST, no basta con parar en la linea en blanco: los bytes
         * que quedaran en el buffer hacen que el cliente vea un RST en vez de la respuesta que esta
         * prueba viene a medir. Es la misma leccion que {@code UnPagoNoMuereSinCredencialTest} tuvo
         * que aprender `caja` con el pago.
         */
        private record Peticion(String autorizacion, String cuerpo) {}

        private static Peticion leerPeticion(Socket cliente) throws IOException {
            BufferedReader lector =
                    new BufferedReader(
                            new InputStreamReader(
                                    cliente.getInputStream(), StandardCharsets.UTF_8));
            int longitud = 0;
            String autorizacion = "";
            String linea = lector.readLine();
            while (linea != null && !linea.isEmpty()) {
                String enMinusculas = linea.toLowerCase(java.util.Locale.ROOT);
                if (enMinusculas.startsWith("content-length:")) {
                    longitud = Integer.parseInt(linea.substring(linea.indexOf(':') + 1).trim());
                }
                if (enMinusculas.startsWith("authorization:")) {
                    autorizacion = linea.substring(linea.indexOf(':') + 1).trim();
                }
                linea = lector.readLine();
            }
            char[] cuerpo = new char[longitud];
            int leidos = 0;
            while (leidos < longitud) {
                int n = lector.read(cuerpo, leidos, longitud - leidos);
                if (n < 0) {
                    break;
                }
                leidos += n;
            }
            return new Peticion(autorizacion, new String(cuerpo, 0, Math.max(leidos, 0)));
        }

        private static void responder(Socket cliente, int estado, String cuerpo)
                throws IOException {
            byte[] datos = cuerpo.getBytes(StandardCharsets.UTF_8);
            String cabeceras =
                    "HTTP/1.1 "
                            + estado
                            + " \r\nContent-Type: application/json\r\nContent-Length: "
                            + datos.length
                            + "\r\nConnection: close\r\n\r\n";
            OutputStream salida = cliente.getOutputStream();
            salida.write(cabeceras.getBytes(StandardCharsets.US_ASCII));
            salida.write(datos);
            salida.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
