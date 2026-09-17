package kamayuk.rentas.verificaciones;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.comun.verificaciones.ReglasDeArquitectura;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.web.ParametrosDePaginacion;
import org.jspecify.annotations.Nullable;

/**
 * Por que campos deja ordenar cada operacion, y por cual ordena si no se pide (#227).
 *
 * <h2>El hueco que cierra</h2>
 *
 * <p>El contrato publicaba <b>que existe</b> {@code ?ordenarPor=} y nada mas. Pero el orden va por
 * lista cerrada: {@link OrdenSeguro#clausula} contesta <b>422 ORDEN_NO_ADMITIDO</b> con cualquier
 * campo que no este en el {@code OrdenSeguro.sobre(...)} de su repositorio, y la lista es distinta
 * en cada uno. Un cliente que quiera ofrecer un desplegable de orden solo podia averiguarla
 * probando nombres contra el servidor — o, como hacia la interfaz de este repositorio, <b>abriendo
 * los {@code .java} del backend</b>, que es un acoplamiento al reves.
 *
 * <h2>De donde sale, y por que no se escribe a mano</h2>
 *
 * <p>Una lista copiada se queda vieja <b>en verde</b>: el dia que un repositorio le quite una
 * columna, el contrato seguiria ofreciendola y la pantalla que la ofreciera recibiria un 422. Asi
 * que no se copia: se lee del {@link OrdenSeguro} que la operacion usa de verdad.
 *
 * <p>Cual usa no esta en la firma del controlador —vive en el repositorio, dos o tres saltos mas
 * abajo—, asi que se sigue la <b>llamada</b>: desde el handler, por el bytecode, hasta el acceso al
 * campo estatico de tipo {@code OrdenSeguro}. Las llamadas a una interfaz se resuelven ademas
 * contra sus implementaciones, porque el controlador llama al puerto y quien lee la lista es el
 * adaptador.
 *
 * <p><b>Y el recorrido se cine al camino de la paginacion</b>: solo se sigue una llamada cuyo
 * metodo reciba {@link Paginacion} o {@link ParametrosDePaginacion}. Sin ese corte, {@code GET
 * /licencias/funcionamiento} alcanzaba tambien el {@code ORDEN} de {@code
 * ContribuyenteRepositoryJdbc} —porque resuelve el nombre del titular por el camino— y habria dos
 * listas donde hay una. El {@code ordenarPor} que llega por la URL viaja dentro de ese objeto y de
 * ningun otro sitio, asi que el camino del objeto <b>es</b> el camino del orden.
 *
 * <p>El orden por omision no se puede leer del bytecode: {@code ORDEN_POR_OMISION} es una constante
 * de compilacion y el compilador la incrusta, de modo que no queda ni un acceso a campo que seguir.
 * Se lee del fuente del controlador, <b>del cuerpo de ese handler</b> y no del archivo entero: tres
 * controladores publican dos operaciones con ordenes por omision distintos —{@code
 * SesionController} ordena la bitacora por {@code fecha} y los respaldos por {@code inicio}—, y una
 * lectura por archivo elegiria uno de los dos al azar.
 *
 * <h2>Lo que este recorrido NO puede ver</h2>
 *
 * <p>Que la operacion <b>rehaga</b> la paginacion antes de usarla. {@code ConsultaUnificada}
 * construye una {@code new Paginacion(pedida.pagina(), pedida.tamano(), "fecha_valor",
 * DESCENDENTE)} por cada una de sus cinco relaciones, o sea que {@code ?ordenarPor=} no ordena nada
 * ahi. Por eso una operacion que alcanza <b>varias</b> listas no publica ninguna: no hay una
 * respuesta que dar, y dar la primera seria inventarla. Quien lo declara, con su motivo, es {@code
 * ParametrosDeLaApiTest#SIN_ORDEN_PEDIBLE}.
 */
final class OrdenDeCadaOperacion {

    private OrdenDeCadaOperacion() {}

