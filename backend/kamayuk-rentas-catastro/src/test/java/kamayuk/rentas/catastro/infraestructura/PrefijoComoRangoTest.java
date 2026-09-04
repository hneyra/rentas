package kamayuk.rentas.catastro.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El limite superior con que la consulta de fichas convierte un prefijo en un rango.
 *
 * <p>Existe porque el plan de ejecucion no cambia el resultado: si alguien devuelve la consulta a
 * {@code LIKE} —«se lee mejor»—, las filas siguen saliendo bien y ninguna prueba funcional se pone
 * roja. Lo unico que cambia es que deja de usarse el indice, y con el padron de una provincia eso
 * es la diferencia entre una pantalla y un tiempo de espera. Ver DAT-01 §0, hallazgo 3.
 */
@DisplayName("DAT-01 §0 — El prefijo se busca como rango, no con LIKE")
class PrefijoComoRangoTest {

    @Test
    @DisplayName("el limite superior es el prefijo con el ultimo caracter incrementado")
    void elLimiteSuperiorEsElPrefijoIncrementado() {
        assertThat(FichaCatastralRepositoryJdbc.siguienteAlPrefijo("2501010010"))
                .as("«2501010010%» son exactamente los codigos entre 2501010010 y 2501010011")
                .isEqualTo("2501010011");
        assertThat(FichaCatastralRepositoryJdbc.siguienteAlPrefijo("MZ-A")).isEqualTo("MZ-B");
    }

    @Test
    @DisplayName("con un prefijo que no es ASCII imprimible se rinde, y eso es lo correcto")
    void conPrefijoNoAsciiSeRinde() {
        assertThat(FichaCatastralRepositoryJdbc.siguienteAlPrefijo("Ñ100"))
                .as(
                        "incrementar el ultimo caracter en UTF-16 no equivale a incrementarlo en"
                                + " bytes, y una comparacion por bytes con un limite calculado en"
                                + " caracteres dejaria filas fuera. Mejor un LIKE lento que un"
                                + " resultado incompleto")
                .isNull();
        assertThat(FichaCatastralRepositoryJdbc.siguienteAlPrefijo("")).isNull();
    }
}
