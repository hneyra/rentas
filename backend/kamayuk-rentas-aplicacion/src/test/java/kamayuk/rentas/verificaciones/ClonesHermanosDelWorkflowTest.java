package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lo que este repositorio pide de un clon hermano es lo que el workflow trae.
 *
 * <h2>Que defecto cierra</h2>
 *
 * <p>El `Backend` de los cuatro sistemas llevaba rojo, y cuando C-21 quito la causa que mataba el
 * paso de aislamiento aparecieron debajo <b>tres roturas mas del mismo tipo</b>, una por cada forma
 * de pedirle algo a un hermano:
 *
 * <pre>
 * «/…/rentas/docs/50-api/anti-entropia/huella-del-lote.json» no existe. Lo publica «rentas» …
 * Input file does not exist … '/…/normativa/docs/…/publicacion/parametros-2026.csv'
 * No esta /…/rentas/infra/carga-de-datos/ejemplos/contribuyentes.csv …
 * </pre>
 *
 * <p>Las tres tenian la misma causa —el workflow no traia ese directorio del hermano— y ninguna se
 * veia en local, donde los cinco clones estan al lado. Ninguna guarda las cubria.
 *
 * <h2>Las tres formas, y por que las tres cuentan</h2>
 *
 * <p><b>1. Entrada de Gradle</b> — {@code inputs.file(rootProject.file("../../X/…"))}. Se resuelve
 * al CONFIGURAR la tarea: el archivo que falta no da una asercion sino «Input file does not exist»
 * y no llega a ejecutarse ni una prueba.
 *
 * <p><b>2. Prueba de contrato</b> — {@code ContratoConElConsumidorTestBase} y {@code
 * VectoresDeHuellaTestBase} resuelven su archivo en el clon del otro y <b>se niegan a saltarse</b>.
 *
 * <p><b>3. Ruta resuelta a mano</b> — un literal como {@code "rentas/infra/carga-de-datos/…"}
 * dentro de una prueba. Es la que ninguna de las otras dos veria, y es justo la que quedaba viva
 * cuando esta guarda se escribio con solo las dos primeras.
 *
 * <p>Las tres se rompen igual —el archivo no esta— y se arreglan igual: ampliando el {@code
 * checkout} del workflow. Por eso las mide la misma guarda.
 *
 * <h2>Por que las rutas no estan escritas aqui</h2>
 *
 * <p>Porque una lista a mano seria el segundo sitio donde olvidarse de un directorio, que es
 * exactamente el defecto que esto cierra. Las entradas de Gradle salen de los {@code
 * build.gradle.kts}, los contratos se le piden al mismo metodo que la prueba usara al correr, y las
 * rutas a mano salen de los literales del propio codigo de prueba.
 *
 * <h2>Donde esta el limite</h2>
 *
 * <p>Un literal de ruta se reconoce por empezar con el nombre de un hermano y tener al menos tres
 * segmentos. Una ruta compuesta en tiempo de ejecucion a partir de trozos no la veria. Eso es un
 * <b>falso negativo</b>, nunca lo contrario: esta guarda no puede dar por bueno un directorio que
 * falte de los que si conoce.
 */
@DisplayName("C-22 — El workflow trae lo que este repositorio pide del clon hermano")
class ClonesHermanosDelWorkflowTest {

    /** Los otros cuatro repositorios del producto. El propio se descarta al vuelo. */
    private static final List<String> HERMANOS =
            List.of("rentas", "catastro", "normativa", "caja", "infrastructure");

    /** Las dos bases que leen del clon de otro repositorio, con el metodo que da su archivo. */
    private static final Map<String, String> BASES_DE_CONTRATO =
            Map.of(
                    "VectoresDeHuellaTestBase", "archivo",
                    "ContratoConElConsumidorTestBase", "archivoDelConsumidor");

    /** {@code rootProject.file("../../<hermano>/<ruta>")}, que es como se declara una entrada. */
    private static final Pattern ENTRADA_DE_GRADLE =
            Pattern.compile("rootProject\\.file\\(\"\\.\\./\\.\\./([^\"]+)\"\\)");

    /** Un literal de ruta que empieza por el nombre de un hermano. */
    private static final Pattern RUTA_A_MANO =
            Pattern.compile("\"((?:" + String.join("|", HERMANOS) + ")/[A-Za-z0-9._/-]+)\"");

