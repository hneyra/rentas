package kamayuk.rentas.valores.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #37 — sin base de datos: los tres tipos de valor y su resolucion por codigo. */
@DisplayName("#37 — TipoValor")
class TipoValorTest {

    @Test
    @DisplayName("los tres codigos son OP, RD y RM")
    void losCodigosSonLosTres() {
        assertThat(TipoValor.ORDEN_DE_PAGO.codigo()).isEqualTo("OP");
        assertThat(TipoValor.RESOLUCION_DE_DETERMINACION.codigo()).isEqualTo("RD");
        assertThat(TipoValor.RESOLUCION_DE_MULTA.codigo()).isEqualTo("RM");
    }

    @Test
    @DisplayName("porCodigo resuelve sin distinguir mayusculas")
    void porCodigoResuelveSinDistinguirMayusculas() {
        assertThat(TipoValor.porCodigo("op")).isEqualTo(TipoValor.ORDEN_DE_PAGO);
        assertThat(TipoValor.porCodigo("RD")).isEqualTo(TipoValor.RESOLUCION_DE_DETERMINACION);
    }

    @Test
    @DisplayName("un codigo desconocido falla, no se aproxima al mas parecido")
    void codigoDesconocidoFalla() {
        assertThatThrownBy(() -> TipoValor.porCodigo("XX"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cada tipo trae su base legal, y ninguna esta vacia")
    void cadaTipoTraeSuBaseLegal() {
        for (TipoValor tipo : TipoValor.values()) {
            assertThat(tipo.baseLegal()).isNotBlank();
        }
    }
}
