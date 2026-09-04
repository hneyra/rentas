package kamayuk.rentas.compartido;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.MunicipalidadId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Contexto de tenant")
class TenantContextTest {

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName("sin contexto, actual() falla en lugar de devolver un valor por omision")
    void sinContextoFalla() {
        assertThatThrownBy(TenantContext::actual)
                .as("un error ruidoso es preferible a una fuga silenciosa (RNF-032)")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No hay contexto de municipalidad");
    }

    @Test
    @DisplayName("sin contexto, actualSiHay() esta vacio y no lanza")
    void sinContextoActualSiHayVacio() {
        assertThat(TenantContext.actualSiHay()).isEmpty();
    }

    @Test
    @DisplayName("lo fijado es lo que se lee")
    void fijarYLeer() {
        TenantContext.fijar(new MunicipalidadId(41));
        assertThat(TenantContext.actual().valor()).isEqualTo(41);
    }

    @Test
    @DisplayName("limpiar deja el hilo sin contexto")
    void limpiarDejaSinContexto() {
        TenantContext.fijar(new MunicipalidadId(41));
        TenantContext.limpiar();
        assertThat(TenantContext.actualSiHay()).isEmpty();
    }

    @Test
    @DisplayName("el contexto no se propaga a otro hilo")
    void noSePropagaAOtroHilo() throws InterruptedException {
        TenantContext.fijar(new MunicipalidadId(41));

        // Un hilo del pool que arrastrara contexto ajeno seria una fuga. Esta
        // prueba fija la garantia; si algun dia se cambia ThreadLocal por
        // ScopedValue heredable, tiene que seguir cumpliendose.
        boolean[] hayContexto = {true};
        Thread otro = new Thread(() -> hayContexto[0] = TenantContext.actualSiHay().isPresent());
        otro.start();
        otro.join();

        assertThat(hayContexto[0]).isFalse();
    }

    @Test
    @DisplayName("un identificador no positivo se rechaza al construirlo")
    void identificadorNoPositivoSeRechaza() {
        assertThatThrownBy(() -> new MunicipalidadId(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MunicipalidadId(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
