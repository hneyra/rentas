package kamayuk.rentas.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code TipoTransferencia} en dominio puro: sin base, sin Spring (#542). */
@DisplayName("#542 — El vocabulario cerrado del tipo de transferencia")
class TipoTransferenciaTest {

    @Test
    @DisplayName("son los nueve que dibujan los dos desplegables «Tipo de acto» del manual")
    void sonLosNueveDelManual() {
        assertThat(TipoTransferencia.values())
                .as(
                        "siete de «Transferencia de predio» y dos mas de «Transferencia de"
                                + " vehiculo»: REMATE y HERENCIA, que la primera no dibuja")
                .containsExactly(
                        TipoTransferencia.COMPRA_VENTA,
                        TipoTransferencia.DONACION,
                        TipoTransferencia.PERMUTA,
                        TipoTransferencia.ANTICIPO_DE_LEGITIMA,
                        TipoTransferencia.ADJUDICACION,
                        TipoTransferencia.DACION_EN_PAGO,
                        TipoTransferencia.SUCESION,
                        TipoTransferencia.REMATE,
                        TipoTransferencia.HERENCIA);
    }

    @Test
    @DisplayName("un valor desconocido lanza nombrando el valor, y sin nombrar nada del esquema")
    void unValorDesconocidoLanzaNombrandoElValor() {
        assertThatThrownBy(() -> TipoTransferencia.de("XXXX"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tipo de transferencia desconocido: 'XXXX'");
    }

    @Test
    @DisplayName("«COMPRAVENTA» sin guion bajo es desconocido: es el caso realista del issue")
    void laCompraventaSinGuionEsDesconocida() {
        assertThatThrownBy(() -> TipoTransferencia.de("COMPRAVENTA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'COMPRAVENTA'");
    }

    @Test
    @DisplayName("normaliza la caja y los espacios, y nada mas")
    void normalizaLaCajaYLosEspacios() {
        assertThat(TipoTransferencia.de("  dacion_en_pago "))
                .isEqualTo(TipoTransferencia.DACION_EN_PAGO);
    }

    @Test
    @DisplayName("no es una lectura tolerante: ni la tilde ni el guion del rotulo del manual")
    void noEsUnaLecturaTolerante() {
        assertThatThrownBy(() -> TipoTransferencia.de("DONACIÓN"))
                .as("quitar tildes aqui convertiria cualquier texto parecido en un valor valido")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TipoTransferencia.de("COMPRA-VENTA"))
                .as("el rotulo del catalogo lo traduce la interfaz con una tabla, no este metodo")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TipoTransferencia.de("ANTICIPO DE LEGITIMA"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ningun nombre pasa de los 40 caracteres de la columna")
    void ningunNombrePasaDeLaColumna() {
        for (TipoTransferencia tipo : TipoTransferencia.values()) {
            assertThat(tipo.name().length())
                    .as("transferencia.tipo_transferencia es varchar(40) desde V2: %s", tipo)
                    .isLessThanOrEqualTo(40);
        }
    }
}
