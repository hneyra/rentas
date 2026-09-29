package kamayuk.rentas.verificaciones;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

/**
 * La API publica de los modulos lleva javadoc (#642).
 *
 * <h2>Que exige</h2>
 *
 * <p>En {@code src/main} de los diecisiete modulos, y solo en lo que javadoc publica —lo publico y
 * lo protegido, dentro de tipos que tambien lo son—, llevan comentario de documentacion:
 *
 * <ol>
 *   <li><b>todo tipo</b>: clase, interfaz, record, enum o anotacion, anidado o no;
 *   <li><b>todo metodo publico de un caso de uso</b>: un {@code @Service} de un paquete {@code
 *       ..aplicacion..}, la misma definicion que {@link CasosDeUsoSinLlamadorTest};
 *   <li><b>todo metodo de una interfaz</b>: los puertos de {@code ..dominio..}, los que el paquete
 *       raiz de cada modulo publica y los de {@code plataforma}. Una interfaz es un contrato entre
 *       quien la implementa y quien la llama, que no se ven, y el javadoc es lo unico que los une.
 * </ol>
 *
 * <p>Un metodo que sobrescribe a otro no lo necesita: javadoc hereda el comentario del que
 * sobrescribe, y doclint tampoco lo pide. Se resuelve con los tipos y no con {@code @Override}, que
 * es opcional. Un comentario vacio cuenta como ninguno.
 *
 * <h2>Lo que queda fuera, medido</h2>
 *
 * <p>El 2026-09-29 doclint con {@code missing} daba <b>7 601</b> avisos en {@code src/main}, y no
 * los 1 448 de {@code ./gradlew javadoc}: esa cifra era la de catorce modulos parados en el tope de
 * cien avisos de la herramienta. De los 7 601, 1 525 eran comentarios que faltan y 6 032 etiquetas
 * {@code @param}, {@code @return} y {@code @throws} de comentarios que ya existen. De los 1 525, en
 * el alcance faltaban <b>102</b> —12 de 1 848 tipos, 22 de 279 metodos de caso de uso y 68 de 482
 * metodos de interfaz— y fuera quedan 1 423, por su motivo: el constructor de inyeccion de un
 * {@code @Service} (183; la costumbre de la casa es no documentarlo, 1 de 184 lo esta, porque no
 * dice nada que la clase no diga), los records y las clases de {@code ..dominio..} (562), el borde
 * web (243) y el resto de {@code infraestructura}, de {@code plataforma} y de las clases de {@code
 * aplicacion} que no son casos de uso (435). Exigirlos seria escribir javadoc por escribirlo.
 *
 * <h2>Por que no es {@code -Xdoclint:missing}, que era lo que #642 proponia</h2>
 *
 * <p>Tres motivos, los tres probados con {@code javac} antes de descartarlo:
 *
 * <ul>
 *   <li>{@code missing} no es «falta el comentario»: trae en el mismo grupo las etiquetas. Dentro
 *       de este mismo alcance habria pedido unas <b>3 500</b> —1 898 {@code @param} de componentes
 *       de record en el javadoc de su tipo, 863 {@code @param} y {@code @return} en los metodos de
 *       interfaz y 734 en los casos de uso—, sobre comentarios que ya dicen en prosa lo que cada
 *       argumento es.
 *   <li>La unica forma de acotarlo, {@code -Xdoclint/package:}, acota <b>todo</b> doclint: fuera de
 *       esos paquetes se apagaria lo que #629 encendio —un {@code {@link}} roto o un encabezado
 *       fuera de orden volverian a compilar—.
 *   <li>Lo que falta son <b>avisos</b>, no errores: la compilacion solo se para con {@code
 *       -Werror}, que haria fatal tambien cada aviso de {@code -Xlint:all}.
 * </ul>
 *
 * <p>Asi que es una guarda de {@code verificarArquitectura}, bloqueante en la CI como las demas.
 * Lee las fuentes con el mismo {@code javac} —{@code -proc:only}, con este censo como procesador—,
 * y pregunta por el javadoc a {@code Elements.getDocComment}, que es lo que doclint y javadoc leen:
 * medido sobre {@code 9964a08}, cuenta los mismos 1 848 tipos, 279 metodos de caso de uso y 482 de
 * interfaz que un recorrido aparte del arbol sintactico, y acusa las mismas 102 piezas que doclint
 * con {@code missing}, una a una. No usa {@code com.sun.source}, que Checkstyle prohibe con todo
 * {@code com.sun}.
 *
 * <p>Vive aqui y no en {@code comun-verificaciones} porque el alcance —que es un caso de uso y que
 * es un puerto— es de este repositorio; por eso no la alcanza {@code
 * ReglasDeArquitecturaMuerdenTest}, y que muerde lo demuestra {@link #laReglaMuerdeSobreSuMuestra}.
 */
