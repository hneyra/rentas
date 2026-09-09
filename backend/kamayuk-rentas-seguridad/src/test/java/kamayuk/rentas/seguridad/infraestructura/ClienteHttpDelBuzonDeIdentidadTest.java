package kamayuk.rentas.seguridad.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import kamayuk.rentas.plataforma.CredencialDeServicio;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
import kamayuk.rentas.seguridad.dominio.FuenteDeEventosDeIdentidad;
import kamayuk.rentas.seguridad.dominio.FuenteDeEventosDeIdentidad.AcuseRechazado;
import kamayuk.rentas.seguridad.dominio.FuenteDeEventosDeIdentidad.IdentidadNoContesta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * El cliente contra un buzon de MENTIRA sobre {@code ServerSocket}: se mide la peticion que LLEGO
 * —ruta, cabecera, cuerpo— y no la que se penso mandar. Es la forma de {@code
 * ElTokenDeServicioTest}, y sobre un socket porque Checkstyle no deja {@code
 * com.sun.net.httpserver}.
 */
@DisplayName("Etapa 4 — el cliente HTTP del buzon de identidad")
class ClienteHttpDelBuzonDeIdentidadTest {

    private static final String EVENTO_ID = "0ac39d9c-1c2e-4f1a-9b3d-7a5e2c8f4d10";
    private static final String UN_EVENTO =
            "{\"eventos\":[{\"eventoId\":\""
                    + EVENTO_ID
                    + "\",\"secuencia\":7,\"tipo\":\"USUARIO_DADO_DE_ALTA\",\"sujetoId\":3,"
                    + "\"cuerpo\":\"{\\\"usuarioId\\\":3,\\\"cuenta\\\":\\\"jperez\\\"}\","
                    + "\"huella\":\"abc\",\"creadoEn\":\"2026-09-09T12:00:00Z\"}],\"quedan\":4}";

    private BuzonDeMentira buzon;

    @BeforeEach
    void levantar() throws IOException {
        buzon = BuzonDeMentira.arranca();
    }

    @AfterEach
    void apagar() throws IOException {
        buzon.close();
    }

    private ClienteHttpDelBuzonDeIdentidad cliente() {
        return cliente(CredencialDeServicio.fija("Bearer el-token-de-servicio"));
    }

    private ClienteHttpDelBuzonDeIdentidad cliente(CredencialDeServicio credencial) {
        return new ClienteHttpDelBuzonDeIdentidad(
                JsonMapper.builder().build(), buzon.raiz() + "/", credencial);
    }

    @Test
    @DisplayName(
            "pendientes: GET /eventos/pendientes?limite= con el token, y los siete campos del"
                    + " evento")
    void pendientes() {
        buzon.responde(200, UN_EVENTO);

        FuenteDeEventosDeIdentidad.Lote lote = cliente().pendientes(200);

        assertThat(buzon.lineas())
                .as("la ruta de EventosController, bajo la raiz de la API de identidad")
                .containsExactly("GET /identidad/api/v1/eventos/pendientes?limite=200");
        assertThat(buzon.autorizaciones())
                .as("el token de servicio viaja: sin el, identidad contesta 401")
                .containsExactly("Bearer el-token-de-servicio");
        assertThat(lote.quedan()).isEqualTo(4);
        assertThat(lote.eventos()).hasSize(1);
        EventoDeIdentidadRecibido evento = lote.eventos().getFirst();
        assertThat(evento.eventoId()).isEqualTo(UUID.fromString(EVENTO_ID));
        assertThat(evento.secuencia()).isEqualTo(7);
        assertThat(evento.tipoPublicado()).isEqualTo("USUARIO_DADO_DE_ALTA");
        assertThat(evento.sujetoId()).isEqualTo(3);
        assertThat(evento.cuerpo())
                .as("el cuerpo viaja como TEXTO —la fila entera tal como quedo— y se lee despues")
                .isEqualTo("{\"usuarioId\":3,\"cuenta\":\"jperez\"}");
        assertThat(evento.huella()).isEqualTo("abc");
        assertThat(evento.creadoEn()).isEqualTo(Instant.parse("2026-09-09T12:00:00Z"));
    }

