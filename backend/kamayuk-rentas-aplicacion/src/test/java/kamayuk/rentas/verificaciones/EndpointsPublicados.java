package kamayuk.rentas.verificaciones;

import com.tngtech.archunit.core.domain.JavaClass;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import kamayuk.comun.verificaciones.ReglasDeArquitectura;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los endpoints que el backend publica, leidos de los propios controladores.
 *
 * <p>Lo miran dos pruebas y por motivos distintos: {@link ContratoDeApiTest} compara <b>que
 * rutas</b> hay contra el contrato, y {@link FormasDeLaApiTest} compara <b>que devuelve</b> cada
 * una. Vive aparte para que las dos lean el mismo recorrido: dos recorridos escritos por separado
 * empiezan iguales y acaban discrepando en el caso raro —un metodo sin verbo, dos mapeos sobre la
 * misma ruta—, y entonces una de las dos pruebas mide algo que la otra no ve.
 */
final class EndpointsPublicados {

    /** Raiz declarada en {@code servers.url} del contrato. */
    static final String RAIZ = "/rentas/api/v1";

    private EndpointsPublicados() {}

    /**
     * Cada operacion publicada —{@code «VERBO /ruta»}— con el metodo que la sirve.
     *
     * <p><b>Cuando dos metodos publican la misma operacion, gana el que no acota por parametro.</b>
     * Pasa una vez: {@code ReporteController} sirve la ficha del contribuyente en JSON y, con
     * {@code params = "formato"}, como archivo. La forma de la respuesta que importa es la primera
     * —la del archivo son bytes—, y quedarse con la que llegara antes por el orden en que el JDK
     * devuelve los metodos declarados haria que esta prueba cambiara de resultado sin que nadie
     * tocara nada.
     */
    static Map<String, Method> porOperacion() {
        Map<String, Method> publicadas = new TreeMap<>();
        for (JavaClass clase : ReglasDeArquitectura.clasesDeProduccion()) {
            Class<?> tipo = clase.reflect();
            if (!AnnotatedElementUtils.hasAnnotation(tipo, RestController.class)) {
                continue;
            }
            RequestMapping deLaClase =
                    AnnotatedElementUtils.findMergedAnnotation(tipo, RequestMapping.class);
            String base = deLaClase == null ? "" : primero(deLaClase.path());

            for (Method metodo : tipo.getDeclaredMethods()) {
                RequestMapping mapeo =
                        AnnotatedElementUtils.findMergedAnnotation(metodo, RequestMapping.class);
                if (mapeo == null) {
                    continue;
                }
                String ruta = sinRaiz(base + primero(mapeo.path()));
                for (RequestMethod verbo : verbos(mapeo)) {
                    String operacion = verbo.name() + " " + ruta;
                    Method anterior = publicadas.get(operacion);
                    if (anterior == null || acotaPorParametro(anterior)) {
                        publicadas.put(operacion, metodo);
                    }
                }
            }
        }
        return publicadas;
    }

    /** Las operaciones publicadas, sin su metodo. Es lo que compara el contrato. */
    static Set<String> operaciones() {
        return new java.util.TreeSet<>(porOperacion().keySet());
    }

    private static boolean acotaPorParametro(Method metodo) {
        RequestMapping mapeo =
                AnnotatedElementUtils.findMergedAnnotation(metodo, RequestMapping.class);
        return mapeo != null && mapeo.params().length > 0;
    }

    private static Set<RequestMethod> verbos(RequestMapping mapeo) {
        Set<RequestMethod> verbos = new LinkedHashSet<>(List.of(mapeo.method()));
        if (verbos.isEmpty()) {
            // Un mapeo sin verbo responde a todos; en el contrato eso no existe, y
            // dejarlo pasar en silencio esconderia un endpoint mal declarado.
            verbos.add(RequestMethod.GET);
        }
        return verbos;
    }

    private static String primero(String[] rutas) {
        return rutas.length == 0 ? "" : rutas[0];
    }

    private static String sinRaiz(String ruta) {
        return ruta.startsWith(RAIZ) ? ruta.substring(RAIZ.length()) : ruta;
    }
}
