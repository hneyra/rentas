package kamayuk.rentas.tesoreria.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDate;
import kamayuk.rentas.dominio.MotivoDeInalcanzable;
import kamayuk.rentas.tesoreria.AvanceDeCaja;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * #25 AC-4 — que falte la variable y que la caja se caiga son dos cosas, y se pueden separar.
 *
 * <p>Hasta #25 las dos producian el <b>mismo</b> {@code CajaInalcanzable} y lo unico que las
 * distinguia era un trozo de frase en castellano dentro del mensaje —{@code "no esta
 * configurada"}—. Decidir analizando ese texto es exactamente lo que este repositorio ya declaro
 * prohibido cuando escribio {@code NoConstaEnCatastro.codigo()}: «un texto en castellano se
 * reescribe en cuanto alguien lo lee en voz alta».
 *
 * <p>Se arreglan distinto y por eso hay que poder separarlas: una es un despliegue al que le falta
 * {@code KAMAYUK_CAJA_URL} —no se cura sola— y la otra un vecino caido —se cura sola—. Colapsarlas
 * manda a mirar un contenedor sano cuando lo que falta es configuracion.
 *
 * <p><b>Ninguna prueba de este repositorio ejercitaba la rama de la URL vacia</b> antes de #25: el
 * doble de {@code catastro} pasa {@code "http://catastro.invalido"} a su constructor, o sea una URL
 * no vacia, y de {@code ClienteHttpDeCaja} no habia ninguna prueba.
 */
@DisplayName("#25 — `caja`: la ausencia se distingue de la averia")
class CajaSinConfigurarTest {

    private static final LocalDate DIA = LocalDate.of(2026, 9, 7);

    private static AvanceDeCajaHttp avanceCon(String raiz) {
        return new AvanceDeCajaHttp(new ClienteHttpDeCaja(new JsonMapper(), raiz));
    }

    @Test
    @DisplayName("sin `kamayuk.caja.url` el motivo es SIN_CONFIGURAR, y no se llega a la red")
    void sinConfigurar() {
        assertThatThrownBy(() -> avanceCon("").delDia(DIA, DIA))
                .isInstanceOf(AvanceDeCaja.CajaInalcanzable.class)
                .extracting(fallo -> ((AvanceDeCaja.CajaInalcanzable) fallo).motivo())
                .isEqualTo(MotivoDeInalcanzable.SIN_CONFIGURAR);
    }

    @Test
    @DisplayName("con la URL puesta y nadie escuchando, NO_CONTESTA")
    void noContesta() throws IOException {
        String muerto = "http://127.0.0.1:" + unPuertoQueNadieEscucha() + "/caja/api/v1";

        assertThatThrownBy(() -> avanceCon(muerto).delDia(DIA, DIA))
                .isInstanceOf(AvanceDeCaja.CajaInalcanzable.class)
                .extracting(fallo -> ((AvanceDeCaja.CajaInalcanzable) fallo).motivo())
                .isEqualTo(MotivoDeInalcanzable.NO_CONTESTA);
    }

    @Test
    @DisplayName("y las dos salen por el PUERTO, no por el tipo del transporte")
    void salenPorElPuerto() throws IOException {
        // Es lo que permite que `indicadores` las cace sin conocer `tesoreria.infraestructura`,
        // que es un subpaquete interno de otro modulo (frontera de Modulith). Antes de #25 el
        // panel habria tenido que cazar `ClienteHttpDeCaja.CajaInalcanzable`.
        String muerto = "http://127.0.0.1:" + unPuertoQueNadieEscucha() + "/caja/api/v1";

        assertThat(errorDe(() -> avanceCon("").delDia(DIA, DIA)))
                .isNotInstanceOf(ClienteHttpDeCaja.CajaInalcanzable.class)
                .isInstanceOf(AvanceDeCaja.CajaInalcanzable.class);
        assertThat(errorDe(() -> avanceCon(muerto).delDia(DIA, DIA)))
                .isNotInstanceOf(ClienteHttpDeCaja.CajaInalcanzable.class)
                .isInstanceOf(AvanceDeCaja.CajaInalcanzable.class);
    }

    /** La causa original se conserva: el registro sigue pudiendo decir que paso de verdad. */
    @Test
    @DisplayName("la excepcion del transporte viaja como causa, no se pierde")
    void laCausaSeConserva() {
        Throwable fallo = errorDe(() -> avanceCon("").delDia(DIA, DIA));

        assertThat(fallo.getCause()).isInstanceOf(ClienteHttpDeCaja.CajaInalcanzable.class);
        assertThat(fallo).hasMessageContaining("kamayuk.caja.url no esta configurada");
    }

    private static Throwable errorDe(Runnable accion) {
        try {
            accion.run();
        } catch (RuntimeException fallo) {
            return fallo;
        }
        throw new AssertionError("Se esperaba un fallo y no lo hubo");
    }

    private static int unPuertoQueNadieEscucha() throws IOException {
        try (ServerSocket reservado = new ServerSocket(0)) {
            return reservado.getLocalPort();
        }
    }
}
