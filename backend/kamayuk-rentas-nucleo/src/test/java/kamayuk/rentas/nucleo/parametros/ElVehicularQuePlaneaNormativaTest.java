package kamayuk.rentas.nucleo.parametros;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Que el vehicular planeado de {@link ElVehicularQuePlaneaNormativa} es el que dice el corpus de
 * {@code normativa}, y no uno escrito aqui (#499).
 *
 * <p>La muestra existe para sembrar las pruebas del vehicular con el nombre y la cifra que {@code
 * normativa} va a publicar. Si los escribiera este repositorio sin compararlos, volveria el defecto
 * de #376 por el otro lado: una prueba verde sembrada con lo que el codigo espera. Aqui se leen del
 * archivo del corpus —el mismo repositorio hermano que {@link DerivadoPublicado} ya exige clonado—:
 * el tipo, de la fila «Tipo» de su §2; la cifra, del fragmento verbatim del art. 33 en su §1.1.
 */
@DisplayName("#499 — El vehicular planeado, contra el corpus de normativa")
class ElVehicularQuePlaneaNormativaTest {

    @Test
    @DisplayName("§2 del corpus planea cada tipo con este nombre")
    void elCorpusPlaneaCadaTipoConEsteNombre() throws IOException {
        String filaDelTipo =
                filaQueEmpiezaPor("| Tipo |", "## 2.")
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "el §2 de "
                                                        + ElVehicularQuePlaneaNormativa.ARCHIVO
                                                        + " no tiene la fila «| Tipo |»"));

        for (String tipo : ElVehicularQuePlaneaNormativa.FILAS.keySet()) {
            assertThat(filaDelTipo)
                    .as(
                            "normativa planea el vehicular con los tipos de esta fila, y la muestra"
                                    + " siembra %s: si el corpus lo renombro, renombra la muestra y"
                                    + " la llave de LlavesDelConjunto",
                            tipo)
                    .contains("`" + tipo + "`");
        }
    }

    @Test
    @DisplayName("cada cifra es la que imprime el art. 33, sin convertir")
    void cadaCifraEsLaQueImprimeElArticulo33() throws IOException {
        String articulo33 =
                filaQueEmpiezaPor("| Tasa | 33 |", "## 1.")
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "el §1 de "
                                                        + ElVehicularQuePlaneaNormativa.ARCHIVO
                                                        + " no tiene la fila del art. 33"));

        for (Map.Entry<String, ElVehicularQuePlaneaNormativa.Fila> planeada :
                ElVehicularQuePlaneaNormativa.FILAS.entrySet()) {
            ElVehicularQuePlaneaNormativa.Fila fila = planeada.getValue();
            assertThat(articulo33)
                    .as(
                            "el fragmento de %s esta, letra por letra, en el art. 33",
                            planeada.getKey())
                    .contains(fila.fragmento());
            assertThat(fila.fragmento())
                    .as(
                            "verificar-publicacion.mjs exige que la cifra este en el texto: %s se"
                                    + " publicara como el numero que la norma imprime delante del %%",
                            planeada.getKey())
                    .startsWith(fila.cifra() + "%");
        }
    }

    @Test
    @DisplayName("y el derivado todavia no las publica: el dia que lo haga, esta muestra sobra")
    void elDerivadoTodaviaNoLasPublica() {
        Map<String, String> publicados = DerivadoPublicado.numerosVigentesEn(2026);

        assertThat(
                        ElVehicularQuePlaneaNormativa.FILAS.keySet().stream()
                                .filter(
                                        tipo ->
                                                publicados.keySet().stream()
                                                        .anyMatch(
                                                                llave ->
                                                                        llave.startsWith(
                                                                                tipo + "|")))
                                .toList())
                .as(
                        "el derivado ya publica el vehicular: siembra con"
                                + " DerivadoPublicado.conjuntoDelEjercicio, retira esta muestra y"
                                + " las dos llaves de SIN_PUBLICAR")
                .isEmpty();
    }

    /** La primera fila de la tabla que empieza por {@code inicio}, despues del titulo dado. */
    private static Optional<String> filaQueEmpiezaPor(String inicio, String titulo)
            throws IOException {
        List<String> lineas =
                Files.readAllLines(ElVehicularQuePlaneaNormativa.ARCHIVO, StandardCharsets.UTF_8);
        boolean dentro = false;
        for (String linea : lineas) {
            if (linea.startsWith(titulo)) {
                dentro = true;
            } else if (dentro && linea.startsWith(inicio)) {
                return Optional.of(linea);
            }
        }
        return Optional.empty();
    }
}
