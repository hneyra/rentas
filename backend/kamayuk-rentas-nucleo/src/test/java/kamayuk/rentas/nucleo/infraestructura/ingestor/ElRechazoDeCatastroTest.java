package kamayuk.rentas.nucleo.infraestructura.ingestor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import kamayuk.rentas.nucleo.dominio.proyeccion.FuenteDeHechosDeCatastro.CatastroNoContesta;
import kamayuk.rentas.plataforma.CredencialDeServicio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lo que `catastro` contesta cuando NO contesta 200, y que de ahi se pueda diagnosticar (#166).
 *
 * <p><b>De donde sale</b>: medido en `stg`, el {@code CronJob} del ingestor lleva todas sus
 * corridas en rojo, `catastro` contesta 403, y el mensaje era «`catastro` contesto 403 al leer el
 * buzon de catastro» — el codigo de verdad, pero sin el cuerpo, que es lo unico que dice cual de
 * las tres ramas del 403 es. Con eso no se puede saber si falta un alta, si falta un acceso o si el
 * token no lleva la municipalidad, que se arreglan en tres sitios distintos.
 *
 * <p>Contra un buzon de MENTIRA sobre {@code ServerSocket}, que es la forma de {@code
 * ElTokenDeServicioTest} y de {@code ClienteHttpDelBuzonDeIdentidadTest}: Checkstyle no deja {@code
 * com.sun.net.httpserver}.
 */
@DisplayName("#166 — lo que `catastro` contesto viaja en el mensaje")
class ElRechazoDeCatastroTest {

    /**
     * Los cuerpos con que `catastro` contesta 403, con la forma de su {@code ManejadorDeErrores}.
     */
    private static final String SIN_FICHA =
            "{\"detail\":\"La cuenta «kamayuk-rentas-servicio-200105» no esta dada de alta en este"
                    + " sistema. No es que le falte un privilegio: no tiene ninguna ficha"
                    + " aqui\",\"codigo\":\"SIN_PRIVILEGIO\"}";

    private static final String SIN_EL_PRIVILEGIO =
            "{\"detail\":\"No tiene el privilegio LECTURA sobre consulta_fichas\","
                    + "\"codigo\":\"SIN_PRIVILEGIO\"}";

    private static final String SIN_MUNICIPALIDAD =
            "{\"detail\":\"El token no identifica una municipalidad\","
                    + "\"codigo\":\"SIN_MUNICIPALIDAD\"}";

    private BuzonDeMentira buzon;

    @BeforeEach
    void levantar() throws IOException {
        buzon = BuzonDeMentira.arranca();
    }

    @AfterEach
    void apagar() throws IOException {
        buzon.close();
    }

    private ClienteHttpDelBuzonDeCatastro cliente() {
        return new ClienteHttpDelBuzonDeCatastro(
                JsonMapper.builder().build(),
                buzon.raiz(),
                CredencialDeServicio.fija("Bearer el-token-de-servicio"));
    }

    /**
     * Las tres ramas juntas a proposito: lo que hay que demostrar no es que cada una diga algo,
     * sino que dicen cosas <b>distintas</b>. Un texto fijo que las nombrara a las tres pasaria una
     * prueba por rama y no esta.
     */
    @Test
    @DisplayName("los tres 403 de `catastro` se distinguen, y cada uno manda a SU sitio")
    void losTres403NoSeConfunden() {
        buzon.responde(403, SIN_FICHA);
        assertThatThrownBy(() -> cliente().pendientes(10))
                .isInstanceOf(CatastroNoContesta.class)
                .hasMessageContaining("403")
                .hasMessageContaining("SIN_PRIVILEGIO")
                .hasMessageContaining("no esta dada de alta")
                .hasMessageContaining("NO tiene ficha")
                .hasMessageNotContaining("consulta_fichas");

        buzon.responde(403, SIN_EL_PRIVILEGIO);
        assertThatThrownBy(() -> cliente().pendientes(10))
                .as("[esta es la unica de las tres que se arregla concediendo un acceso]")
                .isInstanceOf(CatastroNoContesta.class)
                .hasMessageContaining("consulta_fichas");

        buzon.responde(403, SIN_MUNICIPALIDAD);
        assertThatThrownBy(() -> cliente().pendientes(10))
                .as("[el claim sale del emisor, no de ningun permiso de `catastro`]")
                .isInstanceOf(CatastroNoContesta.class)
                .hasMessageContaining("SIN_MUNICIPALIDAD")
                .hasMessageContaining("cliente confidencial")
                .hasMessageNotContaining("consulta_fichas");
    }

    @Test
    @DisplayName("un 403 que no dice por que LO DICE, en vez de elegir una rama")
    void unCuatrocientosTresMudo() {
        buzon.responde(403, "");
        assertThatThrownBy(() -> cliente().pendientes(10))
                .as(
                        "[inventar una causa cuando el emisor no la dijo es el defecto de #66, y"
                                + " aqui no se estrena]")
                .isInstanceOf(CatastroNoContesta.class)
                .hasMessageContaining("VACIO")
                .hasMessageNotContaining("consulta_fichas");

        buzon.responde(403, "<html><title>403 Forbidden</title>");
        assertThatThrownBy(() -> cliente().pendientes(10))
                .as("[un HTML es un proxy contestando por el, que es OTRO sitio donde mirar]")
                .isInstanceOf(CatastroNoContesta.class)
                .hasMessageContaining("403 Forbidden")
                .hasMessageNotContaining("consulta_fichas");
    }

    @Test
    @DisplayName("al acusar tambien, y sin perder que los hechos SI estan aplicados aqui")
    void alAcusarTambien() {
        buzon.responde(403, SIN_EL_PRIVILEGIO);

        assertThatThrownBy(() -> cliente().acusar(List.of(UUID.randomUUID())))
                .as(
                        "[lo que impide que alguien lea «403 al acusar» y salga a buscar los"
                                + " hechos perdidos: no hay ninguno]")
                .isInstanceOf(CatastroNoContesta.class)
                .hasMessageContaining("Los hechos SI estan aplicados aqui")
                .hasMessageContaining("REGISTRO");
    }

    @Test
    @DisplayName("un 401 nombra la credencial y las dos propiedades donde se arregla")
    void unCuatrocientosUno() {
        buzon.responde(401, "{\"codigo\":\"NO_AUTENTICADO\"}");

        assertThatThrownBy(() -> cliente().pendientes(10))
                .isInstanceOf(CatastroNoContesta.class)
                .hasMessageContaining("NO_AUTENTICADO")
                .hasMessageContaining("kamayuk.catastro.credencial");
    }

    @Test
    @DisplayName("el cuerpo viaja SIN el token: un eco de la peticion no se lleva la credencial")
    void elCuerpoNoSeLlevaElToken() {
        // SIN «codigo» a proposito: es justo el caso en que el cuerpo entero viaja al mensaje —un
        // proxy delante contestando por `catastro`, con el eco de la peticion dentro—. Con un
        // «codigo» el mensaje se compone de el y del detalle, y esta prueba no podria fallar.
        buzon.responde(
                403,
                "{\"error\":\"forbidden\",\"peticion\":{\"Authorization\":\"Bearer"
                        + " el-token-de-servicio\"}}");

        assertThatThrownBy(() -> cliente().pendientes(10))
                .as(
                        "[este mensaje acaba en el registro del CronJob: un token de servicio ahi"
                                + " es un incidente, no una molestia]")
                .isInstanceOf(CatastroNoContesta.class)
                .hasMessageContaining("forbidden")
                .hasMessageNotContaining("el-token-de-servicio");
    }

    // ------------------------------------------------------------------

    /** El buzon fabricado: contesta lo que se le pida, y no mira lo que le llega. */
    private static final class BuzonDeMentira implements AutoCloseable {

        private final ServerSocket socket;
        private final Thread hilo;
        private volatile int estado = 200;
        private volatile String cuerpo = "{}";

        private BuzonDeMentira(ServerSocket socket) {
            this.socket = socket;
            this.hilo =
                    new Thread(
                            () -> {
                                while (!socket.isClosed()) {
                                    try (Socket cliente = socket.accept()) {
                                        consumirPeticion(cliente);
                                        responder(cliente, estado, cuerpo);
                                    } catch (IOException cerrado) {
                                        return;
                                    }
                                }
                            },
                            "buzon-de-catastro-de-mentira");
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
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        private static void consumirPeticion(Socket cliente) throws IOException {
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
