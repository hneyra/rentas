package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code exigirNumero} solo recibe llaves de la clase que las reune (#376).
 *
 * <h2>Que defecto cierra</h2>
 *
 * <p>La alcabala pedia {@code ALICUOTA_ALCABALA} y los espectaculos {@code ALICUOTA_ESPECTACULO}
 * mientras {@code normativa} publicaba {@code ALCABALA_ALICUOTA} y {@code ESPECTACULO_ALICUOTA}:
 * cada caso de uso escribia su llave en una constante propia, y nada las comparaba con el derivado.
 * Desde #376 el nucleo las reune en {@code LlavesDelConjunto}, y {@code
 * LlavesDelConjuntoContraElDerivadoTest} recorre <b>todas</b> sus constantes contra el archivo que
 * se despliega. Esa prueba solo protege lo que esta en la clase: una llave escrita en otro sitio
 * —un literal, una constante local— volveria a quedar fuera de toda comparacion, y en verde.
 *
 * <h2>La regla</h2>
 *
 * <p>En {@code backend/*}{@code /src/main}, el primer argumento de cada llamada a {@code
 * .exigirNumero(} es una constante o una funcion de una clase {@code Llaves*}: {@code
 * LlavesDelConjunto.UIT}, {@code LlavesDelConjunto.tasaDeArbitrio(servicio)}. Es un escaner de
 * texto y no ArchUnit porque lo que se mira es <b>el argumento</b>, y el bytecode de una constante
 * {@code static final String} es el literal ya plegado: {@code LlavesDelConjunto.UIT} y {@code
 * "UIT"} compilan a lo mismo.
 *
 * <p>Las excepciones van con su motivo en {@link #EXENTAS}, y cada una tiene que seguir llamando a
 * {@code exigirNumero}: una exencion que no exime a nadie se borra. Vive aqui y no en {@code
 * comun-verificaciones} porque su sujeto —el nombre de la clase y las exenciones— es de este
 * repositorio; por eso no la alcanza {@code ReglasDeArquitecturaMuerdenTest}, y que muerde lo
 * demuestra {@link #laReglaMuerdeSobreSuMuestra()}.
 */
@DisplayName("#376 — exigirNumero solo recibe llaves de la clase que las reune")
class ExigirNumeroSoloConLlavesDeclaradasTest {

    /** Una constante o una funcion de una clase {@code Llaves*}, y nada mas. */
    private static final Pattern LLAVE_DECLARADA =
            Pattern.compile("Llaves\\w*\\.[A-Za-z_]\\w*(\\s*\\(.*\\))?", Pattern.DOTALL);

    private static final Pattern LLAMADA = Pattern.compile("\\.\\s*exigirNumero\\s*\\(");

    /** Ruta relativa a {@code backend/}, y por que no pasa por una clase {@code Llaves*}. */
    static final Map<String, String> EXENTAS =
            Map.of(
                    "kamayuk-rentas-parametros/src/main/java/kamayuk/rentas/parametros/"
                            + "InsumosDeLaRegla.java",
                    "es el puerto por el que una regla del motor pide su parametro: la llave la"
                            + " escribe la regla (ARQ-09), y aqui solo se pasa",
                    "kamayuk-rentas-parametros/src/main/java/kamayuk/rentas/parametros/"
                            + "InsumosDeLaAgregacion.java",
                    "el mismo puerto, para una regla de agregacion",
                    "kamayuk-rentas-fiscalizacion/src/main/java/kamayuk/rentas/fiscalizacion/"
                            + "aplicacion/InsumosNormativosDeLaLiquidacion.java",
                    "ya reune sus llaves en LLAVES_QUE_ESPERAN_A_D02A y recorre esa lista; son de"
                            + " D-02a y, salvo la UIT, el derivado no publica ninguna todavia");

    @Test
    @DisplayName("ninguna llamada de produccion pasa una llave que no sea de una clase Llaves*")
    void ningunaLlamadaFueraDeLaClase() {
        List<String> hallazgos = new ArrayList<>();
        for (Path archivo : fuentesDeProduccion()) {
            if (EXENTAS.containsKey(relativa(archivo))) {
                continue;
            }
            for (String argumento : primerosArgumentos(leer(archivo))) {
                if (!LLAVE_DECLARADA.matcher(argumento).matches()) {
                    hallazgos.add(relativa(archivo) + ": exigirNumero(" + argumento + ", ...)");
                }
            }
        }

        assertThat(hallazgos)
                .as(
                        "la llave se escribe en la clase Llaves* del modulo, que es la que"
                                + " LlavesDelConjuntoContraElDerivadoTest compara con el derivado:"
                                + " escrita aqui no la compara nadie (#376)")
                .isEmpty();
    }

    @Test
    @DisplayName("y hay llamadas que mirar, y cada exencion sigue eximiendo a alguien")
    void hayLlamadasQueMirar() {
        long conformes = 0;
        List<String> exentasVivas = new ArrayList<>();
        for (Path archivo : fuentesDeProduccion()) {
            List<String> argumentos = primerosArgumentos(leer(archivo));
            if (EXENTAS.containsKey(relativa(archivo)) && !argumentos.isEmpty()) {
                exentasVivas.add(relativa(archivo));
            }
            conformes +=
                    argumentos.stream()
                            .filter(argumento -> LLAVE_DECLARADA.matcher(argumento).matches())
                            .count();
        }

        assertThat(conformes)
                .as(
                        "las llamadas con llave declarada (13 el 2026-09-25): si bajan de diez, el"
                                + " recorrido dejo de verlas y la regla sale verde sin mirar nada")
                .isGreaterThanOrEqualTo(10);
        assertThat(exentasVivas)
                .as("una exencion cuyo archivo ya no llama a exigirNumero no exime a nadie")
                .containsExactlyInAnyOrderElementsOf(EXENTAS.keySet());
    }

    @Test
    @DisplayName("la regla muerde sobre su muestra: el literal y la constante local, no la clase")
    void laReglaMuerdeSobreSuMuestra() {
        String muestra =
                """
                class MuestraDeLlavesSinDeclarar {
                    private static final String ALICUOTA_ALCABALA = "ALICUOTA_ALCABALA";

                    void determinar(ParametrosSellados sellados, String clase) {
                        // sellados.exigirNumero("EN_UN_COMENTARIO", null) no es una llamada
                        sellados.exigirNumero("ALCABALA_ALICUOTA", null);
                        sellados.exigirNumero(ALICUOTA_ALCABALA, null);
                        sellados
                                .exigirNumero(
                                        "ESPECTACULO_ALICUOTA", clase);
                        sellados.exigirNumero(LlavesDelConjunto.UIT, null);
                        sellados.exigirNumero(
                                LlavesDelConjunto.tasaDeArbitrio(servicio), sector + ":" + uso);
                    }
                }
                """;

        List<String> argumentos = primerosArgumentos(muestra);
        assertThat(argumentos).as("cinco llamadas, y la del comentario no es una").hasSize(5);
        assertThat(argumentos.stream().filter(a -> !LLAVE_DECLARADA.matcher(a).matches()))
                .as("el literal, la constante local y el literal partido en varias lineas")
                .containsExactly(
                        "\"ALCABALA_ALICUOTA\"", "ALICUOTA_ALCABALA", "\"ESPECTACULO_ALICUOTA\"");
    }

    // ---------------------------------------------------------------- recorrido

    /** El primer argumento de cada {@code .exigirNumero(}, sin comentarios y en una linea. */
    static List<String> primerosArgumentos(String fuente) {
        String codigo = sinComentarios(fuente);
        List<String> argumentos = new ArrayList<>();
        Matcher llamada = LLAMADA.matcher(codigo);
        while (llamada.find()) {
            argumentos.add(primerArgumento(codigo, llamada.end()));
        }
        return argumentos;
    }

    /** Hasta la primera coma a profundidad cero, respetando parentesis y literales. */
    private static String primerArgumento(String codigo, int desde) {
        int profundidad = 0;
        boolean enCadena = false;
        StringBuilder argumento = new StringBuilder();
        for (int i = desde; i < codigo.length(); i++) {
            char c = codigo.charAt(i);
            if (enCadena) {
                argumento.append(c);
                if (c == '\\' && i + 1 < codigo.length()) {
                    argumento.append(codigo.charAt(++i));
                } else if (c == '"') {
                    enCadena = false;
                }
                continue;
            }
            if (c == '"') {
                enCadena = true;
            } else if (c == '(') {
                profundidad++;
            } else if (c == ')') {
                if (profundidad == 0) {
                    break;
                }
                profundidad--;
            } else if (c == ',' && profundidad == 0) {
                break;
            }
            argumento.append(c);
        }
        return argumento.toString().replaceAll("\\s+", " ").strip();
    }

    /**
     * Quita los comentarios de bloque y de linea, sin tocar lo que esta dentro de un literal: una
     * cadena, un bloque de texto —el SQL de los repositorios— o un caracter como {@code '"'}, que
     * sin tratarlo abriria una cadena que no existe y desalinearia el resto del archivo.
     */
    private static String sinComentarios(String fuente) {
        StringBuilder codigo = new StringBuilder(fuente.length());
        int i = 0;
        while (i < fuente.length()) {
            char c = fuente.charAt(i);
            if (fuente.startsWith("\"\"\"", i)) {
                int fin = fuente.indexOf("\"\"\"", i + 3);
                fin = fin < 0 ? fuente.length() : fin + 3;
                codigo.append(fuente, i, fin);
                i = fin;
            } else if (c == '"' || c == '\'') {
                int fin = i + 1;
                while (fin < fuente.length() && fuente.charAt(fin) != c) {
                    fin += fuente.charAt(fin) == '\\' ? 2 : 1;
                }
                fin = Math.min(fin + 1, fuente.length());
                codigo.append(fuente, i, fin);
                i = fin;
            } else if (fuente.startsWith("/*", i)) {
                int fin = fuente.indexOf("*/", i + 2);
                i = fin < 0 ? fuente.length() : fin + 2;
                codigo.append(' ');
            } else if (fuente.startsWith("//", i)) {
                int fin = fuente.indexOf('\n', i);
                i = fin < 0 ? fuente.length() : fin;
            } else {
                codigo.append(c);
                i++;
            }
        }
        return codigo.toString();
    }

    private static List<Path> fuentesDeProduccion() {
        Path backend = RaizDelRepositorio.ruta().resolve("backend");
        try (Stream<Path> modulos = Files.list(backend)) {
            return modulos.map(modulo -> modulo.resolve("src/main/java"))
                    .filter(Files::isDirectory)
                    .flatMap(ExigirNumeroSoloConLlavesDeclaradasTest::archivosJava)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Stream<Path> archivosJava(Path raiz) {
        try (Stream<Path> todos = Files.walk(raiz)) {
            return todos.filter(archivo -> archivo.toString().endsWith(".java")).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relativa(Path archivo) {
        return RaizDelRepositorio.ruta()
                .resolve("backend")
                .relativize(archivo)
                .toString()
                .replace('\\', '/');
    }

    private static String leer(Path archivo) {
        try {
            return Files.readString(archivo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
