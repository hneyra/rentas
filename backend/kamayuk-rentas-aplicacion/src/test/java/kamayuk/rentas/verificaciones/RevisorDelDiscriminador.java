package kamayuk.rentas.verificaciones;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.comun.verificaciones.RevisorDeCodigoFuente;

/**
 * La guarda de #691: un {@code catch} que convierte «falta publicar una cifra normativa» en una
 * respuesta de error tiene que hacerlo con {@link kamayuk.rentas.parametros.FaltaPublicar}, o sea
 * <b>con el discriminador dentro</b>. Sea 422 o 404 (#723).
 *
 * <h2>Por que hace falta una guarda y no basta el tipo</h2>
 *
 * <p>#604 puso el miembro {@code parametroQueFalta} y lo cableo en tres capturas de tesoreria. Un
 * ano despues seguian sin el las veintidos restantes, en ocho modulos, y <b>nada lo decia</b>:
 * ausente, el miembro significa «es un campo de la peticion», asi que un 422 mudo es indistinguible
 * de uno correcto. Es la clase de hueco que se vuelve a abrir con el modulo siguiente que se
 * conecte —el propio #604 documento en {@code PredialController} que lo dejaba abierto a
 * proposito—, y por eso lo que cierra el issue no es arreglar los veintidos sitios sino que el
 * veintitres no pueda nacer mudo.
 *
 * <h2>Como se computa la familia</h2>
 *
 * <p><b>Del codigo fuente, no de una lista escrita a mano</b>, igual que el escaner de la regla 5:
 * es familia toda excepcion cuya declaracion diga {@code implements ParametroSinPublicar}. Anadir
 * una excepcion de «falta publicar» la mete en la guarda sin tocar esta clase; y una excepcion que
 * NO declare la interfaz no puede traducirse con el ayudante, porque su tipo no compila ahi.
 *
 * <p>A esa lista se suma <b>una</b> excepcion nombrada, {@link #LA_DEL_DOMINIO_PURO}: {@code
 * PoliticasDeRedondeo.PuntoSinPolitica} vive en {@code kamayuk-rentas-dominio-compartido} y no
 * puede declarar la interfaz —la interfaz vive en {@code kamayuk-rentas-parametros}, que depende
 * del dominio y no al reves— ni sabe de que ejercicio salieron sus politicas (regla 7). Es la
 * unica, y es lo que la sobrecarga de {@code FaltaPublicar} existe para traducir.
 *
 * <h2>Que NO mira</h2>
 *
 * <p>Solo los {@code catch} que <b>componen un {@code ProblemaDeNegocio}</b>. Uno que devuelva un
 * valor —el resumen anual de licencias cuenta lo que puede contar y dice por que falta la cifra—,
 * que la vuelva a lanzar o que la envuelva en otra excepcion de la familia se dejan pasar: ahi no
 * hay ninguna respuesta que quedarse sin miembro.
 *
 * <p><b>El codigo de estado dejo de ser el criterio en #723.</b> #691 lo escribio literal —«todo
 * {@code catch} que hoy traduce estas excepciones a un 422»— y con eso las tres lecturas de cuadro
 * de catastro, que contestan {@code NO_ENCONTRADO} porque piden una tabla publicada y no una
 * operacion, quedaban fuera de la guarda: eran las <b>unicas tres</b> del backend con esa forma, y
 * las tres estaban mudas. Lo que le falta a esa respuesta no depende del numero —el miembro es lo
 * unico que un programa lee—, asi que la guarda mira si se compone la respuesta y no con que
 * codigo. Cual toca lo sigue decidiendo quien traduce, con los dos metodos de {@code
 * FaltaPublicar}.
 *
 * <p>Y mira el cuerpo <b>sin comentarios</b>. Con ellos, un comentario que mencionara el ayudante
 * dejaria pasar un catch mudo, que es exactamente el modo de fallo que la guarda existe para
 * impedir.
 */
public final class RevisorDelDiscriminador {

    /**
     * La unica excepcion de la familia que no puede declarar {@code ParametroSinPublicar}.
     *
     * <p>Esta escrita a mano y con nombre propio porque su motivo tambien lo es: {@code
     * PuntoSinPolitica} es dominio puro. Cualquier otra que quiera entrar en la familia lo hace
     * declarando la interfaz, que es lo que la hace traducible.
     */
    public static final String LA_DEL_DOMINIO_PURO = "PuntoSinPolitica";

    /** Lo que tiene que aparecer en el cuerpo del {@code catch}: el unico traductor. */
    public static final String EL_TRADUCTOR = "FaltaPublicar";

    /**
     * Lo que hace del {@code catch} una respuesta de error, y no otra cosa.
     *
     * <p>Hasta #723 el criterio era «produce un {@code CodigoDeError.VALIDACION}», literal del
     * enunciado de #691. Con el, las <b>tres</b> lecturas de cuadro de catastro —las unicas del
     * backend que traducian esta familia a otro codigo— se quedaban fuera de la guarda por
     * contestar 404, de modo que la primera que naciera muda no la veria nadie. El hecho que
     * importa no es el numero sino que se este componiendo la respuesta: lo que un programa lee es
     * el miembro, y el miembro falta igual en un 404 que en un 422.
     */
    public static final String EL_PROBLEMA = "ProblemaDeNegocio";

    private static final Pattern DECLARACION =
            Pattern.compile(
                    "class\\s+(\\w+)\\s+extends\\s+RuntimeException\\s+implements\\s+ParametroSinPublicar\\b");

    private static final Pattern CATCH = Pattern.compile("\\bcatch\\s*\\(");

