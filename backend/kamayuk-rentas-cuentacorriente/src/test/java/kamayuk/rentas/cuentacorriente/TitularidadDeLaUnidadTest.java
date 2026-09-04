package kamayuk.rentas.cuentacorriente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.TitularesDeLaUnidad.TitularDeLaUnidad;
import kamayuk.rentas.cuentacorriente.TitularesDeLaUnidad.TitularidadDeLaUnidad;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lo que el padron contesta sobre una unidad, sin base de datos y sin reloj (#680).
 *
 * <h2>Por que esta prueba existe</h2>
 *
 * <p>Hasta #680 el puerto contestaba una {@code List} y su vacio significaba <b>dos cosas</b> —«ese
 * identificador no apunta a nada» y «la unidad existe y no la reclama nadie»—, que se arreglan de
 * forma distinta y acababan en el mismo 422 con el mismo texto. La distincion vive ahora en el tipo
 * que el puerto devuelve, y lo que impide volver a confundirlas es el <b>invariante del
 * compacto</b>: una unidad que no esta en el padron no puede tener titulares.
 *
 * <p>No necesita PostgreSQL a proposito: si el invariante hiciera falta ejecutar una consulta para
 * comprobarse, no seria un invariante del tipo sino una costumbre de quien lo construye.
 */
@DisplayName("#680 — La respuesta del padron sobre la unidad de una obligacion")
class TitularidadDeLaUnidadTest {

    private static final TitularDeLaUnidad JUAN = new TitularDeLaUnidad(7L, "C-000007", "JUAN PEZ");

    @Test
    @DisplayName("una unidad que no esta en el padron no puede tener titulares")
    void laInexistenteNoPuedeTenerTitulares() {
        assertThatThrownBy(() -> new TitularidadDeLaUnidad(false, List.of(JUAN)))
                .as(
                        "es la respuesta incoherente que #680 existe para hacer imposible: quien la"
                                + " recibiera tendria que elegir a cual de las dos mitades creerle")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no esta en el padron no puede tener titulares");
    }

    @Test
    @DisplayName("y las tres respuestas posibles se distinguen entre si")
    void lasTresSeDistinguen() {
        TitularidadDeLaUnidad fuera = TitularidadDeLaUnidad.fueraDelPadron();
        TitularidadDeLaUnidad sinTitular = TitularidadDeLaUnidad.sinTitular();
        TitularidadDeLaUnidad deJuan = TitularidadDeLaUnidad.de(List.of(JUAN));

        assertThat(fuera.estaEnElPadron()).isFalse();
        assertThat(fuera.sinTitularVigente())
                .as(
                        "«no esta en el padron» NO es «esta y no la reclama nadie»: la primera se"
                                + " arregla tecleando el identificador que es, y la segunda no se"
                                + " arregla con nada porque es el padron diciendo la verdad")
                .isFalse();

        assertThat(sinTitular.estaEnElPadron()).isTrue();
        assertThat(sinTitular.sinTitularVigente()).isTrue();
        assertThat(sinTitular.titulares()).isEmpty();

        assertThat(deJuan.estaEnElPadron()).isTrue();
        assertThat(deJuan.sinTitularVigente()).isFalse();
        assertThat(deJuan.titulares()).containsExactly(JUAN);
    }

    @Test
    @DisplayName("esDe reconoce al titular y solo a el")
    void esDeReconoceAlTitular() {
        TitularidadDeLaUnidad deJuan = TitularidadDeLaUnidad.de(List.of(JUAN));

        assertThat(deJuan.esDe(7L)).isTrue();
        assertThat(deJuan.esDe(8L)).isFalse();
        assertThat(TitularidadDeLaUnidad.sinTitular().esDe(7L))
                .as("una unidad que no reclama nadie no es de nadie, ni siquiera de quien pregunta")
                .isFalse();
        assertThat(TitularidadDeLaUnidad.fueraDelPadron().esDe(7L)).isFalse();
    }

    @Test
    @DisplayName("la lista que entra se copia: quien la construyo no puede cambiarla despues")
    void laListaSeCopia() {
        List<TitularDeLaUnidad> mutable = new ArrayList<>();
        mutable.add(JUAN);

        TitularidadDeLaUnidad respuesta = TitularidadDeLaUnidad.de(mutable);
        mutable.clear();

        assertThat(respuesta.titulares())
                .as("la respuesta del padron no cambia porque cambie la lista que la origino")
                .containsExactly(JUAN);
    }
}
