package kamayuk.rentas.catastro.infraestructura;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import kamayuk.rentas.dominio.MotivoDeInalcanzable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * #25 AC-4 — lo mismo para {@code catastro}: falta la variable, o el vecino no contesta.
 *
 * <p>La rama de la URL vacia de {@link ClienteHttpDeCatastro#enviar} <b>no la ejercitaba ninguna
 * prueba</b> hasta #25, y es facil ver por que: el doble {@code CatastroQueNoContesta} llama a su
 * {@code super} con {@code "http://catastro.invalido"}, o sea una URL no vacia. Un host que no
 * resuelve y una variable de entorno que falta son dos defectos distintos con dos remedios
 * distintos, y el segundo no lo cubria nadie.
 */
@DisplayName("#25 — `catastro`: la ausencia se distingue de la averia")
class CatastroSinConfigurarTest {

    private static final String QUE = "leer el predio 11";

    @Test
    @DisplayName("sin `kamayuk.catastro.url` el motivo es SIN_CONFIGURAR")
    void sinConfigurar() {
        ClienteHttpDeCatastro sinUrl = new ClienteHttpDeCatastro(new JsonMapper(), "");

        assertThatThrownBy(() -> sinUrl.enviar("/predios/11", QUE))
                .isInstanceOf(ClienteHttpDeCatastro.CatastroInalcanzable.class)
                .extracting(fallo -> ((ClienteHttpDeCatastro.CatastroInalcanzable) fallo).motivo())
                .isEqualTo(MotivoDeInalcanzable.SIN_CONFIGURAR);
    }

    @Test
    @DisplayName("con la URL puesta y nadie escuchando, NO_CONTESTA")
    void noContesta() throws IOException {
        ClienteHttpDeCatastro caido =
                new ClienteHttpDeCatastro(
                        new JsonMapper(),
                        "http://127.0.0.1:" + unPuertoQueNadieEscucha() + "/catastro/api/v1");

        assertThatThrownBy(() -> caido.enviar("/predios/11", QUE))
                .isInstanceOf(ClienteHttpDeCatastro.CatastroInalcanzable.class)
                .extracting(fallo -> ((ClienteHttpDeCatastro.CatastroInalcanzable) fallo).motivo())
                .isEqualTo(MotivoDeInalcanzable.NO_CONTESTA);
    }

    private static int unPuertoQueNadieEscucha() throws IOException {
        try (ServerSocket reservado = new ServerSocket(0)) {
            return reservado.getLocalPort();
        }
    }
}