    /** Lo que una operacion admite en {@code ?ordenarPor=}, y por cual ordena sin el. */
    record Orden(String porOmision, List<String> admitidos) {}

    /**
     * Las operaciones cuyo handler recibe {@link ParametrosDePaginacion}.
     *
     * <p>Son exactamente las que publican {@code ?ordenarPor=} en el contrato: el resto no lo lee,
     * aunque {@code GuardiaDeParametros} lo admita por el dialecto.
     */
    static Set<String> lasQuePaginan() {
        Set<String> paginan = new TreeSet<>();
        for (Map.Entry<String, Method> endpoint : EndpointsPublicados.porOperacion().entrySet()) {
            for (Class<?> tipo : endpoint.getValue().getParameterTypes()) {
                if (tipo == ParametrosDePaginacion.class) {
                    paginan.add(endpoint.getKey());
                }
            }
        }
        return paginan;
    }

    /**
     * El orden de cada operacion que pagina y alcanza <b>una</b> lista blanca.
     *
     * <p>Las que alcanzan varias, o ninguna, no salen: ver el javadoc de la clase.
     */
    static Map<String, Orden> porOperacion() {
        JavaClasses clases = clases();
        Set<String> paginan = lasQuePaginan();
        Map<String, Orden> ordenes = new TreeMap<>();
        for (Map.Entry<String, Method> endpoint : EndpointsPublicados.porOperacion().entrySet()) {
            if (!paginan.contains(endpoint.getKey())) {
                continue;
            }
            Set<JavaField> alcanzados = listasAlcanzadas(endpoint.getValue(), clases);
            if (alcanzados.size() != 1) {
                continue;
            }
            List<String> admitidos =
                    new ArrayList<>(
                            new TreeSet<>(valorDe(alcanzados.iterator().next()).camposAdmitidos()));
            ordenes.put(
                    endpoint.getKey(), new Orden(ordenPorOmision(endpoint.getValue()), admitidos));
        }
        return ordenes;
    }

    /** Cuantas listas blancas alcanza esa operacion: 0, 1 o varias. Lo mira la guarda. */
    static int cuantasListasAlcanza(String operacion) {
        Method handler = EndpointsPublicados.porOperacion().get(operacion);
        if (handler == null) {
            return 0;
        }
        return listasAlcanzadas(handler, clases()).size();
    }

    /**
     * Las clases de produccion, importadas una vez.
     *
     * <p>Importarlas es lo mas caro de todo esto —son los {@code .class} de los diecisiete modulos—
     * y las dos guardas que preguntan por el orden lo harian dos veces.
     */
    private static JavaClasses clases() {
        JavaClasses ya = importadas;
        if (ya == null) {
            ya = ReglasDeArquitectura.clasesDeProduccion();
            importadas = ya;
        }
        return ya;
    }

    private static @Nullable JavaClasses importadas;

    // ------------------------------------------------------------------
    // El recorrido del bytecode
    // ------------------------------------------------------------------

    private static Set<JavaField> listasAlcanzadas(Method handler, JavaClasses clases) {
        JavaClass clase = clases.get(handler.getDeclaringClass());
        JavaMethod raiz = clase.getMethod(handler.getName(), handler.getParameterTypes());

        Set<JavaCodeUnit> vistos = new HashSet<>();
        Deque<JavaCodeUnit> pendientes = new ArrayDeque<>();
        Set<JavaField> listas = new LinkedHashSet<>();
        pendientes.add(raiz);
        vistos.add(raiz);

        while (!pendientes.isEmpty()) {
            JavaCodeUnit actual = pendientes.poll();
            // El handler no cuenta: el es quien CONSTRUYE la paginacion. Una lista blanca leida
            // aqui seria una que el controlador usa para otra cosa.
            if (!actual.equals(raiz)) {
                for (JavaFieldAccess acceso : actual.getFieldAccesses()) {
                    if (acceso.getAccessType() != JavaFieldAccess.AccessType.GET) {
                        continue;
                    }
                    acceso.getTarget()
                            .resolveMember()
                            .filter(campo -> campo.getRawType().isEquivalentTo(OrdenSeguro.class))
                            .ifPresent(listas::add);
                }
            }
            for (JavaMethodCall llamada : actual.getMethodCallsFromSelf()) {
                for (JavaMethod destino : destinosDe(llamada)) {
                    if (recibeLaPaginacion(destino) && vistos.add(destino)) {
                        pendientes.add(destino);
                    }
                }
            }
        }
        return listas;
    }