@DisplayName("#642 — ningun tipo publico, caso de uso ni metodo de interfaz se queda sin javadoc")
class ApiPublicaSinJavadocTest {

    private static final Path BACKEND = RaizDelRepositorio.ruta().resolve("backend");

    private static final Path MUESTRAS =
            BACKEND.resolve(
                    "kamayuk-rentas-aplicacion/src/test/java/kamayuk/rentas/verificaciones/muestras");

    /** El censo de {@code src/main}, una vez para las dos pruebas que lo leen. */
    private static Censo produccion = new Censo(List.of(), 0, 0, 0);

    @BeforeAll
    static void censarLaProduccion() {
        produccion = censarLosModulos();
    }

    @Test
    @DisplayName("todo tipo publico, caso de uso publico y metodo de interfaz lleva javadoc")
    void ningunaPiezaDelAlcanceSinJavadoc() {
        assertThat(produccion.sinJavadoc())
                .as(
                        "a estas piezas de la API publica les falta el javadoc: una frase que diga"
                                + " que hace y para que, en el estilo del codigo de alrededor —el"
                                + " por que, no el nombre repetido—. Un metodo que sobrescribe"
                                + " hereda el del que sobrescribe, y no hace falta")
                .isEmpty();
    }

    @Test
    @DisplayName("y hay que mirar: el recorrido ve los tipos, los casos de uso y los puertos")
    void hayQueMirar() {
        assertThat(produccion.tipos())
                .as(
                        "tipos publicos (1 848 el 2026-09-29): si bajan de mil, javac dejo de"
                                + " leer el arbol y la regla sale verde sin mirar nada")
                .isGreaterThanOrEqualTo(1_000);
        assertThat(produccion.casosDeUso())
                .as("metodos publicos de caso de uso (279 el 2026-09-29)")
                .isGreaterThanOrEqualTo(150);
        assertThat(produccion.metodosDeInterfaz())
                .as("metodos de interfaz (482 el 2026-09-29)")
                .isGreaterThanOrEqualTo(250);
    }

    @Test
    @DisplayName("la regla muerde sobre su muestra, y solo donde tiene que morder")
    void laReglaMuerdeSobreSuMuestra() {
        String aplicacion = "kamayuk.rentas.verificaciones.muestras.aplicacion.";
        String caso = aplicacion + "MuestrasDeApiSinJavadoc.CasoDeUso#";
        String puerto = aplicacion + "MuestrasDeApiSinJavadoc.Puerto";

        Censo muestra =
                censar(
                        List.of(
                                MUESTRAS.resolve("aplicacion/MuestrasDeApiSinJavadoc.java"),
                                MUESTRAS.resolve("web/MuestraDeServicioFueraDeAplicacion.java")));

        assertThat(muestra.sinJavadoc())
                .as(
                        "lo que termina en «Acusado», y nada mas: ni el tipo que no se publica, ni"
                                + " el constructor, ni lo que sobrescribe —con o sin @Override—, ni"
                                + " un record, ni un componente que no es @Service, ni un @Service"
                                + " fuera de ..aplicacion..")
                .containsExactlyInAnyOrder(
                        aplicacion + "MuestrasDeApiSinJavadoc.TipoAcusado",
                        caso + "metodoAcusado()",
                        caso + "conComentarioDeLineaAcusado()",
                        caso + "conComentarioDeBloqueAcusado()",
                        caso + "conJavadocVacioAcusado()",
                        caso + "estaticoAcusado()",
                        puerto + "#metodoDeInterfazAcusado()",
                        puerto + "#porOmisionAcusado()",
                        puerto + ".Anidada#anidadoAcusado()",
                        puerto + ".InterfazAnidadaAcusado");
    }

    // ---------------------------------------------------------------- censo

