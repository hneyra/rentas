package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import kamayuk.comun.verificaciones.AsercionesQueNoPuedenFallarTestBase;
import kamayuk.comun.verificaciones.RevisorDeAserciones.Censo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #724: ninguna asercion de AssertJ compara un {@code Optional} con algo que no lo es.
 *
 * <p>Recorre {@code src/test} de todos los modulos de <b>este</b> repositorio; el escaner y su
 * muestra viven en {@code comun-verificaciones}.
 *
 * <p>Y añade la que es de {@code sgtm} y no de la libreria: la premisa que sostiene la decision de
 * #724 —que {@code llave} es ambiguo POR NOMBRE— afirmada contra el arbol de este repositorio.
 */
@DisplayName("#724 — Aserciones que no pueden fallar")
class AsercionesQueNoPuedenFallarTest extends AsercionesQueNoPuedenFallarTestBase {

    @Test
    @DisplayName("el censo separa los dos llave() que conviven hoy en kamayuk-rentas-licencias")
    void elCensoSeparaLosDosLlaveDeLicencias() throws IOException {
        // Lo que sostiene la decision de #724, medido contra el arbol real y no razonado: el
        // mismo nombre con dos tipos. Si alguien unificara los dos, esta prueba lo diria.
        //
        // Y ya dijo una vez: el ejemplo del lado NO-Optional era
        // `TablaDeValoresUnitarios.ValorUnitarioSinParametrizar`, que declaraba `llave()` como
        // String; #723 le hizo declarar `ParametroSinPublicar` y esta prueba se puso roja al
        // mezclar. La conclusion no cambio —`llave` sigue siendo ambiguo— y el ejemplo si, que es
        // exactamente para lo que sirve afirmarlo contra el arbol y no contra un comentario.
        Censo censo = censarDelDisco(fuentesJava(raizDelBackend()));

        assertThat(censo.nombresInequivocos())
                .as("`llave` es ambiguo por nombre; por eso hizo falta el censo por clase")
                .doesNotContain("llave");
        assertThat(censo.clasesConOptional("llave"))
                .as("las de la familia `ParametroSinPublicar` lo declaran Optional")
                .contains("ParametroAusente", "DerechoSinParametrizar")
                .as(
                        "y `ParametroQueFalta` —la proyeccion HTTP del mismo discriminador— lo"
                                + " lleva como componente String anulable, que es el otro lado")
                .doesNotContain("ParametroQueFalta");
    }
}