    @Test
    @DisplayName("cada archivo que este repositorio pide de un hermano cae bajo su sparse-checkout")
    void loQueSePideEsLoQueSeTrae() throws Exception {
        Path raiz = raizDelClon();
        Map<String, Set<String>> traido = sparseCheckoutPorRepositorio(raiz);
        List<String[]> pedido = new ArrayList<>();
        pedido.addAll(entradasDeGradle(raiz));
        pedido.addAll(loQueLasPruebasResuelven(raiz));
        pedido.addAll(rutasEscritasAMano(raiz));

        assertThat(pedido)
                .as(
                        "no se encontro nada que este repositorio pida de un hermano: esta guarda"
                                + " no estaria midiendo nada. O se movieron las declaraciones, o cambio"
                                + " su forma")
                .isNotEmpty();

        for (String[] par : pedido) {
            String hermano = par[0];
            String ruta = par[1];
            Set<String> patrones = traido.get(hermano);

            assertThat(patrones)
                    .as(
                            "este repositorio pide «%s/%s» y el workflow no hace checkout de «%s»:"
                                    + " en CI ese archivo no existe. Si es una entrada de Gradle el"
                                    + " fallo es «Input file does not exist» y no corre ni una prueba;"
                                    + " si es un contrato o una ruta a mano, la prueba cae diciendo «no"
                                    + " existe», que se lee como si el otro sistema no lo hubiera"
                                    + " publicado",
                            hermano, ruta, hermano)
                    .isNotNull();

            assertThat(patrones.stream().anyMatch(ruta::startsWith))
                    .as(
                            "el checkout de «%s» trae %s, y aqui se pide «%s», que no cae bajo"
                                    + " ninguno. Hay que ampliar el sparse-checkout en"
                                    + " .github/workflows/backend.yml: sin eso el rojo solo aparece en"
                                    + " CI, sobre un archivo que en local esta",
                            hermano, patrones, ruta)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("EL CONTRASTE: y el sparse-checkout que se lee es el del workflow, no una copia")
    void yLoQueSeLeeEsElWorkflow() throws Exception {
        // Sin esto, «todo cubierto» seria compatible con no haber encontrado ningun checkout: un
        // mapa vacio no puede contradecir a nadie, y un cambio de formato del YAML dejaria la
        // guarda muda en verde.
        assertThat(sparseCheckoutPorRepositorio(raizDelClon()))
                .as(
                        "no se leyo ningun `sparse-checkout` de .github/workflows/backend.yml. O el"
                                + " workflow dejo de usarlos —y entonces esta guarda sobra— o cambio su"
                                + " forma y hay que enseñarsela")
                .isNotEmpty();
    }

    /**
     * El directorio del clon de este repositorio: el primer ancestro con un `.git`.
     *
     * <p><b>{@code exists} y no {@code isDirectory}</b>, y no es un detalle: en un {@code git
     * worktree} el {@code .git} de la raiz es un <b>archivo</b> con una linea {@code gitdir:}
     * dentro, asi que con {@code isDirectory} el recorrido sube hasta la raiz del sistema de
     * archivos y muere con «No se encontro la raiz». Eso no es un rojo que hable de lo que esta
     * guarda vigila: es que la guarda no se puede correr, que es peor. {@code catastro} y {@code
     * normativa} cerraron el mismo defecto en sus dos ayudantes; aqui quedaba este.
     */
    private static Path raizDelClon() {
        Path actual = Path.of("").toAbsolutePath();
        while (actual != null && !Files.exists(actual.resolve(".git"))) {
            actual = actual.getParent();
        }
        if (actual == null) {
            throw new IllegalStateException(
                    "No se encontro la raiz del clon desde " + Path.of("").toAbsolutePath());
        }
        return actual;
    }

    /**
     * Los `sparse-checkout` del workflow, por repositorio hermano.
     *
     * <p>Se lee el YAML como texto en vez de con un analizador: la forma que interesa son tres
     * claves seguidas dentro de un paso, y meter una dependencia de YAML en el modulo de pruebas
     * por eso seria pagar mas de lo que se compra.
     *
     * <p>Un `checkout` SIN `sparse-checkout` trae el repositorio entero, y se representa con el
     * patron vacio, que cubre cualquier ruta.
     */
    private static Map<String, Set<String>> sparseCheckoutPorRepositorio(Path raiz)
            throws IOException {
        Path workflow = raiz.resolve(".github/workflows/backend.yml");
        assertThat(workflow).as("no esta el workflow del backend").exists();

        Map<String, Set<String>> porRepositorio = new LinkedHashMap<>();
        String repositorio = null;
        boolean enLista = false;
        for (String cruda : Files.readString(workflow, StandardCharsets.UTF_8).split("\n")) {
            String linea = cruda.strip();
            if (linea.startsWith("- name:")) {
                repositorio = null;
                enLista = false;
            } else if (linea.startsWith("repository:")) {
                String valor = linea.substring("repository:".length()).strip();
                repositorio = valor.contains("/") ? valor.substring(valor.indexOf('/') + 1) : valor;
                enLista = false;
                // Sin `sparse-checkout` viene entero: el patron vacio cubre cualquier ruta.
                porRepositorio.computeIfAbsent(repositorio, r -> new LinkedHashSet<>()).add("");
            } else if (linea.startsWith("sparse-checkout:") && repositorio != null) {
                String valor = linea.substring("sparse-checkout:".length()).strip();
                enLista = valor.equals("|") || valor.equals(">");
                // Deja de venir entero: a partir de aqui solo lo que se nombre.
                porRepositorio.get(repositorio).remove("");
                if (!enLista && !valor.isEmpty()) {
                    porRepositorio.get(repositorio).add(valor);
                }
            } else if (enLista) {
                if (linea.isEmpty() || linea.startsWith("#") || linea.contains(":")) {
                    enLista = false;
                } else {
                    porRepositorio.get(repositorio).add(linea);
                }
            }
        }
        return porRepositorio;
    }

    /** Lo que los `build.gradle.kts` declaran como entrada fuera de este clon. */
    private static List<String[]> entradasDeGradle(Path raiz) throws IOException {
        List<String[]> declaradas = new ArrayList<>();
        for (Path guion : fuentes(raiz, ".gradle.kts")) {
            Matcher coincidencia =
                    ENTRADA_DE_GRADLE.matcher(Files.readString(guion, StandardCharsets.UTF_8));
            while (coincidencia.find()) {
                declaradas.add(partir(coincidencia.group(1)));
            }
        }
        return declaradas;
    }

    /** Rutas de hermano escritas como literal en el codigo de prueba. */
    private static List<String[]> rutasEscritasAMano(Path raiz) throws IOException {
        String propio = raiz.getFileName().toString();
        List<String[]> escritas = new ArrayList<>();
        for (Path archivo : fuentes(raiz, ".java")) {
            Matcher coincidencia =
                    RUTA_A_MANO.matcher(Files.readString(archivo, StandardCharsets.UTF_8));
            while (coincidencia.find()) {
                String ruta = coincidencia.group(1);
                // Menos de tres segmentos es casi siempre otra cosa —un identificador con barra,
                // una URL recortada—, y exigir un checkout por eso seria ruido.
                if (!ruta.startsWith(propio + "/")
                        && ruta.chars().filter(c -> c == '/').count() >= 2) {
                    escritas.add(partir(ruta));
                }
            }
        }
        return escritas;
    }

    /**
     * Lo que cada prueba de contrato resuelve, como (hermano, ruta dentro de ese hermano).
     *
     * <p>Se descarta lo que cae en el clon de ESTE repositorio: el {@code VectoresDeHuellaTest} del
     * repositorio que PUBLICA resuelve su propio archivo, y exigirle un `checkout` de si mismo no
     * tendria sentido.
     */
    private static List<String[]> loQueLasPruebasResuelven(Path raiz) throws Exception {
        String propio = raiz.getFileName().toString();
        List<String[]> resueltas = new ArrayList<>();
        for (Path archivo : fuentes(raiz, "Test.java")) {
            String texto = Files.readString(archivo, StandardCharsets.UTF_8);
            for (Map.Entry<String, String> base : BASES_DE_CONTRATO.entrySet()) {
                if (!texto.contains("extends " + base.getKey())) {
                    continue;
                }
                Path camino = caminoDe(claseDe(texto, archivo), base.getValue());
                Path relativa = raiz.getParent().relativize(camino);
                if (!relativa.getName(0).toString().equals(propio)) {
                    resueltas.add(
                            new String[] {
                                relativa.getName(0).toString(),
                                relativa.subpath(1, relativa.getNameCount()).toString()
                            });
                }
            }
        }
        return resueltas;
    }

    private static List<Path> fuentes(Path raiz, String sufijo) throws IOException {
        try (Stream<Path> archivos = Files.walk(raiz.resolve("backend"))) {
            return archivos.filter(p -> p.toString().endsWith(sufijo))
                    .filter(p -> !p.toString().contains("/build/"))
                    .toList();
        }
    }

    private static String[] partir(String rutaConHermano) {
        int barra = rutaConHermano.indexOf('/');
        return new String[] {
            rutaConHermano.substring(0, barra), rutaConHermano.substring(barra + 1)
        };
    }

    private static Class<?> claseDe(String texto, Path archivo) throws ClassNotFoundException {
        String paquete = texto.substring(texto.indexOf("package ") + 8, texto.indexOf(";")).strip();
        String nombre = archivo.getFileName().toString().replace(".java", "");
        return Class.forName(paquete + "." + nombre);
    }

    private static Path caminoDe(Class<?> clase, String metodo) throws Exception {
        Object instancia = clase.getDeclaredConstructor().newInstance();
        Method resolutor = clase.getSuperclass().getDeclaredMethod(metodo);
        resolutor.setAccessible(true);
        return (Path) resolutor.invoke(instancia);
    }
}