    private static final Pattern TIPO_DE_LA_CLASE =
            Pattern.compile("\\b(?:class|record|enum|interface)\\s+(\\w+)");

    /**
     * Una declaracion de metodo: un nombre, sus parametros y la llave que abre su cuerpo.
     *
     * <p>Sirve para poder <b>nombrar el metodo</b> en el hallazgo. «El catch de la linea 400» no le
     * dice a nadie que endpoint se quedo mudo.
     */
    private static final Pattern METODO =
            Pattern.compile("(?<!@)\\b(\\w+)\\s*\\([^;{}]*\\)\\s*(?:throws\\s[\\w\\s,.]+)?\\{");

    private static final Set<String> NO_SON_METODOS =
            Set.of(
                    "if",
                    "for",
                    "while",
                    "switch",
                    "catch",
                    "try",
                    "synchronized",
                    "do",
                    "else",
                    "return",
                    "new",
                    "case");

    private RevisorDelDiscriminador() {}

    /**
     * Las excepciones de la familia, leidas de las fuentes que se le den.
     *
     * @param fuentes nombre del archivo -> contenido
     */
    public static Set<String> familiaSegunLasFuentes(Map<String, String> fuentes) {
        Set<String> familia = new LinkedHashSet<>();
        familia.add(LA_DEL_DOMINIO_PURO);
        for (String contenido : fuentes.values()) {
            Matcher declara = DECLARACION.matcher(contenido);
            while (declara.find()) {
                familia.add(declara.group(1));
            }
        }
        return Set.copyOf(familia);
    }

    /** Los {@code catch} de este archivo que producen un 422 sin pasar por el traductor. */
    public static List<RevisorDeCodigoFuente.Hallazgo> revisar(
            String archivo, String contenido, Set<String> familia) {

        List<RevisorDeCodigoFuente.Hallazgo> hallazgos = new ArrayList<>();
        String clase = nombreDeLaClase(archivo, contenido);
        Matcher captura = CATCH.matcher(contenido);
        while (captura.find()) {
            int abre = captura.end() - 1;
            int cierra = cierreDe(contenido, abre, '(', ')');
            if (cierra < 0) {
                continue;
            }
            String clausula = contenido.substring(abre + 1, cierra);
            if (nombradas(clausula, familia).isEmpty()) {
                continue;
            }
            int llave = contenido.indexOf('{', cierra);
            if (llave < 0) {
                continue;
            }
            int fin = cierreDe(contenido, llave, '{', '}');
            String cuerpo = sinComentarios(contenido.substring(llave, fin < 0 ? llave + 1 : fin));
            if (!cuerpo.contains(EL_PROBLEMA)) {
                continue;
            }
            if (cuerpo.contains(EL_TRADUCTOR)) {
                continue;
            }
            hallazgos.add(
                    new RevisorDeCodigoFuente.Hallazgo(
                            archivo,
                            "#691, #723 — una respuesta de «falta publicar» sin su"
                                    + " discriminador: traducela con "
                                    + EL_TRADUCTOR
                                    + ".problema(...) si es un 422, o con "
                                    + EL_TRADUCTOR
                                    + ".noEncontrado(...) si es un 404",
                            clase
                                    + "."
                                    + metodoQueContiene(contenido, captura.start())
                                    + " captura "
                                    + String.join(", ", nombradas(clausula, familia))));
        }
        return List.copyOf(hallazgos);
    }

    private static List<String> nombradas(String clausula, Set<String> familia) {
        List<String> nombradas = new ArrayList<>();
        for (String nombre : familia) {
            if (Pattern.compile("\\b" + Pattern.quote(nombre) + "\\b").matcher(clausula).find()) {
                nombradas.add(nombre);
            }
        }
        nombradas.sort(String::compareTo);
        return nombradas;
    }

    private static String nombreDeLaClase(String archivo, String contenido) {
        Matcher tipo = TIPO_DE_LA_CLASE.matcher(contenido);
        if (tipo.find()) {
            return tipo.group(1);
        }
        return archivo.replace(".java", "");
    }

    /**
     * El metodo que envuelve al {@code catch}, para poder nombrarlo en el hallazgo.
     *
     * <p>«El catch de la linea 400» no le dice a nadie que endpoint se quedo mudo.
     *
     * <p><b>Y una anotacion no es un metodo</b>: sin el {@code (?<!@)} del patron, la primera
     * medicion de la guarda nombro {@code PredialController.PostMapping} en vez de {@code
     * calcularIndividual}. {@code @PostMapping("/calculo-individual")} casa con el patron y <b>se
     * come</b> la firma de su propio metodo hasta la llave del cuerpo, asi que descartarla despues
     * de casarla no basta: hay que impedir que el patron empiece ahi.
     */
    private static String metodoQueContiene(String contenido, int posicion) {
        String encontrado = "‹metodo desconocido›";
        Matcher metodo = METODO.matcher(contenido);
        while (metodo.find() && metodo.start() < posicion) {
            if (NO_SON_METODOS.contains(metodo.group(1))) {
                continue;
            }
            encontrado = metodo.group(1);
        }
        return encontrado;
    }

    private static int cierreDe(String texto, int desde, char abre, char cierra) {
        int profundidad = 0;
        for (int i = desde; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c == abre) {
                profundidad++;
            } else if (c == cierra) {
                profundidad--;
                if (profundidad == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Sin comentarios de bloque ni de linea: lo que el compilador ve, no lo que se explica. */
    private static String sinComentarios(String texto) {
        String sinBloque = texto.replaceAll("(?s)/\\*.*?\\*/", " ");
        return sinBloque.replaceAll("(?m)//[^\n]*", " ");
    }
}
