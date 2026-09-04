package kamayuk.rentas.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Un parametro de consulta que la operacion no sabe leer <b>no se ignora</b>: se rechaza con {@code
 * 422} nombrandolo (#539).
 *
 * <h2>El defecto que cierra</h2>
 *
 * <p>{@code GET /rentas/contribuyentes} acota por {@code dNI} —asi lo declara el contrato, derivado
 * del rotulo «D.N.I.» del prototipo (#312)—. Escribirlo {@code dni} no fallaba: Spring ignora el
 * parametro que ningun argumento reclama, la consulta sale <b>sin acotar</b> y el endpoint devuelve
 * el padron entero con {@code 200}. Medido contra Catacaos: {@code ?dNI=29614026} devuelve 1 fila y
 * {@code ?dni=29614026} devuelve <b>10 603</b>.
 *
 * <p>El sintoma no se parece a la causa —quien busca un DNI y recibe diez mil filas piensa que el
 * padron esta mal, no que el parametro se llama de otra forma— y es un filtro que <b>abre
 * datos</b>: la peticion pedia una persona y devolvio el padron completo de la municipalidad.
 *
 * <p>Y no son dos nombres: el contrato declara <b>once</b> con la forma «una minuscula suelta y
 * luego mayuscula» —{@code dNI}, {@code rUC}, {@code nExpediente}, {@code nDePrograma}…—, que es lo
 * que produce pasar a camelCase un rotulo como «D.N.I.» o «Nº de expediente». Cada uno es una
 * errata esperando a pasar, y con la errata la respuesta es el listado entero.
 *
 * <h2>Por que un interceptor y no una comprobacion por operacion</h2>
 *
 * <p>Por lo mismo que {@code GuardiaDeAcceso}: una comprobacion que hay que escribir en doscientos
 * sitios falta en alguno, y el que falta no se descubre revisando. Aqui ademas no habria donde
 * escribirla —el parametro que sobra no llega a ningun argumento del metodo, asi que el controlador
 * no puede ni verlo—.
 *
 * <h2>Que se admite, y por que exactamente eso</h2>
 *
 * <p>Lo que el <b>metodo</b> sabe leer: sus {@code @RequestParam}, los componentes del {@code
 * record} que Spring le compone de la consulta —{@link ParametrosDePaginacion} en las cien lecturas
 * paginadas— y los nombres que su mapeo exige con {@code params = "..."}. Nada mas, y en particular
 * <b>no</b> los campos del cuerpo: un dato que la operacion lee del {@code @RequestBody} y llega
 * por la URL no hace nada, que es el mismo defecto por otro conducto.
 *
 * <p><b>Esto convierte {@code @RequestParam} en una anotacion que sostiene algo</b>, y es un cambio
 * respecto de lo que #431 midio: alli se comprobo que quitarsela a un {@code String} no cambiaba
 * nada —Spring lo enlaza por su nombre igual—, o sea que la anotacion documentaba y no era lo que
 * hacia viajar el filtro. Desde aqui si lo es: un parametro sin anotar no aparece en el conjunto
 * que esta guarda enumera y se rechazaria aunque el metodo lo lea. Los doscientos handlers
 * publicados lo anotan, y para que siga siendo asi {@code ParametrosDeLaConsultaTest} censa la
 * forma de cada parametro y se pone rojo con la primera que la guarda no sepa leer.
 *
 * <p>Con una excepcion declarada, {@link #DIALECTO_DE_LA_PAGINACION}: los cuatro nombres de la
 * paginacion se admiten <b>siempre</b>, tambien en la operacion que no pagina. Son el unico
 * dialecto de las 134 pantallas, el contrato los declara en toda lectura con tabla, y hay tres
 * operaciones cuyo contrato los publica y cuyo controlador no los lee: sin esta excepcion, pedir la
 * pagina siguiente de un listado se contestaria «parametro desconocido: pagina», que es cambiar un
 * defecto por otro. Que una de ellas no pagine es un desajuste distinto, y lo cuenta {@code
 * ParametrosDeLaConsultaTest}.
 *
 * <h2>Lo que costo, medido antes de decidirlo</h2>
 *
 * <p>Esto cambia el borde de <b>todas</b> las lecturas con filtros, asi que no se decide de paso.
 * El contrato declara 146 filtros en 58 operaciones que ningun controlador lee (#544, ya con los
 * siete que #546 retiro): con esta guarda, mandarlos deja de devolver el listado entero y pasa a
 * contestar 422. Se cruzo el censo contra lo que el unico cliente manda de verdad —las doce
 * fachadas de {@code frontend/src/api}, ruta por ruta y campo por campo—: de esos 146, el frontend
 * <b>no manda ninguno</b>. Los dos unicos parametros que manda y nadie lee son {@code
 * contribuyente} y {@code fechaDeConsulta} de {@code GET /fiscalizacion/omisos}, que <b>el contrato
 * tampoco declara</b> — o sea dos filtros que la pantalla dibuja, que hoy no acotan nada y que
 * devuelven el padron de omisos entero. Con la guarda, esa pantalla dice cual de sus filtros no
 * existe en vez de ensenar una lista que no es la que se pidio.
 *
 * <h2>De donde se leen los nombres que llegaron</h2>
 *
 * <p>De {@link HttpServletRequest#getParameterNames()}, que es <b>exactamente</b> el conjunto del
 * que Spring resuelve un {@code @RequestParam}: comparar contra otra cosa dejaria a la guarda
 * midiendo un conjunto y al enlace resolviendo de otro.
 *
 * <p>La alternativa era leer la cadena de consulta cruda, y se descarto <b>despues de medirla</b>:
 * {@code MockMvcRequestBuilders.param(...)} rellena el mapa de parametros y <b>no</b> la cadena, de
 * modo que con esa version las siete pruebas de esta guarda pasaban en verde sin que rechazara nada
 * — y una prueba futura escrita con {@code .param("dni", …)} habria concluido que la guarda no
 * funciona. Un contenedor de verdad llena las dos; el de las pruebas, una.
 *
 * <p>El mapa de parametros mezcla la consulta con el cuerpo cuando la peticion viene en formulario.
 * Aqui no ocurre: los cuerpos publicados son {@code record} en JSON, y una peticion {@code
 * application/x-www-form-urlencoded} contra un {@code @RequestBody} ya no pasa de la negociacion de
 * contenido. Y leer los nombres no consume el cuerpo salvo en ese mismo caso, que no existe.
 *
 * <h2>Y por que el orden importa</h2>
 *
 * <p>Este guardia corre <b>despues</b> de {@code GuardiaDeAcceso}: el mensaje nombra los parametros
 * que la operacion admite, y eso es informacion sobre la API que no tiene por que recibir quien no
 * puede llamarla. Primero se decide si puede entrar; luego, si lo que trae se puede leer.
 */
public class GuardiaDeParametros implements HandlerInterceptor {

    /**
     * Los cuatro nombres de {@link ParametrosDePaginacion}, admitidos en toda operacion.
     *
     * <p>Se escriben aqui y no se derivan del record a proposito: derivarlos haria que anadirle un
     * componente a la paginacion ensanchara en silencio lo que toda la API acepta.
     */
    public static final Set<String> DIALECTO_DE_LA_PAGINACION =
            Set.of("pagina", "tamano", "ordenarPor", "direccion");

    /**
     * Lo que ya se calculo para cada metodo.
     *
     * <p>El conjunto sale de la reflexion sobre la firma, que no cambia mientras la aplicacion
     * vive.
     */
    private final Map<Method, Set<String>> admitidos = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(
            HttpServletRequest peticion, HttpServletResponse respuesta, Object manejador) {

        if (!(manejador instanceof HandlerMethod metodo)) {
            return true;
        }

        Set<String> admite =
                admitidos.computeIfAbsent(metodo.getMethod(), m -> admitidosDe(metodo));
        Set<String> desconocidos = new TreeSet<>(Collections.list(peticion.getParameterNames()));
        desconocidos.removeAll(admite);
        if (desconocidos.isEmpty()) {
            return true;
        }

        Set<String> ordenados = new TreeSet<>(admite);
        throw new ProblemaDeNegocio(
                CodigoDeError.VALIDACION,
                (desconocidos.size() == 1 ? "Parametro desconocido: " : "Parametros desconocidos: ")
                        + entreComillas(desconocidos),
                List.of(
                        ordenados.isEmpty()
                                ? "Esta operacion no admite ningun parametro de consulta"
                                : "Se admiten: " + String.join(", ", ordenados)));
    }

    /** Lo que el metodo sabe leer de la consulta, mas el dialecto de la paginacion. */
    private static Set<String> admitidosDe(HandlerMethod metodo) {
        Set<String> nombres = new LinkedHashSet<>(DIALECTO_DE_LA_PAGINACION);
        for (Parameter parametro : metodo.getMethod().getParameters()) {
            RequestParam anotacion = parametro.getAnnotation(RequestParam.class);
            if (anotacion != null) {
                String declarado =
                        anotacion.name().isEmpty() ? anotacion.value() : anotacion.name();
                nombres.add(declarado.isEmpty() ? parametro.getName() : declarado);
                continue;
            }
            // Un parametro SIN anotar cuyo tipo es un record lo compone Spring de la consulta,
            // componente a componente. Es ParametrosDePaginacion, y es como llegan `pagina` y
            // `tamano` a las cien lecturas paginadas.
            if (parametro.getAnnotations().length == 0 && parametro.getType().isRecord()) {
                for (RecordComponent componente : parametro.getType().getRecordComponents()) {
                    nombres.add(componente.getName());
                }
            }
        }
        // El mapeo puede exigir un parametro para elegir entre dos handlers de la misma ruta
        // —`params = "formato"`, el listado en JSON y el mismo listado como documento—. Ese nombre
        // es parte de la operacion aunque el metodo que gana no lo declare.
        //
        // Hoy los diecisiete handlers de documento declaran las dos cosas, asi que sobre ellos esta
        // rama no anade nada: se queda porque describe lo que hace SPRING, no lo que hoy escribimos
        // —sin ella, un handler elegido por la presencia de un parametro que no lee se volveria
        // inalcanzable— y porque se puede medir, que es lo que hace la sonda de
        // GuardiaDeParametrosTest, cuyo handler de documento no lo declara a proposito.
        //
        // Se miran las dos, la del metodo y la de la CLASE, porque Spring combina las dos
        // condiciones: un `params` declarado en el controlador rige para todos sus handlers, y sin
        // leerlo la guarda rechazaria el parametro con el que ese controlador se elige a si mismo.
        // Hoy ninguno lo declara asi; la sonda de la prueba, si.
        anadirCondiciones(nombres, metodo.getMethod());
        anadirCondiciones(nombres, metodo.getBeanType());
        return nombres;
    }

    private static void anadirCondiciones(Set<String> nombres, AnnotatedElement donde) {
        RequestMapping mapeo =
                AnnotatedElementUtils.findMergedAnnotation(donde, RequestMapping.class);
        if (mapeo == null) {
            return;
        }
        for (String condicion : mapeo.params()) {
            String nombre = nombreDeLaCondicion(condicion);
            if (!nombre.isEmpty()) {
                nombres.add(nombre);
            }
        }
    }

    /** {@code "formato"}, {@code "!formato"} y {@code "formato=PDF"} nombran el mismo parametro. */
    private static String nombreDeLaCondicion(String condicion) {
        String sinNegacion = condicion.startsWith("!") ? condicion.substring(1) : condicion;
        int igual = sinNegacion.indexOf('=');
        String nombre = igual < 0 ? sinNegacion : sinNegacion.substring(0, igual);
        return nombre.endsWith("!") ? nombre.substring(0, nombre.length() - 1) : nombre;
    }

    private static String entreComillas(Set<String> nombres) {
        return nombres.stream()
                .map(nombre -> "'" + nombre + "'")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
