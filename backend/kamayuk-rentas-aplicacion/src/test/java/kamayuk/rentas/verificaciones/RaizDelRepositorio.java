package kamayuk.rentas.verificaciones;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Donde esta el repositorio, para las pruebas que leen fuera del build de Gradle.
 *
 * <p>Existe porque ya lo buscaban dos pruebas por su cuenta —el contrato y las formas— y ahora son
 * tres: tres recorridos escritos por separado empiezan iguales y acaban discrepando en el caso
 * raro, y entonces una prueba lee un archivo y otra lee otro.
 */
final class RaizDelRepositorio {

    private RaizDelRepositorio() {}

    static Path ruta() {
        Path actual = Path.of("").toAbsolutePath();
        while (actual != null) {
            if (Files.exists(actual.resolve("docs/50-api/openapi/rentas-v1.yaml"))) {
                return actual;
            }
            actual = actual.getParent();
        }
        throw new IllegalStateException("No se encontro la raiz del repositorio");
    }
}