    /**
     * A donde puede ir esa llamada: el metodo declarado y, si el dueno es abstracto, los que lo
     * implementan.
     *
     * <p>Sin la segunda mitad el recorrido se para en el puerto —{@code CiiuRepository.listar}— y
     * nunca llega al adaptador, que es el unico que sabe por que columnas se puede ordenar.
     */
    private static List<JavaMethod> destinosDe(JavaMethodCall llamada) {
        List<JavaMethod> destinos = new ArrayList<>();
        llamada.getTarget().resolveMember().ifPresent(destinos::add);
        JavaClass dueno = llamada.getTargetOwner();
        if (dueno.isInterface() || dueno.getModifiers().contains(JavaModifier.ABSTRACT)) {
            String[] parametros =
                    llamada.getTarget().getRawParameterTypes().stream()
                            .map(JavaClass::getName)
                            .toArray(String[]::new);
            for (JavaClass sub : dueno.getAllSubclasses()) {
                sub.tryGetMethod(llamada.getName(), parametros).ifPresent(destinos::add);
            }
        }
        return destinos;
    }

    private static boolean recibeLaPaginacion(JavaMethod metodo) {
        for (JavaClass tipo : metodo.getRawParameterTypes()) {
            if (tipo.isEquivalentTo(Paginacion.class)
                    || tipo.isEquivalentTo(ParametrosDePaginacion.class)) {
                return true;
            }
        }
        return false;
    }

    private static OrdenSeguro valorDe(JavaField campo) {
        try {
            java.lang.reflect.Field reflejado = campo.reflect();
            reflejado.setAccessible(true);
            return (OrdenSeguro) reflejado.get(null);
        } catch (ReflectiveOperationException noSePudo) {
            throw new IllegalStateException(
                    "No se pudo leer «"
                            + campo.getOwner().getSimpleName()
                            + "."
                            + campo.getName()
                            + "»: sin su valor no hay lista blanca que publicar",
                    noSePudo);
        }
    }

    // ------------------------------------------------------------------
    // El orden por omision, leido del cuerpo del handler
    // ------------------------------------------------------------------

    /** {@code aPaginacion(ORDEN_POR_OMISION)} o {@code aPaginacion("fechaEmision")}. */
    private static final Pattern POR_OMISION =
            Pattern.compile("aPaginacion\\(\\s*(\"[^\"]*\"|[A-Za-z_][A-Za-z0-9_]*)\\s*\\)");

    /** Una llamada a un metodo del mismo archivo: el ayudante privado que compone la paginacion. */
    private static final Pattern LLAMADA = Pattern.compile("\\b([a-z][A-Za-z0-9_]*)\\s*\\(");

    private static String ordenPorOmision(Method handler) {
        Path fuente = fuenteDe(handler.getDeclaringClass());
        String texto = leer(fuente);
        String encontrado = enElCuerpoDe(texto, handler.getName(), new HashSet<>());
        if (encontrado == null) {
            throw new IllegalStateException(
                    "No se encontro el orden por omision de "
                            + handler.getDeclaringClass().getSimpleName()
                            + "#"
                            + handler.getName()
                            + ": ningun «aPaginacion(...)» en su cuerpo ni en los ayudantes de su"
                            + " archivo. Sin el, el contrato no puede decir por que campo ordena la"
                            + " operacion cuando no se manda «?ordenarPor=», y un cliente que"
                            + " anuncie un orden distinto del que traen las filas miente sobre lo"
                            + " que ensena");
        }
        return encontrado;
    }

