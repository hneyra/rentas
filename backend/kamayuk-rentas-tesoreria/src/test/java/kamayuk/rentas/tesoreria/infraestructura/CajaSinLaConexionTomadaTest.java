package kamayuk.rentas.tesoreria.infraestructura;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDate;
import kamayuk.rentas.tesoreria.AvanceDeCaja;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * #450 — A {@code caja} no se le pregunta con una conexion de la base tomada.
 *
 * <p>Es lo que tumbaba rentas entero con {@code caja} lenta: {@code PanelDeRecaudacion} esperaba el
 * avance del dia con su transaccion abierta, y diez aperturas de Inicio se quedaban con las diez
 * conexiones del pool. La guarda va encima de {@code enviar} —lo que los dobles sustituyen— para
 * que cace tambien a los llamadores que nadie ha enumerado.
 *
 * <p><b>La escritura queda fuera, y se dice</b>: {@code OrdenesDeCobroHttp} publica la orden de
 * cobro dentro de la transaccion que la registra, y lo que eso rompe es la atomicidad del camino
 * del dinero, no el pool. Va en su propio issue; esta prueba lo fija para que nadie lo lea como un
 * olvido.
 */
@DisplayName("#450 — a `caja` no se le pregunta con la conexion tomada")
class CajaSinLaConexionTomadaTest {

    private static final LocalDate DIA = LocalDate.of(2026, 9, 7);

    @Test
    @DisplayName("con una transaccion abierta la pregunta no sale, y el error nombra la ruta")
    void conUnaTransaccionAbiertaNoSale() throws IOException {
        AvanceDeCajaHttp avance = new AvanceDeCajaHttp(contra(unPuertoQueNadieEscucha()));

        enUnaTransaccion(
                () ->
                        assertThatThrownBy(() -> avance.delDia(DIA, DIA))
                                .as(
                                        "la guarda salta ANTES de ir a la red: sin ella, esto seria"
                                                + " un CajaInalcanzable despues de esperar")
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("#450")
                                .hasMessageContaining("caja")
                                .hasMessageContaining("/recaudacion/avance"));
    }

    @Test
    @DisplayName("sin transaccion la pregunta sale, y lo que contesta la red es lo de siempre")
    void sinTransaccionSale() throws IOException {
        AvanceDeCajaHttp avance = new AvanceDeCajaHttp(contra(unPuertoQueNadieEscucha()));

        assertThatThrownBy(() -> avance.delDia(DIA, DIA))
                .as("nadie escucha: el cliente fue a la red y no encontro a nadie")
                .isInstanceOf(AvanceDeCaja.CajaInalcanzable.class);
    }

    @Test
    @DisplayName("la escritura de la orden de cobro queda fuera de esta guarda (otro issue)")
    void laEscrituraQuedaFuera() throws IOException {
        ClienteHttpDeCaja caja = contra(unPuertoQueNadieEscucha());

        enUnaTransaccion(
                () ->
                        assertThatThrownBy(() -> caja.publicar("/ordenes", "{}", "emitir la orden"))
                                .as(
                                        "la orden se publica dentro de la transaccion que la"
                                                + " registra, y eso es la atomicidad del dinero, no"
                                                + " el pool: aqui se va a la red y no hay nadie")
                                .isInstanceOf(ClienteHttpDeCaja.CajaInalcanzable.class));
    }

    // ------------------------------------------------------------------

    private static ClienteHttpDeCaja contra(int puerto) {
        return new ClienteHttpDeCaja(
                new JsonMapper(), "http://127.0.0.1:" + puerto + "/caja/api/v1");
    }

    /** Lo que {@code TenantTransactionManager} deja en el hilo al abrir una transaccion. */
    private static void enUnaTransaccion(Runnable accion) {
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
