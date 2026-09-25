package kamayuk.rentas.catastro.infraestructura;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * #450 — A {@code catastro} no se le pregunta con una conexion de la base tomada.
 *
 * <p>Cada viaje de red con una transaccion abierta retiene una de las diez conexiones del pool
 * mientras el vecino contesta: con {@code catastro} lento, rentas entero se queda sin conexiones,
 * guardia de acceso incluido. La guarda va en la costura del cliente —encima de {@code enviar}, que
 * es lo que los dobles sustituyen— para que cace a <b>todos</b> los llamadores, tambien los que el
 * diagnostico no enumero. En produccion avisa; en las pruebas, falla.
 *
 * <p>La siembra que distingue es una transaccion activa en el hilo. Sin ella, cualquier llamador
 * pasa: es el caso de todas las pruebas de los adaptadores, que los llaman sueltos.
 */
@DisplayName("#450 — a `catastro` no se le pregunta con la conexion tomada")
class CatastroSinLaConexionTomadaTest {

    @Test
    @DisplayName("con una transaccion abierta la pregunta no sale, y el error nombra la ruta")
    void conUnaTransaccionAbiertaNoSale() throws IOException {
        ClienteHttpDeCatastro catastro = contra(unPuertoQueNadieEscucha());

        enUnaTransaccion(
                () ->
                        assertThatThrownBy(() -> catastro.pedir("/predios/450", "leer el predio"))
                                .as(
                                        "la guarda salta ANTES de ir a la red: sin ella, esto seria"
                                                + " un CatastroInalcanzable despues de esperar")
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("#450")
                                .hasMessageContaining("catastro")
                                .hasMessageContaining("/predios/450"));
    }

    @Test
    @DisplayName("y la lectura que traduce hechos del territorio pasa por la misma guarda")
    void laLecturaDeHechosTambien() throws IOException {
        ClienteHttpDeCatastro catastro = contra(unPuertoQueNadieEscucha());

        enUnaTransaccion(
                () ->
                        assertThatThrownBy(
                                        () ->
                                                catastro.pedirHechoDelTerritorio(
                                                        "/urbano/zonificacion?predioId=450",
                                                        "leer la zona"))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("#450"));
    }

    @Test
    @DisplayName("sin transaccion la pregunta sale, y lo que contesta la red es lo de siempre")
    void sinTransaccionSale() throws IOException {
        ClienteHttpDeCatastro catastro = contra(unPuertoQueNadieEscucha());

        assertThatThrownBy(() -> catastro.pedir("/predios/450", "leer el predio"))
                .as("nadie escucha: el cliente fue a la red y no encontro a nadie")
                .isInstanceOf(ClienteHttpDeCatastro.CatastroInalcanzable.class);
    }

    // ------------------------------------------------------------------

    private static ClienteHttpDeCatastro contra(int puerto) {
        return new ClienteHttpDeCatastro(
                new JsonMapper(), "http://127.0.0.1:" + puerto + "/catastro/api/v1");
    }

    /** Lo que {@code TenantTransactionManager} deja en el hilo al abrir una transaccion. */
    static void enUnaTransaccion(Runnable accion) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            accion.run();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static int unPuertoQueNadieEscucha() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