    @Test
    @DisplayName("acusar: POST /eventos/acuses con {\"eventos\":[…]} y se leen las tres cifras")
    void acusar() {
        buzon.responde(200, "{\"recibidos\":2,\"escritos\":1,\"quedan\":9}");
        UUID uno = UUID.fromString(EVENTO_ID);
        UUID otro = UUID.fromString("45bd3978-0000-4000-8000-000000000002");

        FuenteDeEventosDeIdentidad.Acuse acuse = cliente().acusar(List.of(uno, otro));

        assertThat(buzon.lineas()).containsExactly("POST /identidad/api/v1/eventos/acuses");
        assertThat(buzon.cuerpos())
                .as("la forma de EventosController.PeticionDeAcuse: una lista bajo «eventos»")
                .containsExactly("{\"eventos\":[\"" + uno + "\",\"" + otro + "\"]}");
        assertThat(buzon.autorizaciones()).containsExactly("Bearer el-token-de-servicio");
        assertThat(acuse.recibidos()).isEqualTo(2);
        assertThat(acuse.escritos()).isEqualTo(1);
        assertThat(acuse.quedan()).isEqualTo(9);
    }

    @Test
    @DisplayName("acusar nada no viaja: cero peticiones")
    void acusarNada() {
        FuenteDeEventosDeIdentidad.Acuse acuse = cliente().acusar(List.of());

        assertThat(acuse.recibidos()).isZero();
        assertThat(buzon.lineas()).isEmpty();
    }

    @Test
    @DisplayName(
            "un 422 al acusar es AcuseRechazado, con el estado y el cuerpo que identidad escribio")
    void acuseRechazado() {
        buzon.responde(422, "{\"codigo\":\"VALIDACION\",\"detail\":\"no consta: 45bd3978\"}");

        Throwable rechazo = catchThrowable(() -> cliente().acusar(List.of(UUID.randomUUID())));

        assertThat(rechazo)
                .as(
                        "[un 4xx de negocio al acusar no es transitorio: identidad entendio la"
                                + " peticion y la rechazo, y reintentarla es recibir el mismo 422"
                                + " cincuenta veces]")
                .isInstanceOf(AcuseRechazado.class);
        assertThat(((AcuseRechazado) rechazo).estado()).isEqualTo(422);
        assertThat(rechazo.getMessage()).contains("no consta: 45bd3978");
    }

    @Test
    @DisplayName("un 401 es transitorio y nombra la credencial; un 403, el grupo que falta")
    void credencialQueNoVale() {
        buzon.responde(401, "{\"codigo\":\"NO_AUTENTICADO\"}");
        assertThatThrownBy(() -> cliente().pendientes(10))
                .as(
                        "[un 401 NO es AcuseRechazado: la clave se arregla en el despliegue y el"
                                + " evento tiene que seguir en el buzon]")
                .isInstanceOf(IdentidadNoContesta.class)
                .hasMessageContaining("401")
                .hasMessageContaining("kamayuk.identidad.credencial");
        assertThatThrownBy(() -> cliente().acusar(List.of(UUID.randomUUID())))
                .isInstanceOf(IdentidadNoContesta.class)
                .isNotInstanceOf(AcuseRechazado.class);

        buzon.responde(403, "{\"codigo\":\"SIN_IDENTIDAD_DE_SERVICIO\"}");
        assertThatThrownBy(() -> cliente().pendientes(10))
                .isInstanceOf(IdentidadNoContesta.class)
                .hasMessageContaining("403")
                .hasMessageContaining("Consumidores del buzon");
    }

    @Test
    @DisplayName("un 500, un cuerpo que no es JSON y un evento sin forma son «no contesta»")
    void respuestasQueNoSirven() {
        buzon.responde(500, "{}");
        assertThatThrownBy(() -> cliente().pendientes(10))
                .isInstanceOf(IdentidadNoContesta.class)
                .hasMessageContaining("500");

        buzon.responde(200, "<html>");
        assertThatThrownBy(() -> cliente().pendientes(10))
                .isInstanceOf(IdentidadNoContesta.class)
                .hasMessageContaining("no es JSON");

        buzon.responde(200, "{\"eventos\":[{\"eventoId\":\"esto-no-es-un-uuid\"}],\"quedan\":0}");
        assertThatThrownBy(() -> cliente().pendientes(10))
                .isInstanceOf(IdentidadNoContesta.class)
                .hasMessageContaining("no tiene la forma de un evento");
    }

    @Test
    @DisplayName("un puerto que nadie escucha es «no contesta», y no un rechazo")
    void nadieEscucha() throws IOException {
        int puerto = buzon.puerto();
        buzon.close();

        ClienteHttpDelBuzonDeIdentidad apagado =
                new ClienteHttpDelBuzonDeIdentidad(
                        JsonMapper.builder().build(),
                        "http://127.0.0.1:" + puerto + "/identidad/api/v1",
                        CredencialDeServicio.fija("Bearer x"));

        assertThatThrownBy(() -> apagado.pendientes(10))
                .isInstanceOf(IdentidadNoContesta.class)
                .hasMessageContaining("leer el buzon");
    }

