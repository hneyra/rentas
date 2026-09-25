package kamayuk.rentas.autorizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #429 — la forma del cliente de servicio, en una sola copia: la leen el guardia y el consumidor
 * del buzon de {@code identidad}.
 */
@DisplayName("#429 — El cliente de servicio leido del azp")
class ClienteDeServicioTest {

    @Test
    @DisplayName("el de la caja: sistema y ubigeo, y vuelve a componer el mismo azp")
    void elDeLaCaja() {
        ClienteDeServicio cliente = ClienteDeServicio.desdeAzp("kamayuk-caja-servicio-200601");

        assertThat(cliente.sistema()).isEqualTo("caja");
        assertThat(cliente.ubigeo()).isEqualTo("200601");
        assertThat(cliente.esDe("caja")).isTrue();
        assertThat(cliente.esDe("rentas")).isFalse();
        assertThat(cliente.azp()).isEqualTo("kamayuk-caja-servicio-200601");
    }

    @Test
    @DisplayName(
            "el de otro sistema tiene la forma: se lee, y quien lo usa decide que no es el suyo")
    void elDeOtroSistemaSeLee() {
        assertThat(ClienteDeServicio.desdeAzp("kamayuk-catastro-servicio-200601").sistema())
                .isEqualTo("catastro");
    }

    @Test
    @DisplayName("el cliente de la interfaz no es un cliente de servicio")
    void elDeLaInterfazNo() {
        assertThatThrownBy(() -> ClienteDeServicio.desdeAzp("kamayuk-backoffice"))
                .isInstanceOf(ClienteDeServicio.NoEsUnClienteDeServicio.class)
                .hasMessageContaining("kamayuk-backoffice")
                .hasMessageContaining(ClienteDeServicio.FORMA_LEGIBLE);
    }

    @Test
    @DisplayName("ni un ubigeo que no son seis digitos, ni un azp que falta")
    void niUbigeoMalNiAzpAusente() {
        assertThatThrownBy(() -> ClienteDeServicio.desdeAzp("kamayuk-caja-servicio-2006"))
                .isInstanceOf(ClienteDeServicio.NoEsUnClienteDeServicio.class);
        assertThatThrownBy(() -> ClienteDeServicio.desdeAzp("kamayuk-caja-servicio-200601-x"))
                .isInstanceOf(ClienteDeServicio.NoEsUnClienteDeServicio.class);
        assertThatThrownBy(() -> ClienteDeServicio.desdeAzp(null))
                .isInstanceOf(ClienteDeServicio.NoEsUnClienteDeServicio.class)
                .hasMessageContaining("no trae `azp`");
        assertThatThrownBy(() -> ClienteDeServicio.desdeAzp("  "))
                .isInstanceOf(ClienteDeServicio.NoEsUnClienteDeServicio.class);
    }
}