    /**
     * Lo que el recorrido encontro sin javadoc, y cuanto miro de cada clase: sin lo segundo, un
     * recorrido que no ve nada daria la lista vacia en verde.
     */
    record Censo(List<String> sinJavadoc, int tipos, int casosDeUso, int metodosDeInterfaz) {}

    /**
     * Un {@code javac} por modulo y no uno para todos: juntos, los arboles de los diecisiete se
     * quedarian en memoria a la vez, y esta JVM tiene 512 MB para todas las barreras (#627). El
     * gestor de archivos si es uno, y es lo que abre los jar del classpath.
     */
    private static Censo censarLosModulos() {
        List<String> sinJavadoc = new ArrayList<>();
        int tipos = 0;
        int casosDeUso = 0;
        int metodosDeInterfaz = 0;
        JavaCompiler javac = javac();
        try (StandardJavaFileManager archivos =
                javac.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            for (Path fuentes : raicesDeProduccion()) {
                Censo censo =
                        censar(
                                javac,
                                archivos,
                                ArbolDeFuentes.archivos(fuentes).stream()
                                        .filter(archivo -> archivo.toString().endsWith(".java"))
                                        .toList());
                sinJavadoc.addAll(censo.sinJavadoc());
                tipos += censo.tipos();
                casosDeUso += censo.casosDeUso();
                metodosDeInterfaz += censo.metodosDeInterfaz();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Censo(List.copyOf(sinJavadoc), tipos, casosDeUso, metodosDeInterfaz);
    }

    private static List<Path> raicesDeProduccion() {
        try (Stream<Path> modulos = Files.list(BACKEND)) {
            return modulos.filter(
                            modulo -> modulo.getFileName().toString().startsWith("kamayuk-rentas-"))
                    .map(modulo -> modulo.resolve("src/main/java"))
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** El censo de unas fuentes sueltas: las de la muestra. */
    static Censo censar(List<Path> fuentes) {
        JavaCompiler javac = javac();
        try (StandardJavaFileManager archivos =
                javac.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            return censar(javac, archivos, fuentes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Pasa {@code javac} sobre las fuentes con {@code -proc:only}: lee, resuelve los tipos contra
     * el classpath de esta prueba —que tiene los diecisiete modulos— y no genera nada. Un error de
     * {@code javac} para la prueba: si no pudo leer el arbol, la guarda no sabria que mira.
     */
    private static Censo censar(
            JavaCompiler javac, StandardJavaFileManager archivos, List<Path> fuentes) {
        DiagnosticCollector<JavaFileObject> diagnosticos = new DiagnosticCollector<>();
        Censista censista = new Censista();
        JavaCompiler.CompilationTask tarea =
                javac.getTask(
                        null,
                        archivos,
                        diagnosticos,
                        List.of(
                                "-proc:only",
                                "-implicit:none",
                                "-nowarn",
                                "-Xlint:none",
                                "-classpath",
                                System.getProperty("java.class.path")),
                        null,
                        archivos.getJavaFileObjectsFromPaths(fuentes));
        tarea.setProcessors(List.of(censista));
        tarea.call();
        List<String> errores =
                diagnosticos.getDiagnostics().stream()
                        .filter(diagnostico -> diagnostico.getKind() == Diagnostic.Kind.ERROR)
                        .map(diagnostico -> diagnostico.toString())
                        .toList();
        if (!errores.isEmpty()) {
            throw new IllegalStateException(
                    "javac no pudo leer las fuentes, y sin leerlas la guarda no sabe lo que mira:\n"
                            + String.join("\n", errores));
        }
        return censista.censo();
    }

    private static JavaCompiler javac() {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException(
                    "Esta JVM no trae javac: la guarda lee las fuentes con el, y necesita un JDK");
        }
        return javac;
    }

    /** El procesador que {@code javac} llama con cada tipo que leyo de las fuentes. */
    private static final class Censista extends AbstractProcessor {

        private final List<String> sinJavadoc = new ArrayList<>();
        private int tipos;
        private int casosDeUso;
        private int metodosDeInterfaz;

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> anotaciones, RoundEnvironment ronda) {
            for (Element raiz : ronda.getRootElements()) {
                if (raiz instanceof TypeElement tipo) {
                    revisar(tipo);
                }
            }
            return false;
        }

        Censo censo() {
            return new Censo(List.copyOf(sinJavadoc), tipos, casosDeUso, metodosDeInterfaz);
        }

        private void revisar(TypeElement tipo) {
            if (!sePublica(tipo)) {
                return;
            }
            tipos++;
            exigir(tipo, tipo.getQualifiedName().toString());
            boolean casoDeUso = esCasoDeUso(tipo);
            boolean interfaz = tipo.getKind() == ElementKind.INTERFACE;
            for (Element miembro : tipo.getEnclosedElements()) {
                if (miembro instanceof TypeElement anidado) {
                    revisar(anidado);
                } else if ((casoDeUso || interfaz)
                        && miembro instanceof ExecutableElement metodo
                        && metodo.getKind() == ElementKind.METHOD
                        && metodo.getModifiers().contains(Modifier.PUBLIC)
                        && !sobrescribe(metodo, tipo)) {
                    if (casoDeUso) {
                        casosDeUso++;
                    } else {
                        metodosDeInterfaz++;
                    }
                    exigir(metodo, firma(tipo, metodo));
                }
            }
        }

        /** Lo que javadoc publica: publico o protegido, y dentro de un tipo que tambien lo es. */
        private static boolean sePublica(TypeElement tipo) {
            Set<Modifier> modificadores = tipo.getModifiers();
            boolean propio =
                    modificadores.contains(Modifier.PUBLIC)
                            || modificadores.contains(Modifier.PROTECTED);
            return propio
                    && (!(tipo.getEnclosingElement() instanceof TypeElement duenio)
                            || sePublica(duenio));
        }

        private boolean esCasoDeUso(TypeElement tipo) {
            String paquete =
                    processingEnv
                            .getElementUtils()
                            .getPackageOf(tipo)
                            .getQualifiedName()
                            .toString();
            return paquete.contains(".aplicacion")
                    && tipo.getAnnotationMirrors().stream()
                            .map(anotacion -> anotacion.getAnnotationType().asElement())
                            .anyMatch(
                                    anotacion ->
                                            anotacion instanceof TypeElement clase
                                                    && clase.getQualifiedName()
                                                            .contentEquals(
                                                                    Service.class.getName()));
        }

        /** Si sobrescribe o implementa un metodo de algun ancestro, con o sin {@code @Override}. */
        private boolean sobrescribe(ExecutableElement metodo, TypeElement tipo) {
            Types tiposDelModelo = processingEnv.getTypeUtils();
            Deque<TypeMirror> pendientes =
                    new ArrayDeque<>(tiposDelModelo.directSupertypes(tipo.asType()));
            Set<String> vistos = new HashSet<>();
            while (!pendientes.isEmpty()) {
                TypeMirror arriba = pendientes.pop();
                if (!(tiposDelModelo.asElement(arriba) instanceof TypeElement ancestro)
                        || !vistos.add(ancestro.getQualifiedName().toString())) {
                    continue;
                }
                for (Element suyo : ancestro.getEnclosedElements()) {
                    if (suyo instanceof ExecutableElement otro
                            && otro.getSimpleName().equals(metodo.getSimpleName())
                            && processingEnv.getElementUtils().overrides(metodo, otro, tipo)) {
                        return true;
                    }
                }
                pendientes.addAll(tiposDelModelo.directSupertypes(arriba));
            }
            return false;
        }

        private void exigir(Element elemento, String nombre) {
            String javadoc = processingEnv.getElementUtils().getDocComment(elemento);
            if (javadoc == null || javadoc.isBlank()) {
                sinJavadoc.add(nombre);
            }
        }

        /** {@code paquete.Tipo#metodo(Argumento, Otro)}, con los argumentos sin paquete. */
        private String firma(TypeElement tipo, ExecutableElement metodo) {
            Types tiposDelModelo = processingEnv.getTypeUtils();
            return tipo.getQualifiedName()
                    + "#"
                    + metodo.getSimpleName()
                    + metodo.getParameters().stream()
                            .map(
                                    argumento ->
                                            tiposDelModelo
                                                    .erasure(argumento.asType())
                                                    .toString()
                                                    .replaceAll("(?:[a-z_$][\\w$]*\\.)+", ""))
                            .collect(joining(", ", "(", ")"));
        }
    }
}
