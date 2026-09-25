package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Una cifra que un comentario da como el tamano de HOY lleva su marca, y la marca se recalcula
 * (#309).
 *
 * <h2>De que defecto viene, medido</h2>
 *
 * <p>El javadoc de {@code frontend/verificaciones/un-importe-sin-su-fecha-esta-declarado.test.ts}
 * decia «167 operaciones» y «3 250 campos tipados» del contrato, y el contrato traia 168 y 3 280
 * —contados como los cuenta esta clase—: las regeneraciones de {@code formas-de-la-api.json} las
 * habian movido sin que nada se pusiera rojo. Y la de 167 nacio vieja —#303 se mezclo 75 segundos
 * antes que #305 y anadio la operacion que faltaba—. Es el modo de fallo que las guardas existen
 * para impedir, una afirmacion en verde que ya no es verdad, <b>un nivel mas arriba</b>: en la
 * prosa con que se decidio escribirlas.
 *
 * <h2>La convencion</h2>
 *
 * <p>Una cifra que describe el disco <b>de hoy</b> —no la de un dia, sino la que tiene que seguir
 * siendo verdad— se escribe como {@code MEDIDO: <n> <medida>}: en Java dentro de un {@code @code},
 * en TypeScript entre comillas invertidas. Esta prueba barre {@code backend/}, {@code frontend/} e
 * {@code infrastructure/}, y por cada marca:
 *
 * <ul>
 *   <li>si {@code <medida>} no es una de las de {@link #MEDIDAS}, rojo: una marca que nadie sabe
 *       recalcular es una cifra en prosa con otro disfraz;
 *   <li>si el disco da otro numero, rojo, con el archivo, la linea, lo escrito y lo medido.
 * </ul>
 *
 * <p>El numero admite el separador de miles de la casa —«3 266»—, y la medida puede partirse entre
 * dos lineas del comentario.
 *
 * <h2>El censo, antes de escribir nada (#309)</h2>
 *
 * <p>El issue pedia contarlas primero, porque de cuantas fueran dependia la salida. Sobre los
 * comentarios de las guardas —{@code frontend/verificaciones/}, {@code frontend/e2e/}, las pruebas
 * de {@code kamayuk-rentas-aplicacion} y de {@code kamayuk-rentas-esquema}, {@code
 * docs/00-gobierno/} e {@code infrastructure/}—: <b>149 archivos, 90 con alguna cifra, 406
 * lineas</b>. Leidas una a una, casi todas son de otras dos clases: <b>constantes</b> —un 404, un
 * puerto, un 4,5:1 de WCAG, el tamano de una captura congelada— y <b>medidas de un dia</b> que ya
 * dicen de cual —«eran 22 hasta #218», «medido el 2026-09-20», «leido sobre dd64f02»—. Ninguna de
 * las dos se queda vieja.
 *
 * <p>Las que decian el tamano del arbol <b>de hoy</b> eran <b>23 sitios en 15 archivos</b>, y <b>9
 * ya eran falsos</b> en verde: «167 operaciones», «3 250 campos», «624 campos», «78 interfaces» y
 * «213/79/19» en {@code un-importe-sin-su-fecha-esta-declarado}; «179 operaciones» en {@code
 * FormasDeLaApiTest}; «753 entradas» del locale; «17 cadenas en 22 columnas» en el javadoc de
 * {@code la-insignia-no-se-pinta-verde-sin-regla}, cuyo propio centinela afirma 19 y 21; «181
 * respuestas» en {@code camino-a-la-api}; y «las otras 62 pruebas» en {@code
 * el-grafico-de-ini-flujo-se-dibuja}. Con veintitres, ni la (1) para todas ni la (2) para todas:
 *
 * <ul>
 *   <li><b>La marca</b>, para las que son una funcion barata del disco y estan solas en su frase:
 *       cuatro sitios —cinco con el javadoc de {@code KamayukAplicacion#reloj()}, fuera del censo,
 *       que salio tirando del hilo— y las cuatro medidas de {@link #MEDIDAS}.
 *   <li><b>Su issue</b>, para las que salen de medir la heuristica de la propia guarda —«de los N
 *       campos, el patron atrapa dos»—: recalcularlas aqui seria copiar la heuristica en Java, y
 *       marcar solo el denominador dejaria viejo el numerador con la marca en verde al lado. Ocho
 *       sitios pasan a decir «medido en #309» o «con #291», que es la medida con que se decidio y
 *       avisa de que hay que volver a medir.
 *   <li><b>Se retira el numero</b> donde no sostenia nada o donde la prueba ya lo afirma: once
 *       sitios, sin tocar el razonamiento de al lado.
 * </ul>
 *
 * <h2>Y por que no un escaner que exija marca o issue a TODA cifra</h2>
 *
 * <p>Porque se midio, sobre el arbol ya arreglado: marcaria <b>352 lineas en 89 archivos</b>, y en
 * la lectura de #309 ninguna es una cifra de hoy sin su marca —185 son, por su forma, un codigo
 * HTTP—. Es la regla ancha que #255 descarto en {@code
 * ningun-javadoc-delega-en-un-archivo-que-no-existe} por lo mismo: una guarda con cientos de rojos
 * sobre prosa correcta se acaba desactivando. Lo que se vigila es la marca; que una cifra en
 * presente la lleve lo lee la revision.
 *
 * <h2>Por que vive en el backend</h2>
 *
 * <p>Porque la mitad de las cifras sale de {@code docs/50-api/}, y lo que la regenera es un PR de
 * backend. {@code frontend.yml} solo corre con {@code frontend/**}: una guarda en {@code
 * verificaciones/} no se habria enterado de la regeneracion que deja la cifra vieja, y el rojo le
 * habria caido al siguiente PR de interfaz, que no la toco. {@code backend.yml} no filtra rutas, y
 * esta clase corre en {@code verificarArquitectura}. Pero que corra no basta con que el flujo se
 * dispare: lo que lee fuera de {@code backend/} —el locale, y los fuentes de {@code frontend/} e
 * {@code infrastructure/}— esta declarado como entrada de la tarea en {@code build.gradle.kts}, y
 * sin eso un PR solo de interfaz la dejaba UP-TO-DATE, o FROM-CACHE contra la cache de {@code
 * main}.
 *
 * <h2>Lo que esto NO comprueba, y se dice</h2>
 *
 * <p>Que la prosa de alrededor siga siendo verdad con la cifra nueva: eso lo lee quien la
 * actualiza, y el rojo se lo dice. Ni que una cifra sin marca sea historica: la regla es de quien
 * escribe, y la revision la lee.
 */
@DisplayName("#309 — Las cifras medidas de los comentarios cuadran con el disco")
class LasCifrasMedidasCuadranConElDiscoTest {

    /**
     * Lo que una marca puede medir, con su nombre y como se recalcula.
     *
     * <p>Cada una es una funcion del disco y de nada mas. Anadir una es anadir una linea aqui, y
     * {@link #cadaMedidaLaUsaAlgunaMarca()} exige que alguna marca la use: una medida que nadie
     * cita es codigo muerto que se queda viejo sin dar rojo.
     */
    record Medida(String nombre, ToLongFunction<Path> calculo) {}

    static final List<Medida> MEDIDAS =
            List.of(
                    new Medida(
                            "operaciones de formas-de-la-api.json",
                            raiz -> operacionesDe(formas(raiz)).size()),
                    new Medida(
                            "campos tipados de formas-de-la-api.json",
                            raiz -> {
                                JsonNode formas = formas(raiz);
                                return operacionesDe(formas).stream()
                                        .mapToLong(clave -> camposTipados(formas.get(clave)))
                                        .sum();
                            }),
                    new Medida(
                            "entradas de es.json",
                            raiz ->
                                    hojas(
                                            leerJson(
                                                    raiz.resolve(
                                                            "frontend/src/i18n/locales/es.json")))),
                    new Medida(
                            "LocalDate.now(reloj) en el codigo de src/main",
                            LasCifrasMedidasCuadranConElDiscoTest::lecturasDelDiaEnProduccion));

    /**
     * Los arboles que se barren. {@code docs/} no: sus cifras las renueva quien las mide.
     *
     * <p>Esta lista, {@link #DIRECTORIOS_FUERA} y {@link #FUENTE} estan <b>repetidas</b> como
     * entradas de {@code tasks.test} en {@code build.gradle.kts}, y tienen que decir lo mismo: lo
     * que la guarda lee y Gradle no declara deja la tarea UP-TO-DATE —o FROM-CACHE— cuando cambia,
     * con la marca vieja en verde. Medido en la revision de #309 con una entrada mas en {@code
     * es.json}.
     */
    private static final List<String> ARBOLES = List.of("backend", "frontend", "infrastructure");

    /** Lo que no es fuente de nadie: dependencias, salidas de build y del arnes. */
    private static final Set<String> DIRECTORIOS_FUERA =
            Set.of(
                    "node_modules",
                    "build",
                    "dist",
                    "out",
                    "bin",
                    ".gradle",
                    ".git",
                    "test-results",
                    "playwright-report",
                    "coverage");

    private static final Pattern FUENTE = Pattern.compile(".*\\.(java|kts|ts|tsx|mjs|js)$");

    /**
     * La marca: la palabra, dos puntos, el numero —con o sin separador de miles— y un blanco.
     *
     * <p>Se escribe partida para que esta clase no se barra a si misma: el literal entero, aqui,
     * seria una marca sin medida.
     */
    private static final Pattern MARCA =
            Pattern.compile("MEDI" + "DO:[ \\t]+(\\d{1,3}(?:[ \\u00A0]\\d{3})+|\\d+)[ \\t]+");

    /** Lo que une dos lineas de un comentario: el salto, la sangria y el asterisco o las barras. */
    private static final Pattern CONTINUACION = Pattern.compile("\\s*\\R\\s*(?:\\*(?!/)|//)?\\s*");

    @Test
    @DisplayName("cada cifra marcada es la que el disco da hoy")
    void cadaCifraMarcadaEsLaDelDisco() {
        Path raiz = RaizDelRepositorio.ruta();
        List<String> hallazgos = new ArrayList<>();
        for (Path archivo : fuentes(raiz)) {
            hallazgos.addAll(revisar(raiz.relativize(archivo).toString(), leer(archivo), raiz));
        }
        assertThat(hallazgos)
                .as(
                        "Estas cifras de un comentario dicen el tamano de hoy y el disco dice"
                                + " otro:\n%s\n\n"
                                + "  Re-lee la frase entera, no solo el numero: si el razonamiento"
                                + " sigue valiendo con la cifra nueva, escribela; si no, lo que"
                                + " habia que cambiar era la frase. Y si la cifra era la de UN"
                                + " dia, no la marques: di de que issue es (#309).",
                        String.join("\n", hallazgos))
                .isEmpty();
    }

    @Test
    @DisplayName("y cada medida registrada la usa alguna marca")
    void cadaMedidaLaUsaAlgunaMarca() {
        // Sin esto el escaner podria dejar de ver un arbol entero —una ruta mal escrita, una
        // extension que falta— y la prueba de arriba pasaria en verde sobre la nada.
        Path raiz = RaizDelRepositorio.ruta();
        StringBuilder todo = new StringBuilder();
        for (Path archivo : fuentes(raiz)) {
            todo.append(leer(archivo)).append('\n');
        }
        List<String> citadas = medidasCitadas(todo.toString());
        List<String> muertas =
                MEDIDAS.stream()
                        .map(Medida::nombre)
                        .filter(nombre -> !citadas.contains(nombre))
                        .toList();
        assertThat(muertas)
                .as(
                        "Ninguna marca usa estas medidas: o el escaner dejo de ver el arbol donde"
                                + " estaban, o la marca se retiro y la medida sobra")
                .isEmpty();
    }

    @Test
    @DisplayName("el escaner muerde: una cifra vieja y una medida sin nombre salen rojas")
    void elEscanerMuerde() {
        Path raiz = RaizDelRepositorio.ruta();
        long operaciones = MEDIDAS.getFirst().calculo().applyAsLong(raiz);
        String marca = "MEDI" + "DO: ";
        String texto =
                "/**\n"
                        + " * Son las {@code "
                        + marca
                        + operaciones
                        + " operaciones de formas-de-la-api.json}, bien.\n"
                        + " * Y las {@code "
                        + marca
                        + (operaciones + 1)
                        + " operaciones de\n"
                        + " * formas-de-la-api.json}, partida en dos lineas y vieja.\n"
                        + " * Y `"
                        + marca
                        + "1 234 cosas que nadie mide`.\n"
                        + " */\n";

        List<String> hallazgos = revisar("muestra.java", texto, raiz);

        assertThat(hallazgos)
                .as("la cifra buena no sale; salen la vieja, por su linea, y la desconocida")
                .containsExactly(
                        "  muestra.java:3 dice "
                                + (operaciones + 1)
                                + " operaciones de formas-de-la-api.json y el disco da "
                                + operaciones,
                        "  muestra.java:5 marca «1 234 cosas que nadie mide`.» y ninguna medida"
                                + " registrada se llama asi");
    }

    /** Las marcas de un texto, cada una contra su medida. */
    static List<String> revisar(String nombre, String texto, Path raiz) {
        List<String> hallazgos = new ArrayList<>();
        Matcher marca = MARCA.matcher(texto);
        while (marca.find()) {
            long escrito = Long.parseLong(marca.group(1).replaceAll("[ \\u00A0]", ""));
            int linea = numeroDeLinea(texto, marca.start());
            String resto = restoDe(texto, marca.end());
            Medida medida = medidaAlPrincipio(resto);
            if (medida == null) {
                hallazgos.add(
                        "  "
                                + nombre
                                + ":"
                                + linea
                                + " marca «"
                                + marca.group(1)
                                + " "
                                + hastaElFinDeLaLinea(texto, marca.end())
                                + "» y ninguna medida registrada se llama asi");
                continue;
            }
            long medido = medida.calculo().applyAsLong(raiz);
            if (medido != escrito) {
                hallazgos.add(
                        "  "
                                + nombre
                                + ":"
                                + linea
                                + " dice "
                                + marca.group(1)
                                + " "
                                + medida.nombre()
                                + " y el disco da "
                                + medido);
            }
        }
        return hallazgos;
    }

    private static List<String> medidasCitadas(String texto) {
        List<String> citadas = new ArrayList<>();
        Matcher marca = MARCA.matcher(texto);
        while (marca.find()) {
            Medida medida = medidaAlPrincipio(restoDe(texto, marca.end()));
            if (medida != null) {
                citadas.add(medida.nombre());
            }
        }
        return citadas;
    }

    /** Lo que sigue a la marca, con las lineas del comentario unidas por un blanco. */
    private static String restoDe(String texto, int desde) {
        String resto = texto.substring(desde, Math.min(texto.length(), desde + 200));
        return CONTINUACION.matcher(resto).replaceAll(" ");
    }

    private static String hastaElFinDeLaLinea(String texto, int desde) {
        int fin = texto.indexOf('\n', desde);
        return texto.substring(desde, fin < 0 ? texto.length() : fin).strip();
    }

    private static Medida medidaAlPrincipio(String resto) {
        for (Medida medida : MEDIDAS) {
            if (resto.startsWith(medida.nombre())) {
                return medida;
            }
        }
        return null;
    }

    private static int numeroDeLinea(String texto, int posicion) {
        int linea = 1;
        for (int i = 0; i < posicion; i++) {
            if (texto.charAt(i) == '\n') {
                linea++;
            }
        }
        return linea;
    }

    private static List<Path> fuentes(Path raiz) {
        List<Path> fuentes = new ArrayList<>();
        for (String arbol : ARBOLES) {
            Path inicio = raiz.resolve(arbol);
            try (Stream<Path> recorrido = Files.walk(inicio)) {
                recorrido
                        .filter(Files::isRegularFile)
                        .filter(ruta -> FUENTE.matcher(ruta.getFileName().toString()).matches())
                        .filter(ruta -> !fueraDelArbol(inicio.relativize(ruta)))
                        .sorted()
                        .forEach(fuentes::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return fuentes;
    }

    private static boolean fueraDelArbol(Path relativa) {
        for (Path parte : relativa) {
            if (DIRECTORIOS_FUERA.contains(parte.toString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode formas(Path raiz) {
        return leerJson(raiz.resolve("docs/50-api/formas-de-la-api.json"));
    }

    /** Las claves del contrato de formas menos {@code _}, que dice de donde sale el archivo. */
    private static List<String> operacionesDe(JsonNode formas) {
        return formas.propertyNames().stream().filter(clave -> !clave.equals("_")).toList();
    }

    /**
     * Los campos con nombre cuyo valor es un tipo: {@code "fecha": "fecha"} cuenta uno, y {@code
     * "codigos": ["texto"]} tambien —es un campo, tipado lista de texto—.
     *
     * <p>Los objetos y las listas de objetos no cuentan: son el camino hasta sus campos, que si. Y
     * una operacion que el contrato entero reduce a {@code "objeto"} o {@code "archivo"} no tiene
     * campos que contar —la matriz de permisos, un informe en PDF—.
     */
    private static long camposTipados(JsonNode nodo) {
        long suma = 0;
        if (nodo.isArray()) {
            for (JsonNode fila : nodo) {
                suma += camposTipados(fila);
            }
        } else if (nodo.isObject()) {
            for (JsonNode valor : nodo) {
                boolean contenedor =
                        valor.isObject()
                                || (valor.isArray() && !valor.isEmpty() && valor.get(0).isObject());
                suma += contenedor ? camposTipados(valor) : 1;
            }
        }
        return suma;
    }

    /** Las hojas de un JSON: cada valor que no es un objeto ni una lista. */
    private static long hojas(JsonNode nodo) {
        if (nodo.isObject() || nodo.isArray()) {
            long suma = 0;
            for (JsonNode hijo : nodo) {
                suma += hojas(hijo);
            }
            return suma;
        }
        return 1;
    }

    /**
     * Los {@code LocalDate.now(reloj)} del codigo de produccion, sin los que nombra un comentario.
     *
     * <p>Sin comentarios porque la cifra es de sitios que leen el reloj: los javadoc que la citan
     * —empezando por el de {@code KamayukAplicacion#reloj()}— la subirian cada vez que se escriben.
     */
    private static long lecturasDelDiaEnProduccion(Path raiz) {
        long cuenta = 0;
        try (Stream<Path> recorrido = Files.walk(raiz.resolve("backend"))) {
            List<Path> fuentes =
                    recorrido
                            .filter(ruta -> ruta.toString().endsWith(".java"))
                            .filter(ruta -> ruta.toString().contains("/src/main/java/"))
                            .toList();
            for (Path fuente : fuentes) {
                for (String linea : leer(fuente).lines().toList()) {
                    String sin = linea.strip();
                    if (sin.startsWith("*") || sin.startsWith("//") || sin.startsWith("/*")) {
                        continue;
                    }
                    int desde = 0;
                    while ((desde = sin.indexOf("LocalDate.now(reloj)", desde)) >= 0) {
                        cuenta++;
                        desde++;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return cuenta;
    }

    private static JsonNode leerJson(Path archivo) {
        return JsonMapper.builder().build().readTree(leer(archivo));
    }

    private static String leer(Path archivo) {
        try {
            return Files.readString(archivo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
