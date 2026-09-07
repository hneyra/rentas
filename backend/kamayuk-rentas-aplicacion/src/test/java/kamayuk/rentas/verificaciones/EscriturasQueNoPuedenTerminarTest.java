package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import kamayuk.comun.verificaciones.ReglasDeArquitectura;
import kamayuk.comun.verificaciones.contrato.EndpointsPublicados;
import kamayuk.rentas.dominio.OperacionTodaviaNoCompletable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las escrituras que este backend publica y que hoy <b>no pueden terminar</b> (#40, AC-5 y AC-6).
 *
 * <h2>Que mide, y por que ninguna guarda anterior podia medirlo</h2>
 *
 * <p>Este sistema publica rutas de escritura cuyo camino acaba <b>siempre</b> en excepcion. Pasan
 * {@code verificarArquitectura}, pasan el contrato de la API —que las declara igual que a las
 * demas— y pasan las tres mil pruebas. Lo unico observable es que quien las llama recibe un error,
 * y hasta #40 el error era {@code ERROR_INTERNO} con numero de incidencia: indistinguible de una
 * averia.
 *
 * <p>Un contrato que las publica como a las demas <b>promete algo que ninguna pantalla puede
 * conseguir</b>. Por eso el censo de esta guarda se escribe ademas a {@code
 * docs/50-api/escrituras-no-completables.json}, que {@code generar-openapi.mjs} lee para declarar
 * su {@code 501} en el YAML: el contrato pasa a decirlo, y lo dice <b>derivado del codigo</b> y no
 * escrito a mano, que es lo que #312 midio que envejece solo.
 *
 * <h2>La lista solo baja</h2>
 *
 * <p>Es el patron de {@code busquedasDeTextoLibreConMotivo()} de T-0 y de {@code
 * PuertosSinConsumidorTest}: quien queda dentro esta con su motivo escrito y con la <b>dependencia
 * que lo bloquea nombrada</b>, no con un «pendiente». Quitar una entrada cuya clase sigue lanzando
 * pone esto rojo nombrandola; anadir un muñon nuevo sin declararlo, tambien.
 *
 * <h2>Como se cuenta, y por que por BYTECODE</h2>
 *
 * <p>Se busca quien <b>construye</b> una {@link OperacionTodaviaNoCompletable} —cualquiera de sus
 * subclases— entre las clases de produccion. Es el bytecode y no una expresion regular sobre el
 * fuente: un {@code throw} escrito con el nombre completo del tipo, o partido por el formateador en
 * dos lineas, no deja el mismo texto y si deja la misma llamada.
 *
 * <p><b>Y no se intenta decidir «termina inevitablemente en excepcion» siguiendo el grafo de
 * llamadas</b>, que exigiria un analisis que esta guarda no puede sostener. Lo que se exige es mas
 * estrecho y comprobable: toda clase de produccion que construya una de estas excepciones esta
 * declarada aqui, con la operacion publicada que muere en ella. Si alguien anade una rama
 * condicional, seguira teniendo que declararla — y eso es lo correcto, porque una escritura que a
 * veces no puede terminar tampoco se puede prometer entera.
 */
@DisplayName("#40 — las escrituras publicadas que no pueden terminar estan declaradas")
class EscriturasQueNoPuedenTerminarTest {

    /** Con esto puesto, la prueba reescribe el archivo derivado en vez de compararlo. */
    private static final String REGENERAR = "kamayuk.escrituras.regenerar";

    private static final String PROCEDENCIA =
            "ARCHIVO GENERADO — no editar a mano. Lo escribe"
                    + " EscriturasQueNoPuedenTerminarTest desde su lista NO_PUEDEN_TERMINAR, que"
                    + " la misma prueba contrasta EN LOS DOS SENTIDOS contra el bytecode de"
                    + " produccion: ninguna clase que construya una OperacionTodaviaNoCompletable"
                    + " puede quedar sin declarar, y ninguna entrada puede hablar de una clase que"
                    + " ya no la construye. Se regenera con -Dkamayuk.escrituras.regenerar=true."
                    + " Dice que operaciones publicadas NO pueden terminar todavia y que falta"
                    + " para que puedan, y lo lee generar-openapi.mjs para declarar su 501 en el"
                    + " contrato (#40).";

    /**
     * Lo que hoy se publica y no puede terminar, con la operacion, que falta y por que.
     *
     * <p><b>Cada entrada es una promesa que el contrato no puede cumplir</b>, asi que se escribe la
     * dependencia que la bloquea y no un «pendiente»: sin ella, esta lista se convierte en el sitio
     * donde se aparca lo que estorba.
     *
     * <p>Eran <b>tres</b> hasta #40. La tercera —{@code POST
     * /tesoreria/convenios/&#123;numero&#125;/anulacion}, que lanzaba {@code SinRutaEnCaja}— sale
     * de aqui porque la ruta que la serviria <b>ya la publicaba {@code caja}</b> desde el mismo
     * commit que creo el muñon: no faltaba un protocolo, faltaba escribir el adaptador.
     */
    private static final Map<String, Bloqueada> NO_PUEDEN_TERMINAR =
            new LinkedHashMap<>(
                    Map.of(
                            // RF-054. `TransferirARentas.transferir` hace cuatro cosas dentro de
                            // UNA transaccion, y esta es la primera: inscribir lo hallado en el
                            // padron de `catastro`. Servida por HTTP, `catastro` confirmaria su
                            // version por su cuenta y los pasos 2 a 4 —la resolucion, sus cargos y
                            // la fila que los ata— ocurririan despues, en otra base. Un fallo entre
                            // medias deja el padron cambiado sin resolucion que lo justifique y sin
                            // cargo que cobrar: #52 lo midio, «12 fichas donde debe haber 11».
                            "POST /fiscalizacion/transferencias",
                            new Bloqueada(
                                    "SinRutaTodavia",
                                    OperacionTodaviaNoCompletable.LoQueFalta
                                            .LA_TRANSACCION_COMPARTIDA,
                                    "ADR-0027: falta el protocolo que hace confirmar juntas a las"
                                            + " dos bases. Publicar la ruta NO la arregla, y ademas la"
                                            + " implementacion de `catastro` declara"
                                            + " Propagation.MANDATORY para que esto no se pueda"
                                            + " escribir suelto"),
                            // RF-026. Misma familia y mismo motivo: `catastro` confirmaria la
                            // transferencia de titularidad por su cuenta, y el registro de la
                            // transferencia en `rentas` y su auditoria ocurren despues.
                            "POST /rentas/transferencias/predio",
                            new Bloqueada(
                                    "TitularidadHttp",
                                    OperacionTodaviaNoCompletable.LoQueFalta
                                            .LA_TRANSACCION_COMPARTIDA,
                                    "ADR-0027: falta el protocolo que hace confirmar juntas a las"
                                            + " dos bases. La lectura de la titularidad de este mismo"
                                            + " adaptador SI funciona; lo que no se puede es"
                                            + " escribir")));

    /** Una operacion publicada que no puede terminar: quien lanza, que falta y por que. */
    private record Bloqueada(
            String clase, OperacionTodaviaNoCompletable.LoQueFalta falta, String motivo) {}

    // ------------------------------------------------------------------

    @Test
    @DisplayName("toda clase que lanza una de estas esta declarada, con su operacion")
    void todaLaQueLanzaEstaDeclarada() {
        Map<String, OperacionTodaviaNoCompletable.LoQueFalta> censo = quienLasConstruye();

        assertThat(censo)
                .as(
                        "sin sujeto esta guarda se cumpliria sola: si el recorrido no encuentra ni"
                                + " una clase que construya una OperacionTodaviaNoCompletable, «todas"
                                + " estan declaradas» es cierto sobre el conjunto vacio. Y con el"
                                + " conjunto vacio tampoco se puede generar el archivo que el contrato"
                                + " lee")
                .isNotEmpty();

        List<String> sinDeclarar = new ArrayList<>(censo.keySet());
        sinDeclarar.removeAll(clasesDeclaradas());

        assertThat(sinDeclarar)
                .as(
                        "estas clases construyen una excepcion que dice «esta operacion todavia no"
                                + " se puede completar» y ninguna entrada de NO_PUEDEN_TERMINAR dice"
                                + " que operacion publicada muere en ellas. Un muñon sin declarar pasa"
                                + " el contrato, pasa verificarArquitectura y pasa las tres mil"
                                + " pruebas: lo unico observable es que quien la llama recibe un error"
                                + " (#40)")
                .isEmpty();
    }

    @Test
    @DisplayName("y la lista solo baja: ninguna entrada habla de una clase que ya no lanza")
    void laListaSoloBaja() {
        Map<String, OperacionTodaviaNoCompletable.LoQueFalta> censo = quienLasConstruye();

        List<String> queYaNoLanzan = new ArrayList<>();
        List<String> conOtraFalta = new ArrayList<>();
        for (Map.Entry<String, Bloqueada> entrada : NO_PUEDEN_TERMINAR.entrySet()) {
            OperacionTodaviaNoCompletable.LoQueFalta lanzada =
                    censo.get(entrada.getValue().clase());
            if (lanzada == null) {
                queYaNoLanzan.add(entrada.getKey() + " -> " + entrada.getValue().clase());
                continue;
            }
            if (lanzada != entrada.getValue().falta()) {
                conOtraFalta.add(
                        entrada.getKey()
                                + ": declara "
                                + entrada.getValue().falta()
                                + " y lanza "
                                + lanzada);
            }
        }

        assertThat(queYaNoLanzan)
                .as(
                        "esta clase ya no construye ninguna: o se conecto —y entonces la entrada"
                                + " sobra, y dejarla convierte el censo en una puerta abierta— o se"
                                + " borro y la lista habla de lo que no hay. Es lo que paso con la"
                                + " anulacion del convenio en #40")
                .isEmpty();
        assertThat(conOtraFalta)
                .as(
                        "la entrada declara una cosa y la clase lanza otra: «falta la ruta» y"
                                + " «falta la transaccion compartida» se arreglan de maneras distintas"
                                + " —una publicandola, la otra con un protocolo— y el contrato publica"
                                + " la que esta lista diga")
                .isEmpty();
    }

    @Test
    @DisplayName("cada operacion declarada es una escritura que este backend publica de verdad")
    void cadaOperacionDeclaradaSePublica() {
        Set<String> publicadas = EndpointsPublicados.operaciones();

        assertThat(publicadas)
                .as("sin endpoints publicados no hay nada contra lo que contrastar la lista")
                .isNotEmpty();

        List<String> queNoExisten = new ArrayList<>();
        List<String> queNoSonEscritura = new ArrayList<>();
        for (String operacion : NO_PUEDEN_TERMINAR.keySet()) {
            if (!publicadas.contains(operacion)) {
                queNoExisten.add(operacion);
            } else if (!operacion.startsWith("POST ") && !operacion.startsWith("PUT ")) {
                queNoSonEscritura.add(operacion);
            }
        }

        assertThat(queNoExisten)
                .as(
                        "esta lista nombra una operacion que este backend no publica, asi que el"
                                + " contrato le pondria un 501 a una ruta que no existe y el censo"
                                + " hablaria de una promesa que nadie hizo")
                .isEmpty();
        assertThat(queNoSonEscritura)
                .as("#40 habla de escrituras publicadas: un GET bloqueado seria otra cosa")
                .isEmpty();
    }

    @Test
    @DisplayName("el archivo que el contrato lee es el que producen las clases de hoy")
    void elArchivoDerivadoEsElDeHoy() throws IOException {
        String producido = comoJson();
        Path destino =
                RaizDelRepositorio.ruta().resolve("docs/50-api/escrituras-no-completables.json");

        if (Boolean.getBoolean(REGENERAR)) {
            Files.writeString(destino, producido, StandardCharsets.UTF_8);
            return;
        }

        assertThat(destino)
                .as("el archivo derivado no existe: regeneralo con -D%s=true", REGENERAR)
                .exists();
        assertThat(Files.readString(destino, StandardCharsets.UTF_8))
                .as(
                        "lo que este censo produce y"
                                + " «docs/50-api/escrituras-no-completables.json» no cuadran. Si"
                                + " conectaste una escritura o anadiste un muñon, regenera con -D%s=true"
                                + " y vuelve a generar el contrato; si no, alguien edito el archivo a"
                                + " mano.",
                        REGENERAR)
                .isEqualTo(producido);
    }

    // ------------------------------------------------------------------

    /**
     * Cada clase de produccion que construye una {@link OperacionTodaviaNoCompletable}, con el
     * nombre de la subclase que construye.
     *
     * <p><b>Se excluye a las propias excepciones</b>, y no es cosmetico: el {@code super(...)} del
     * constructor de {@code SinRutaEnCaja} es tambien una llamada a constructor cuyo destino es
     * asignable a {@link OperacionTodaviaNoCompletable}, asi que sin este filtro las tres subclases
     * saldrian en el censo como si publicaran una operacion — y ademas el destino seria la clase
     * ABSTRACTA, que no se puede instanciar para preguntarle que le falta. Una excepcion que llama
     * a su super no publica ninguna operacion.
     *
     * <p>Y se exige que el destino sea CONCRETO por lo mismo: lo que dice que falta es el valor que
     * el constructor de la subclase pasa, y la abstracta no pasa ninguno.
     */
    private static Map<String, OperacionTodaviaNoCompletable.LoQueFalta> quienLasConstruye() {
        Map<String, OperacionTodaviaNoCompletable.LoQueFalta> censo = new TreeMap<>();
        for (JavaClass clase : ReglasDeArquitectura.clasesDeProduccion()) {
            if (clase.isAssignableTo(OperacionTodaviaNoCompletable.class)) {
                continue;
            }
            for (JavaConstructorCall llamada : clase.getConstructorCallsFromSelf()) {
                JavaClass excepcion = llamada.getTargetOwner();
                if (!excepcion.isAssignableTo(OperacionTodaviaNoCompletable.class)
                        || excepcion.getModifiers().contains(JavaModifier.ABSTRACT)) {
                    continue;
                }
                censo.put(clase.getSimpleName(), faltaQueLanza(excepcion));
            }
        }
        return censo;
    }

    /**
     * Que falta, leido del PROPIO objeto que la clase construye.
     *
     * <p>Se instancia la excepcion —sus dos constructores son {@code (String, String)}— y se le
     * pregunta su {@code loQueFalta()}. Es derivar y no copiar: con una tabla escrita al lado,
     * cambiar la constante que el constructor pasa dejaria el contrato publicando «falta la ruta»
     * sobre una operacion a la que le falta el protocolo, y nada se pondria rojo. Y son dos cosas
     * que se arreglan de maneras distintas: una publicando una ruta en otro repositorio, la otra
     * construyendo ADR-0027.
     */
    private static OperacionTodaviaNoCompletable.LoQueFalta faltaQueLanza(JavaClass excepcion) {
        try {
            Object instancia =
                    excepcion
                            .reflect()
                            .getDeclaredConstructor(String.class, String.class)
                            .newInstance("(sonda de la guarda)", "(sonda de la guarda)");
            return ((OperacionTodaviaNoCompletable) instancia).loQueFalta();
        } catch (ReflectiveOperationException noSePudo) {
            throw new IllegalStateException(
                    "No se pudo preguntarle a «"
                            + excepcion.getSimpleName()
                            + "» que le falta. Sin eso este censo tendria que adivinarlo, y el"
                            + " contrato publicaria un motivo que nadie ha comprobado",
                    noSePudo);
        }
    }

    private static Set<String> clasesDeclaradas() {
        Set<String> clases = new java.util.TreeSet<>();
        for (Bloqueada bloqueada : NO_PUEDEN_TERMINAR.values()) {
            clases.add(bloqueada.clase());
        }
        return clases;
    }

    /** El censo, ordenado por operacion para que el archivo no cambie de una corrida a otra. */
    private static String comoJson() {
        StringBuilder json = new StringBuilder("{\n");
        json.append("  \"_procedencia\": ").append(comillas(PROCEDENCIA)).append(",\n");
        List<String> operaciones = new ArrayList<>(new TreeMap<>(NO_PUEDEN_TERMINAR).keySet());
        for (int i = 0; i < operaciones.size(); i++) {
            Bloqueada bloqueada = NO_PUEDEN_TERMINAR.get(operaciones.get(i));
            json.append("  ").append(comillas(operaciones.get(i))).append(": {\n");
            json.append("    \"falta\": ").append(comillas(bloqueada.falta().name())).append(",\n");
            json.append("    \"motivo\": ").append(comillas(bloqueada.motivo())).append("\n");
            json.append("  }").append(i == operaciones.size() - 1 ? "\n" : ",\n");
        }
        return json.append("}\n").toString();
    }

    private static String comillas(String texto) {
        return "\"" + texto.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
