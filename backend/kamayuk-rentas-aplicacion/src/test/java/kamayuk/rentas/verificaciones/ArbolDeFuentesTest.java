package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #474 — Las guardas recorren el arbol sin entrar en {@code build/}.
 *
 * <p>El defecto no se reproduce sin una carrera —otro modulo borrando sus informes mientras la
 * guarda recorre—, asi que se afirma su causa: que el recorrido no <b>entra</b> en ningun
 * directorio de build. Un filtro sobre las rutas encontradas daria los mismos archivos y seguiria
 * entrando, que es justo lo que reventaba.
 */
@DisplayName("#474 — El arbol de fuentes no entra en build/")
class ArbolDeFuentesTest {

    @Test
    @DisplayName("un arbol con build/ y node_modules/: ni los visita ni devuelve lo que tienen")
    void noEntraEnLoQueGeneraElBuild(@TempDir Path raiz) throws IOException {
        Path fuente = escribir(raiz.resolve("modulo/src/main/java/kamayuk/A.java"));
        escribir(raiz.resolve("modulo/build/spotless-clean/src/main/java/kamayuk/A.java"));
        escribir(raiz.resolve("modulo/build/reports/tests/test/index.html"));
        escribir(raiz.resolve("frontend/node_modules/paquete/index.js"));

        ArbolDeFuentes.Recorrido recorrido =
                ArbolDeFuentes.recorrer(raiz, ArbolDeFuentes.NO_SE_ENTRA);

        assertThat(recorrido.archivos()).containsExactly(fuente);
        assertThat(recorrido.directorios())
                .as("el recorrido no ENTRA en build/ ni en node_modules/: filtrar despues no basta")
                .noneMatch(directorio -> contiene(directorio, "build"))
                .noneMatch(directorio -> contiene(directorio, "node_modules"));
    }

    @Test
    @DisplayName("sobre el backend de verdad, ningun directorio visitado esta bajo build/")
    void elBackendSeRecorreSinPasarPorBuild() {
        Path backend = RaizDelRepositorio.ruta().resolve("backend");
        assertThat(backend.resolve("kamayuk-rentas-aplicacion/build"))
                .as("esta prueba corre desde el build, asi que build/ existe y hay donde no entrar")
                .isDirectory();

        ArbolDeFuentes.Recorrido recorrido =
                ArbolDeFuentes.recorrer(backend, ArbolDeFuentes.NO_SE_ENTRA);

        assertThat(recorrido.directorios())
                .filteredOn(directorio -> contiene(backend.relativize(directorio), "build"))
                .as("directorios visitados bajo build/")
                .isEmpty();
        assertThat(recorrido.archivos())
                .as("y el recorrido si encuentra el fuente")
                .anyMatch(archivo -> archivo.endsWith("ArbolDeFuentes.java"));
    }

    /**
     * Y ninguna prueba del backend vuelve a recorrer con {@code Files.walk}.
     *
     * <p>No es una mania de estilo: {@code Files.walk} no deja podar, asi que quien lo usa sobre
     * una raiz que contiene {@code build/} ENTRA y filtra despues, que es #474. En #474 eran nueve
     * recorridos —ocho de este modulo y uno de {@code kamayuk-rentas-esquema}— con la misma forma;
     * los que partian de {@code src/main} no se asomaban a nada, pero la siguiente guarda copia la
     * forma de cualquiera.
     */
    @Test
    @DisplayName("ninguna prueba del backend recorre el arbol con Files.walk")
    void ningunaPruebaRecorreConFilesWalk() throws IOException {
        Path backend = RaizDelRepositorio.ruta().resolve("backend");
        // Compuesto a trozos para que este archivo no se encuentre a si mismo.
        String llamada = "Files" + ".walk(";
        List<String> quienes = new ArrayList<>();
        for (Path ruta : ArbolDeFuentes.archivos(backend)) {
            String texto = ruta.toString();
            if (texto.endsWith(".java")
                    && texto.contains("/src/test/java/")
                    && Files.readString(ruta, StandardCharsets.UTF_8).contains(llamada)) {
                quienes.add(backend.relativize(ruta).toString());
            }
        }

        assertThat(quienes)
                .as(
                        "%s no poda: entra en build/ y filtra despues, y otro modulo que borre sus"
                                + " informes a la vez lo tumba (#474). Recorre con ArbolDeFuentes",
                        llamada)
                .isEmpty();
    }

    private static boolean contiene(Path ruta, String parte) {
        for (Path segmento : ruta) {
            if (segmento.toString().equals(parte)) {
                return true;
            }
        }
        return false;
    }

    private static Path escribir(Path archivo) throws IOException {
        Files.createDirectories(archivo.getParent());
        return Files.writeString(archivo, "x");
    }
}
