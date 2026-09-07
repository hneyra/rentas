package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #30 — el censo de las escrituras, y las dos formas de contestar mal a una observacion que falta.
 *
 * <h2>Por que el arreglo va en el sitio comun y no operacion por operacion</h2>
 *
 * <p>La regla 10 dice que <i>toda</i> modificacion de datos exige observacion, asi que el defecto
 * que midio #30 —{@code Observacion.de(null)} reventando con un {@code NullPointerException} que el
 * borde traduce a <b>500 con identificador de incidencia</b>— no era de una operacion: estaba
 * potencialmente en cada una de las escrituras del sistema. Lo que decide el estado HTTP no es el
 * ayudante que la traiga sino <b>el constructor por el que pasan todas</b>, y por eso el arreglo
 * vive ahi y esta prueba mide el censo en vez de una operacion.
 *
 * <p><b>El censo medido antes de tocar nada</b> (2026-09-07, sobre {@code src/main}): 92 escrituras
 * {@code POST}/{@code PUT}/{@code PATCH}, de ellas 91 en la capa web; 30 caminos por los que la
 * observacion del cuerpo llega al constructor; y de esos 30:
 *
 * <ul>
 *   <li><b>25</b> rechazaban el nulo antes del constructor y contestaban 422 nombrando el campo;
 *   <li><b>5</b> —los cinco de {@code licencias}— lo convertian en {@code ""} con un {@code texto
 *       == null ? "" : texto}, asi que un campo que <i>falta</i> se contestaba como uno
 *       <i>demasiado corto</i>: 422, si, pero diciendo «al menos 5 caracteres» a quien no habia
 *       escrito ninguno. Son <b>17 escrituras</b>;
 *   <li><b>3</b> llamaban al constructor con el campo crudo y no tenian ayudante ninguno —{@code
 *       PUT /seguridad/sesion/ejercicio}, {@code PUT /seguridad/usuarios/&#123;id&#125;/clave} y
 *       {@code POST /seguridad/grupos/&#123;grupo&#125;/miembros}—: esas son las que contestaban
 *       <b>500</b>, y la primera es la que el issue midio contra la instalacion.
 * </ul>
 *
 * <p>Con el arreglo en el constructor, las tres formas acaban en la misma respuesta y el censo deja
 * de decidir nada. Lo que esta prueba conserva es lo que si puede volver: que alguien vuelva a
 * tapar el nulo antes de que llegue.
 *
 * <p><b>Y lo que se midio y NO hubo que arreglar</b>: los otros 20 objetos de valor de {@code
 * dominio-compartido} que rechazan un nulo con {@code Objects.requireNonNull} —{@code Dinero},
 * {@code AreaM2}, {@code Placa}…— no se construyen en ninguna parte de la capa web con un campo
 * crudo de la peticion: todos pasan por un {@code exigir(...)} o por un ayudante que guarda el
 * nulo. La observacion era la unica, y se entiende por que: es el unico campo que <b>todas</b> las
 * escrituras tienen, o sea el unico que nadie sintio como suyo.
 */
@DisplayName("#30 — ninguna escritura contesta 5xx porque falte la observacion")
class NingunaEscrituraSeRompePorLaObservacionTest {

    private static final Pattern ESCRITURA =
            Pattern.compile("@(?:PostMapping|PutMapping|PatchMapping)");

    /** El centinela que convertia «falta» en «es corta». */
    private static final Pattern CENTINELA =
            Pattern.compile("Observacion\\.de\\(\\s*\\w+\\s*==\\s*null\\s*\\?\\s*\"\"");

    /**
     * El nulo llega al constructor como lo que es, y sale como 422 nombrando el campo.
     *
     * <p>Se afirma <b>el tipo y el mensaje</b>: el tipo decide el estado —el borde traduce {@link
     * IllegalArgumentException} a 422 y no traduce {@link NullPointerException} a nada— y el
     * mensaje es lo unico que le dice al cliente cual de los campos le falta.
     */
    @Test
    @DisplayName("el sitio comun rechaza el nulo como un campo que falta, no como una averia")
    void elSitioComunRechazaElNulo() {
        assertThatThrownBy(() -> Observacion.de(null))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(NullPointerException.class)
                .hasMessageContaining("'observacion'");
    }

    /**
     * Nadie tapa el nulo antes de que llegue al sitio comun.
     *
     * <p>Es la unica forma en que el arreglo de #30 se puede deshacer sin tocar {@link
     * Observacion}: un {@code Observacion.de(texto == null ? "" : texto)} deja la respuesta en 422
     * —asi que ninguna prueba de estado se pone roja— y le dice al cliente que su observacion es
     * corta cuando lo que pasa es que no la mando. Es la respuesta plausible y equivocada, que es
     * la que no se distingue de la correcta.
     */
    @Test
    @DisplayName("ningun ayudante convierte una observacion que falta en una vacia")
    void ningunAyudanteTapaElNulo() {
        List<String> tapan = new ArrayList<>();
        for (Path archivo : fuentesDeProduccion()) {
            String texto = leer(archivo);
            Matcher m = CENTINELA.matcher(texto);
            while (m.find()) {
                tapan.add(nombre(archivo) + ": " + m.group().replace('\n', ' '));
            }
        }
        assertThat(tapan)
                .as(
                        "un campo que falta y uno que no vale son dos errores distintos, y el"
                                + " cliente los arregla distinto: el primero lo manda, el segundo lo"
                                + " reescribe")
                .isEmpty();
    }

    /**
     * El contraste, sin el cual lo de arriba se cumpliria sobre el conjunto vacio.
     *
     * <p>Si el recorrido dejara de encontrar las fuentes —porque cambia la disposicion del
     * repositorio o porque {@link RaizDelRepositorio} se ancla en un archivo que se movio— la
     * comprobacion de arriba pasaria en verde sin haber leido una linea. Se cuenta lo que hay, y se
     * exige que sea el orden de magnitud que este censo midio.
     */
    @Test
    @DisplayName("y el recorrido encuentra las escrituras: sin sujeto no se ha medido nada")
    void elRecorridoEncuentraLasEscrituras() {
        List<Path> fuentes = fuentesDeProduccion();
        int escrituras = 0;
        for (Path archivo : fuentes) {
            Matcher m = ESCRITURA.matcher(leer(archivo));
            while (m.find()) {
                escrituras++;
            }
        }
        assertThat(fuentes)
                .as("si esto sale vacio, lo de arriba se cumple sobre la nada")
                .hasSizeGreaterThan(300);
        assertThat(escrituras)
                .as(
                        "el censo de #30 midio 92 escrituras POST/PUT/PATCH; si esto cae a cero, el"
                                + " recorrido dejo de ver la capa web")
                .isGreaterThan(50);
    }

    // ------------------------------------------------------------------

    private static List<Path> fuentesDeProduccion() {
        Path backend = RaizDelRepositorio.ruta().resolve("backend");
        try (Stream<Path> arbol = Files.walk(backend)) {
            return arbol.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/"))
                    .toList();
        } catch (IOException fallo) {
            throw new UncheckedIOException(fallo);
        }
    }

    private static String leer(Path archivo) {
        try {
            return Files.readString(archivo);
        } catch (IOException fallo) {
            throw new UncheckedIOException(fallo);
        }
    }

    private static String nombre(Path archivo) {
        String ruta = archivo.toString().replace('\\', '/');
        int i = ruta.indexOf("/java/");
        return i < 0 ? ruta : ruta.substring(i + "/java/".length());
    }
}