    @Test
    @DisplayName(
            "si el emisor no da el token, es «no contesta» de ESTE puerto y no viaja ninguna"
                    + " peticion")
    void sinToken() {
        ClienteHttpDelBuzonDeIdentidad sinEmisor =
                cliente(
                        () -> {
                            throw new CredencialDeServicio.NoSePudoObtener(
                                    "el emisor contesto 503 al pedir el token");
                        });

        assertThatThrownBy(() -> sinEmisor.pendientes(10))
                .isInstanceOf(IdentidadNoContesta.class)
                .hasMessageContaining("token de servicio")
                .hasMessageContaining("el emisor contesto 503")
                .hasCauseInstanceOf(CredencialDeServicio.NoSePudoObtener.class);
        assertThat(buzon.lineas()).as("sin token no se llama a identidad").isEmpty();
    }

    @Test
    @DisplayName(
            "sin credencial configurada la peticion sale sin cabecera, y es identidad quien dice"
                    + " 401")
    void sinCredencial() {
        buzon.responde(200, "{\"eventos\":[],\"quedan\":0}");

        cliente(CredencialDeServicio.fija("")).pendientes(10);

        assertThat(buzon.autorizaciones()).containsExactly("");
    }

    // ------------------------------------------------------------------

    private static final class BuzonDeMentira implements AutoCloseable {

        private final ServerSocket socket;
        private final Thread hilo;
        private final List<String> lineas = Collections.synchronizedList(new ArrayList<>());
        private final List<String> cuerpos = Collections.synchronizedList(new ArrayList<>());
        private final List<String> cabeceras = Collections.synchronizedList(new ArrayList<>());
        private volatile int estado = 200;
        private volatile String cuerpo = "{}";

        private BuzonDeMentira(ServerSocket socket) {
            this.socket = socket;
            this.hilo =
                    new Thread(
                            () -> {
                                while (!socket.isClosed()) {
                                    try (Socket cliente = socket.accept()) {
                                        Peticion recibida = leerPeticion(cliente);
                                        lineas.add(recibida.linea());
                                        cabeceras.add(recibida.autorizacion());
                                        cuerpos.add(recibida.cuerpo());
                                        responder(cliente, estado, cuerpo);
                                    } catch (IOException cerrado) {
                                        return;
                                    }
                                }
                            },
                            "buzon-de-identidad-de-mentira");
            this.hilo.setDaemon(true);
        }

        static BuzonDeMentira arranca() throws IOException {
            ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
            BuzonDeMentira buzon = new BuzonDeMentira(socket);
            buzon.hilo.start();
            return buzon;
        }

        void responde(int estado, String cuerpo) {
            this.estado = estado;
            this.cuerpo = cuerpo;
        }

        String raiz() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/identidad/api/v1";
        }

        int puerto() {
            return socket.getLocalPort();
        }

        List<String> lineas() {
            return List.copyOf(lineas);
        }

        List<String> cuerpos() {
            return List.copyOf(cuerpos);
        }

        List<String> autorizaciones() {
            return List.copyOf(cabeceras);
        }

        private record Peticion(String linea, String autorizacion, String cuerpo) {}

        private static Peticion leerPeticion(Socket cliente) throws IOException {
            BufferedReader lector =
                    new BufferedReader(
                            new InputStreamReader(
                                    cliente.getInputStream(), StandardCharsets.UTF_8));
            String primera = lector.readLine();
            String lineaDePeticion =
                    primera == null ? "" : primera.substring(0, primera.lastIndexOf(' '));
            int longitud = 0;
            String autorizacion = "";
            String linea = lector.readLine();
            while (linea != null && !linea.isEmpty()) {
                String enMinusculas = linea.toLowerCase(Locale.ROOT);
                if (enMinusculas.startsWith("content-length:")) {
                    longitud = Integer.parseInt(linea.substring(linea.indexOf(':') + 1).trim());
                }
                if (enMinusculas.startsWith("authorization:")) {
                    autorizacion = linea.substring(linea.indexOf(':') + 1).trim();
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
            return new Peticion(
                    lineaDePeticion, autorizacion, new String(datos, 0, Math.max(leidos, 0)));
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
