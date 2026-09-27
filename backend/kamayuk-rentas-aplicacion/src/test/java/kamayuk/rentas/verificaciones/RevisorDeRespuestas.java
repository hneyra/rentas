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
 *       declarado del campo. Sin este tercero se escaparia todo lo que lanza la capa de aplicacion,
 *       y el censo diria que no hay {@code 404} donde lo hay a diario.
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

    /** Modulos donde vive el codigo de produccion. */
    private static final String FUENTES = "src/main/java";

    private static final Map<String, String> FUENTE_POR_CLASE = new HashMap<>();

    private RevisorDeRespuestas() {}

    /**
     * Los estados de error que el contrato aprende del codigo, y la constante de {@code
     * CodigoDeError} que los produce (#436).
     *
     * <p>Son los que ningun otro mecanismo declara: el 404 (#732), el 409 —«ya estaba», o «el
     * estado no lo admite»— y el 503 —«reintenta»—. El 422 y el 403 los declara el generador en
     * toda operacion, el 401 el esquema de seguridad y el 501 {@code
     * escrituras-no-completables.json}.
     */
    static final Map<String, String> MARCAS =
            Map.of(
                    "NO_ENCONTRADO", "404",
                    "CONFLICTO", "409",
                    "SERVICIO_NO_DISPONIBLE", "503");

    /** Si ese metodo puede contestar 404 (#732): la pregunta de antes, sobre el censo nuevo. */
    static boolean puedeContestar404(Method metodo) {
        return estadosQuePuedeContestar(metodo).contains("404");
    }

    /**
     * Los estados de {@link #MARCAS} que ese metodo de controlador puede contestar (#436).
     *
     * <p>Una marca cuenta si aparece en el cuerpo del metodo, en un ayudante de la MISMA clase al
     * que llama —directa o indirectamente: el analisis se repite hasta que no aparecen ayudantes
     * nuevos, y no para en un salto como hasta #436, que se dejaba {@code exigirQueExista ->
     * noExiste -> NO_ENCONTRADO}— o en un metodo de un colaborador inyectado que la lanza.
     */
    static Set<String> estadosQuePuedeContestar(Method metodo) {
        Class<?> controlador = metodo.getDeclaringClass();
        String fuente = fuenteDe(controlador);
        Set<String> estados = new java.util.TreeSet<>();
        if (fuente == null) {
            return estados;
        }
        String cuerpo = cuerpoDe(fuente, metodo.getName());
        if (cuerpo == null) {
            return estados;
        }
        for (Map.Entry<String, String> marca : MARCAS.entrySet()) {
            if (cuerpo.contains(marca.getKey())) {
                estados.add(marca.getValue());
                continue;
            }
            boolean porUnAyudante = false;
            for (String ayudante : metodosQueLlevanLaMarca(fuente, marca.getKey())) {
                if (llama(cuerpo, ayudante)) {
                    porUnAyudante = true;
                    break;
                }
            }
            if (porUnAyudante || colaboradorQueLaLanza(controlador, cuerpo, marca.getKey())) {
                estados.add(marca.getValue());
            }
        }
        return estados;
    }

    /**
     * El estado de EXITO que ese metodo contesta (#436), leido del codigo y no supuesto.
     *
     * <p>Hasta #436 el generador escribia 201 para todo {@code POST}. Aqui: el
     * {@code @ResponseStatus} del metodo, o lo que su {@code ResponseEntity} pida —{@code CREATED},
     * {@code OK}, {@code ok(...)}, {@code accepted()}—, o el 200 que Spring pone sin decir nada. Un
     * metodo que contesta dos (201 o 200 segun la clave de idempotencia) publica los dos.
     */
    static Set<String> exitosDe(Method metodo) {
        Set<String> exitos = new java.util.TreeSet<>();
        org.springframework.web.bind.annotation.ResponseStatus anotada =
                metodo.getAnnotation(org.springframework.web.bind.annotation.ResponseStatus.class);
        if (anotada != null) {
            exitos.add(
                    String.valueOf(
                            anotada.value().value() != 500
                                    ? anotada.value().value()
                                    : anotada.code().value()));
            return exitos;
        }
        if (org.springframework.http.ResponseEntity.class.isAssignableFrom(
                metodo.getReturnType())) {
            String fuente = fuenteDe(metodo.getDeclaringClass());
            String cuerpo = fuente == null ? null : cuerpoDe(fuente, metodo.getName());
            if (cuerpo != null) {
                if (cuerpo.contains("HttpStatus.CREATED")) {
                    exitos.add("201");
                }
                if (cuerpo.contains("HttpStatus.OK") || cuerpo.contains("ResponseEntity.ok(")) {
                    exitos.add("200");
                }
                if (cuerpo.contains("HttpStatus.ACCEPTED")
                        || cuerpo.contains("ResponseEntity.accepted(")) {
                    exitos.add("202");
                }
                if (cuerpo.contains("HttpStatus.NO_CONTENT")
                        || cuerpo.contains("ResponseEntity.noContent(")) {
                    exitos.add("204");
                }
            }
        }
        if (exitos.isEmpty()) {
            exitos.add("200");
        }
        return exitos;
    }

    private static boolean colaboradorQueLaLanza(
            Class<?> controlador, String cuerpo, String marca) {
        for (Field campo : controlador.getDeclaredFields()) {
            String fuenteDelColaborador = fuenteDe(campo.getType());
            if (fuenteDelColaborador == null || !fuenteDelColaborador.contains(marca)) {
                continue;
            }
            for (String metodo : metodosQueLlevanLaMarca(fuenteDelColaborador, marca)) {
                if (cuerpo.contains(campo.getName() + "." + metodo + "(")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Los metodos de esa fuente que llevan la marca, directa o indirectamente: el conjunto crece
     * con los que llaman a uno que ya esta dentro, hasta que deja de crecer (#436).
     */
    private static Set<String> metodosQueLlevanLaMarca(String fuente, String marca) {
        Map<String, String> cuerpos = new java.util.LinkedHashMap<>();
        for (String nombre : nombresDeMetodo(fuente)) {
            String cuerpo = cuerpoDe(fuente, nombre);
            if (cuerpo != null) {
                cuerpos.put(nombre, cuerpo);
            }
        }
        Set<String> conLaMarca = new LinkedHashSet<>();
        cuerpos.forEach(
                (nombre, cuerpo) -> {
                    if (cuerpo.contains(marca)) {
                        conLaMarca.add(nombre);
                    }
                });
        boolean crecio = true;
        while (crecio) {
            crecio = false;
            for (Map.Entry<String, String> otro : cuerpos.entrySet()) {
                if (conLaMarca.contains(otro.getKey())) {
                    continue;
                }
                for (String yaDentro : List.copyOf(conLaMarca)) {
                    if (llama(otro.getValue(), yaDentro)) {
                        conLaMarca.add(otro.getKey());
                        crecio = true;
                        break;
                    }
                }
            }
        }
        return conLaMarca;
    }

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
                // Las muestras de este revisor viven en src/test (#436): solo ellas se leen de ahi.
                if (nombreDeLaClase.startsWith("kamayuk.rentas.verificaciones.muestras.")) {
                    candidatos.add(modulo.resolve("src/test/java").resolve(relativa));
                }
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
