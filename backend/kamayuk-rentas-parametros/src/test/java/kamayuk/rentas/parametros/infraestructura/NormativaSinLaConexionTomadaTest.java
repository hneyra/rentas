package kamayuk.rentas.parametros.infraestructura;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.dominio.PublicadorDeNormativa;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * #450 — A {@code normativa} no se le pregunta con una conexion de la base tomada.
 *
 * <p>Hasta #450 {@code LectorDeParametrosCacheados.conjuntoVigenteEn} era {@code readOnly} y
 * preguntaba {@code /conjuntos} dentro de su transaccion; y la descarga de un conjunto nuevo abria
 * otra. La guarda va en {@code pedir}, el unico sitio del cliente que habla por la red: los dobles
 * de {@code normativa} implementan el puerto entero, asi que aqui no hay un escalon mas arriba que
 * ellos no sustituyan.
 */
@DisplayName("#450 — a `normativa` no se le pregunta con la conexion tomada")
class NormativaSinLaConexionTomadaTest {

    @Test
    @DisplayName("con una transaccion abierta la pregunta no sale, y el error nombra la ruta")
    void conUnaTransaccionAbiertaNoSale() throws IOException {
        PublicadorDeNormativa normativa = contra(unPuertoQueNadieEscucha());

        enUnaTransaccion(
                () ->
                        assertThatThrownBy(() -> normativa.conjuntoVigenteEn(new Ejercicio(2026)))
                                .as(
                                        "la guarda salta ANTES de ir a la red: sin ella, esto seria"
                                                + " un NormativaInalcanzable despues de esperar")
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("#450")
                                .hasMessageContaining("normativa")
                                .hasMessageContaining("/conjuntos?ejercicio=2026"));
    }

    @Test
    @DisplayName("y la descarga del snapshot pasa por la misma guarda")
    void laDescargaTambien() throws IOException {
        PublicadorDeNormativa normativa = contra(unPuertoQueNadieEscucha());

        enUnaTransaccion(
                () ->
                        assertThatThrownBy(() -> normativa.descargar(450L, "OBLIGACION"))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining("#450")
                                .hasMessageContaining("/conjuntos/450/snapshot"));
    }

    @Test
    @DisplayName("sin transaccion la pregunta sale, y lo que contesta la red es lo de siempre")
    void sinTransaccionSale() throws IOException {
        PublicadorDeNormativa normativa = contra(unPuertoQueNadieEscucha());

        assertThatThrownBy(() -> normativa.conjuntoVigenteEn(new Ejercicio(2026)))
                .as("nadie escucha: el cliente fue a la red y no encontro a nadie")
                .isInstanceOf(PublicadorDeNormativa.NormativaInalcanzable.class);
    }

    // ------------------------------------------------------------------

    private static PublicadorDeNormativa contra(int puerto) {
        return new ClienteHttpDeNormativa(
                new JsonMapper(), "http://127.0.0.1:" + puerto + "/normativa/api/v1");
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