    /**
     * El literal que {@code aPaginacion(...)} recibe dentro de ese metodo, siguiendo un salto a los
     * ayudantes del mismo archivo.
     *
     * <p>Cuatro controladores no llaman a {@code aPaginacion} en el handler sino en un metodo
     * privado —{@code paginacionDe(parametros)}, {@code paginaDelPadron(...)}—, y uno de ellos
     * <b>porque tiene que</b>: {@code ConsultaDeudaController} compone la paginacion a mano para
     * que la direccion por omision sea {@code DESCENDENTE}.
     */
    private static @Nullable String enElCuerpoDe(
            String fuente, String metodo, Set<String> visitados) {
        if (!visitados.add(metodo)) {
            return null;
        }
        String cuerpo = cuerpoDe(fuente, metodo);
        if (cuerpo == null) {
            return null;
        }
        Matcher directo = POR_OMISION.matcher(cuerpo);
        if (directo.find()) {
            String argumento = directo.group(1);
            return argumento.startsWith("\"")
                    ? argumento.substring(1, argumento.length() - 1)
                    : constanteDe(fuente, argumento);
        }
        Matcher llamada = LLAMADA.matcher(cuerpo);
        while (llamada.find()) {
            String encontrado = enElCuerpoDe(fuente, llamada.group(1), visitados);
            if (encontrado != null) {
                return encontrado;
            }
        }
        return null;
    }

    /**
     * La declaracion de ese metodo con su cuerpo, contando llaves.
     *
     * <p>Se ancla en la <b>declaracion</b> —cuatro espacios de sangria y un modificador delante—,
     * que es como el formateador escribe todo metodo de este arbol. Buscar «el nombre seguido de un
     * parentesis» encontraria antes cualquier llamada al mismo metodo.
     */
    private static @Nullable String cuerpoDe(String fuente, String metodo) {
        Matcher declaracion =
                Pattern.compile(
                                "(?m)^ {4}(?:public|protected|private|static|@)[^;{]*?\\b"
                                        + Pattern.quote(metodo)
                                        + "\\s*\\(")
                        .matcher(fuente);
        if (!declaracion.find()) {
            return null;
        }
        int abre = fuente.indexOf('{', declaracion.end());
        if (abre < 0) {
            return null;
        }
        int nivel = 0;
        for (int i = abre; i < fuente.length(); i++) {
            char caracter = fuente.charAt(i);
            if (caracter == '{') {
                nivel++;
            } else if (caracter == '}') {
                nivel--;
                if (nivel == 0) {
                    return fuente.substring(abre, i + 1);
                }
            }
        }
        return null;
    }

    private static @Nullable String constanteDe(String fuente, String nombre) {
        Matcher declarada =
                Pattern.compile("String\\s+" + Pattern.quote(nombre) + "\\s*=\\s*\"([^\"]*)\"")
                        .matcher(fuente);
        return declarada.find() ? declarada.group(1) : null;
    }

    private static String leer(Path fuente) {
        try {
            return Files.readString(fuente, StandardCharsets.UTF_8);
        } catch (IOException noSePudo) {
            throw new UncheckedIOException(noSePudo);
        }
    }

    /** El {@code .java} de esa clase dentro de {@code backend/<modulo>/src/main/java/…}. */
    private static Path fuenteDe(Class<?> clase) {
        Path modulos = RaizDelRepositorio.ruta().resolve("backend");
        String relativa = clase.getName().replace('.', '/').replaceAll("\\$.*", "") + ".java";
        try (var modulo = Files.list(modulos)) {
            for (Path candidato :
                    modulo.map(uno -> uno.resolve("src/main/java").resolve(relativa)).toList()) {
                if (Files.isRegularFile(candidato)) {
                    return candidato;
                }
            }
        } catch (IOException noSePudo) {
            throw new UncheckedIOException(noSePudo);
        }
        throw new IllegalStateException("No se encontro el fuente de " + clase.getName());
    }
}
