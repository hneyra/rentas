package kamayuk.rentas.verificaciones;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Que codigos de error puede contestar cada operacion, leidos del codigo fuente (#732).
 *
 * <h2>Por que del fuente y no de una lista</h2>
 *
 * <p>El {@code 404} es, despues del {@code 422}, la respuesta de error mas frecuente del sistema
 * —«ese contribuyente no esta en el padron», «ese recibo no existe»— y el contrato no declaraba
 * <b>ni uno</b> en sus 225 operaciones. Declararlos a mano en el generador serian medio centenar de
 * entradas que envejecen solas: es el defecto que #312 midio cuando regenerar en limpio borraba dos
 * operaciones sin que nada lo dijera.
 *
 * <p>Asi que se derivan, como {@code FormasDeLaApiTest} deriva las formas (#400): lo que el
 * contrato declara sale de lo que el codigo hace, y CI compara en las dos direcciones.
 *
 * <h2>Como se decide, y hasta donde mira</h2>
 *
 * <p>Un {@code 404} solo puede salir de un sitio: {@code ProblemaDeNegocio} con {@link
 * kamayuk.rentas.web.CodigoDeError#NO_ENCONTRADO} —lo demas que {@code ManejadorDeErrores} traduce
 * a {@code 404} es la ruta que ningun controlador mapea, que no es de ninguna operacion—. De modo
 * que la pregunta es si esa constante es <b>alcanzable</b> desde el metodo que sirve la operacion,
 * y se responde con <b>dos saltos y ni uno mas</b>:
 *
 * <ol>
 *   <li>el cuerpo del propio metodo;
 *   <li>los metodos de <b>su misma clase</b> a los que llama —es la forma de {@code
 *       noEstaEnElPadron(codigo)}, que #622 extrajo a un ayudante privado en seis controladores—;
 *   <li>y los metodos de los <b>colaboradores inyectados</b> a los que llama, resueltos por el tipo
 *       declarado del campo. Sin este tercero se escaparia todo lo que lanza la capa de aplicacion
 *       —doce archivos hoy—, y el censo diria que no hay {@code 404} donde lo hay a diario.
 * </ol>
 *
 * <p><b>Lo que no ve, dicho para que nadie lo de por cubierto:</b> un {@code 404} que nazca a tres
 * saltos —un caso de uso que llama a otro que lo lanza— no aparece aqui. Eso produce un <b>falso
 * negativo</b>: la operacion no declara un {@code 404} que si puede contestar, que es exactamente
 * el estado del que se viene. Lo que no produce nunca es lo contrario —declarar un {@code 404}
 * imposible—, y esa es la mitad que importa: un contrato que promete de mas es peor que uno que
 * calla, porque el cliente escribe codigo para una rama que nunca llega.
 */
final class RevisorDeRespuestas {

    /** La unica forma de contestar 404 desde una operacion. */
    private static final String MARCA = "NO_ENCONTRADO";

    /** Modulos donde vive el codigo de produccion. */
    private static final String FUENTES = "src/main/java";

    private static final Map<String, String> FUENTE_POR_CLASE = new HashMap<>();

    private RevisorDeRespuestas() {}

    /** Si la operacion que sirve este metodo puede contestar {@code 404}. */
    static boolean puedeContestar404(Method metodo) {
        Class<?> controlador = metodo.getDeclaringClass();
        String fuente = fuenteDe(controlador);
        if (fuente == null) {
            return false;
        }
        String cuerpo = cuerpoDe(fuente, metodo.getName());
        if (cuerpo == null) {
            return false;
        }
        if (cuerpo.contains(MARCA)) {
            return true;
        }
        for (String ayudante : metodosQueMencionanLaMarca(fuente)) {
            if (llama(cuerpo, ayudante)) {
                return true;
            }
        }
        return colaboradorQueLaLanza(controlador, cuerpo);
    }

    /** Un colaborador inyectado cuyo metodo llamado lanza el 404. */
    private static boolean colaboradorQueLaLanza(Class<?> controlador, String cuerpo) {
        for (Field campo : controlador.getDeclaredFields()) {
            String fuenteDelColaborador = fuenteDe(campo.getType());
            if (fuenteDelColaborador == null || !fuenteDelColaborador.contains(MARCA)) {
                continue;
            }
            for (String metodo : metodosQueMencionanLaMarca(fuenteDelColaborador)) {
                if (cuerpo.contains(campo.getName() + "." + metodo + "(")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Los metodos de ese archivo cuyo cuerpo menciona la marca. */
    private static Set<String> metodosQueMencionanLaMarca(String fuente) {
        Set<String> nombres = new LinkedHashSet<>();
        for (String nombre : nombresDeMetodo(fuente)) {
            String cuerpo = cuerpoDe(fuente, nombre);
            if (cuerpo != null && cuerpo.contains(MARCA)) {
                nombres.add(nombre);
            }
        }
        return nombres;
    }

    /** Todo nombre que en ese archivo aparece como declaracion de metodo. */
    private static Set<String> nombresDeMetodo(String fuente) {
        Set<String> nombres = new LinkedHashSet<>();
        Matcher encontrado = Pattern.compile("\\b([a-z][A-Za-z0-9_]*)\\s*\\(").matcher(fuente);
        while (encontrado.find()) {
            if (esDeclaracion(fuente, encontrado.start(1), encontrado.end())) {
                nombres.add(encontrado.group(1));
            }
        }
        return nombres;
    }

    /**
     * El cuerpo de un metodo, o {@code null} si no se declara ahi.
     *
     * <p>Si hay sobrecargas se devuelven todas concatenadas: que <b>alguna</b> pueda lanzar el 404
     * basta, y distinguirlas exigiria resolver tipos, que es justo lo que un revisor de texto no
     * puede hacer sin equivocarse.
     */
    private static @Nullable String cuerpoDe(String fuente, String nombre) {
        StringBuilder cuerpos = new StringBuilder();
        Matcher encontrado =
                Pattern.compile("\\b" + Pattern.quote(nombre) + "\\s*\\(").matcher(fuente);
        while (encontrado.find()) {
            if (!esDeclaracion(fuente, encontrado.start(), encontrado.end())) {
                continue;
            }
            int llave = fuente.indexOf('{', encontrado.end());
            if (llave < 0) {
                continue;
            }
            cuerpos.append(bloque(fuente, llave));
        }
        return cuerpos.isEmpty() ? null : cuerpos.toString();
    }

    /**
     * Si esa aparicion del nombre es una declaracion y no una llamada.
     *
     * <p>Dos comprobaciones, y las dos hacen falta. Que <b>no</b> venga precedida de {@code @} ni
     * de {@code .}: una anotacion casa con el patron y se come la firma de su propio metodo —#691
     * lo midio, y el hallazgo salia diciendo «PostMapping» en vez del nombre—, y {@code x.metodo(}
     * es una llamada. Y que tras cerrar el parentesis venga una llave de apertura: una llamada
     * acaba en punto y coma, en punto o en otro parentesis.
     */
    private static boolean esDeclaracion(String fuente, int inicio, int trasElParentesis) {
        int anterior = inicio - 1;
        while (anterior >= 0 && Character.isWhitespace(fuente.charAt(anterior))) {
            anterior--;
        }
        if (anterior < 0) {
            return false;
        }
        char previo = fuente.charAt(anterior);
        if (previo == '@' || previo == '.' || previo == '(' || previo == ',') {
            return false;
        }
        int cierre = cierreDelParentesis(fuente, trasElParentesis - 1);
        if (cierre < 0) {
            return false;
        }
        int siguiente = cierre + 1;
        while (siguiente < fuente.length()
                && fuente.charAt(siguiente) != '{'
                && fuente.charAt(siguiente) != ';') {
            char actual = fuente.charAt(siguiente);
            if (!Character.isWhitespace(actual)
                    && !Character.isLetterOrDigit(actual)
                    && actual != ','
                    && actual != '.'
                    && actual != '_') {
                return false;
            }
            siguiente++;
        }
        return siguiente < fuente.length() && fuente.charAt(siguiente) == '{';
    }

    /** Si ese cuerpo llama a ese metodo de su propia clase. */
    private static boolean llama(String cuerpo, String nombre) {
        Matcher encontrado =
                Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(nombre) + "\\s*\\(")
                        .matcher(cuerpo);
        while (encontrado.find()) {
            if (!esDeclaracion(cuerpo, encontrado.start(), encontrado.end())) {
                return true;
            }
        }
        return false;
    }

    private static int cierreDelParentesis(String texto, int apertura) {
        int nivel = 0;
        for (int i = apertura; i < texto.length(); i++) {
            char actual = texto.charAt(i);
            if (actual == '(') {
                nivel++;
            } else if (actual == ')') {
                nivel--;
                if (nivel == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String bloque(String texto, int apertura) {
        int nivel = 0;
        for (int i = apertura; i < texto.length(); i++) {
            char actual = texto.charAt(i);
            if (actual == '{') {
                nivel++;
            } else if (actual == '}') {
                nivel--;
                if (nivel == 0) {
                    return texto.substring(apertura, i + 1);
                }
            }
        }
        return texto.substring(apertura);
    }

    /** El fuente de una clase del repositorio, o {@code null} si no es nuestra. */
    private static @Nullable String fuenteDe(Class<?> tipo) {
        String nombre = tipo.getName();
        if (!nombre.startsWith("kamayuk.rentas.")) {
            return null;
        }
        return FUENTE_POR_CLASE.computeIfAbsent(nombre, RevisorDeRespuestas::leer);
    }

    private static @Nullable String leer(String nombreDeLaClase) {
        String relativa = nombreDeLaClase.replace('.', '/').replace('$', '/') + ".java";
        Path raiz = RaizDelRepositorio.ruta().resolve("backend");
        try (var modulos = Files.list(raiz)) {
            List<Path> candidatos = new ArrayList<>();
            for (Path modulo : modulos.toList()) {
                candidatos.add(modulo.resolve(FUENTES).resolve(relativa));
            }
            for (Path candidato : candidatos) {
                if (Files.exists(candidato)) {
                    return Files.readString(candidato, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException fallo) {
            throw new UncheckedIOException(fallo);
        }
        return null;
    }
}
