package kamayuk.rentas.verificaciones;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * El arbol que recorren las guardas, <b>sin entrar</b> en lo que genera el build (#474).
 *
 * <p>Hasta #474 cada guarda hacia su {@code Files.walk} y descartaba {@code /build/} con un filtro
 * sobre cada ruta, o sea <b>despues</b> de haber entrado. Con {@code org.gradle.parallel=true} otro
 * modulo borra y regenera sus informes mientras tanto, y el recorrido revento con {@code
 * NoSuchFileException: …/build/reports/tests/test/index.html}: un rojo intermitente que no dice
 * nada del codigo. Y las que no filtraban contaban ademas la copia del fuente que Spotless deja en
 * {@code build/spotless-clean}.
 *
 * <p>Aqui los directorios de {@link #NO_SE_ENTRA} se saltan enteros ({@code SKIP_SUBTREE}), y un
 * archivo que desaparece entre listarlo y visitarlo se ignora: ya no esta, asi que no es fuente.
 */
final class ArbolDeFuentes {

    /** Salidas de build y dependencias: no son fuente de nadie. */
    static final Set<String> NO_SE_ENTRA = Set.of("build", ".gradle", "node_modules", ".git");

    private ArbolDeFuentes() {}

    /** Los archivos del arbol, sin pasar por {@link #NO_SE_ENTRA}. */
    static List<Path> archivos(Path raiz) {
        return recorrer(raiz, NO_SE_ENTRA).archivos();
    }

    /** Los archivos del arbol, sin pasar por ninguno de los directorios {@code fuera}. */
    static List<Path> archivos(Path raiz, Set<String> fuera) {
        return recorrer(raiz, fuera).archivos();
    }

    /**
     * El recorrido entero: los archivos y los directorios en los que entro. Lo segundo existe para
     * que una prueba pueda afirmar que no entro donde no debia, que es lo que se rompio.
     */
    static Recorrido recorrer(Path raiz, Set<String> fuera) {
        List<Path> archivos = new ArrayList<>();
        List<Path> directorios = new ArrayList<>();
        try {
            Files.walkFileTree(
                    raiz,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path directorio, BasicFileAttributes atributos) {
                            Path nombre = directorio.getFileName();
                            if (!directorio.equals(raiz)
                                    && nombre != null
                                    && fuera.contains(nombre.toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            directorios.add(directorio);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(
                                Path archivo, BasicFileAttributes atributos) {
                            if (atributos.isRegularFile()) {
                                archivos.add(archivo);
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path archivo, IOException fallo)
                                throws IOException {
                            if (fallo instanceof NoSuchFileException) {
                                return FileVisitResult.CONTINUE;
                            }
                            throw fallo;
                        }
                    });
        } catch (IOException fallo) {
            throw new UncheckedIOException(fallo);
        }
        return new Recorrido(List.copyOf(archivos), List.copyOf(directorios));
    }

    /** Lo que el recorrido encontro y por donde paso. */
    record Recorrido(List<Path> archivos, List<Path> directorios) {}
}
