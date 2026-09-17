package kamayuk.rentas.nucleo.dominio.predial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#234 — las dos formas del articulo 15, y ninguna mas")
class ModalidadDelPredialTest {

    @Test
    @DisplayName("se lee sin blancos y sin caja, que es como llega del cuerpo de la peticion")
    void seLeeSinBlancosYSinCaja() {
        assertThat(ModalidadDelPredial.de("  contado ")).isEqualTo(ModalidadDelPredial.CONTADO);
        assertThat(ModalidadDelPredial.de("TRIMESTRAL")).isEqualTo(ModalidadDelPredial.TRIMESTRAL);
    }

    @Test
    @DisplayName(
            "cualquier otra palabra se rechaza nombrando las dos, y ya no pasa por fraccionada")
    void cualquierOtraPalabraSeRechaza() {
        assertThatThrownBy(() -> ModalidadDelPredial.de("MENSUAL"))
                .isInstanceOf(ModalidadDelPredial.ModalidadDesconocida.class)
                .hasMessageContaining("MENSUAL")
                .hasMessageContaining("CONTADO, TRIMESTRAL");
    }

    @Test
    @DisplayName("y la que no viene no se supone: quien no la dice no la elige")
    void laQueNoVieneNoSeSupone() {
        assertThatThrownBy(() -> ModalidadDelPredial.de(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("#234");
    }

    /**
     * El vocabulario del enumerado es el del {@code CHECK} de V21, y se comprueba: si alguien anade
     * una modalidad aqui y no en la migracion, el {@code INSERT} falla en produccion y no en
     * ninguna prueba de dominio. Lo que esta lista guarda es el aviso.
     */
    @Test
    @DisplayName("son exactamente dos, las mismas que determinacion_modalidad_ck admite")
    void sonExactamenteLasDosDeLaMigracion() {
        assertThat(ModalidadDelPredial.values())
                .extracting(Enum::name)
                .containsExactly("CONTADO", "TRIMESTRAL");
        assertThat(ModalidadDelPredial.admitidas()).isEqualTo("CONTADO, TRIMESTRAL");
    }
}
