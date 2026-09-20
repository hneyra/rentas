package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los metodos de puerto publicado que no tiene quien los llame (#43 AC-1, ampliado por #266).
 *
 * <h2>Que mide, y por que ninguna otra guarda lo mide</h2>
 *
 * <p>El contrato comprometido —{@code docs/50-api/contratos-que-consume/catastro.json}— comprueba
 * que <b>el proveedor no retire</b> lo que este lado declara leer, y su CI se pone rojo si lo hace.
 * Eso funciona. Lo que ninguna guarda medía es lo contrario: que este lado <b>use</b> lo que
 * declara. Un puerto sin invocadores pasa {@code verificarArquitectura}, pasa el contrato y pasa
 * las tres mil pruebas — se declara, se adapta, se prueba, y de su modulo no sale.
 *
 * <p>Y lo que cuesta no es codigo muerto. Es que la decision que ese puerto existia para sostener
 * se sigue tomando <b>sin el</b>: mientras {@code ZonificacionDelPredio} no tuvo llamador, una
 * licencia se autorizaba contra una zona <b>tecleada</b> en la solicitud, y nadie la comparaba con
 * nada.
 *
 * <h2>Por que ya no son solo los dos modulos cliente</h2>
 *
 * <p>Hasta #266 este censo miraba <b>solo</b> {@code kamayuk-rentas-catastro} y {@code
 * kamayuk-rentas-parametros}, con este motivo escrito: «los demas modulos publican puertos
 * internos, y esos ya los vigila Spring Modulith». <b>Ese motivo era falso, y esta medido</b>:
 * Modulith vigila el <b>limite</b> —que {@code sanciones} no importe {@code
 * valores.aplicacion.RegistrarValor}— y no el <b>consumo</b>. Un puerto que vive donde debe, se
 * implementa donde debe y no lo llama nadie es un modulo perfectamente bien delimitado alrededor
 * del vacio, y Modulith lo aprueba.
 *
 * <p>Lo demostro {@code EmisionDeValoresDeMultas.conPaseACoactiva(Collection&lt;Long&gt;)}:
 * declarado, implementado, con su consulta JDBC y su doble en memoria, y sin una sola llamada en
 * {@code src/main} de los diecisiete modulos. Es exactamente el modo de fallo que #43 nombra, un
 * nivel mas adentro, y ninguna guarda lo vio. #266 lo retiro y amplio este censo para que el
 * siguiente salga el mismo dia.
 *
 * <h2>Como se cuenta un consumidor, y por que NO es «fuera de su modulo»</h2>
 *
 * <p>Un metodo esta consumido si algun archivo de {@code src/main} lo <b>llama</b> y ese archivo
 * <b>no es una implementacion del puerto</b>. La regla de #43 era otra —«cualquier mencion fuera
 * del modulo que lo publica»— y se quedo corta en cuanto el censo salio de los dos modulos cliente,
 * porque ahi los puertos son todos <b>entrantes</b> y en los contextos no:
 *
 * <ul>
 *   <li>{@code AnulacionesDeRecibo#estaAnulado} es un puerto <b>saliente</b> hacia {@code caja}:
 *       {@code tesoreria} lo declara, {@code tesoreria.aplicacion.CerrarConvenio} lo llama y {@code
 *       tesoreria.infraestructura.AnulacionesDeReciboHttp} lo implementa. Esta cableado y decide
 *       algo, y «fuera del modulo» lo daba por muerto.
 *   <li>{@code TitularesDeLaUnidad} es un puerto saliente con la dependencia invertida: lo declara
 *       y lo consume {@code cuentacorriente}, y lo implementa {@code nucleo}.
 * </ul>
 *
 * <p><b>Medido el 2026-09-20</b> sobre los mismos 65 metodos: la regla de #43 acusaba 5 y la de
 * #266 acusa 4; la diferencia es {@code AnulacionesDeRecibo#estaAnulado}, vivo. Y sin apartar los
 * puntos de extension, la regla de #43 sobre el arbol entero acusaba 20 metodos, de los cuales 13
 * estaban vivos. La regla nueva no afloja nada donde #43 mordia: sobre los puertos de los dos
 * modulos cliente las dos reglas dan la misma respuesta, porque ahi «fuera de su implementacion» y
 * «fuera de su modulo» son el mismo conjunto — esos modulos no tienen casos de uso.
 *
 * <p>Se cuenta por el <b>fuente</b> y no por el bytecode, a proposito: un puerto se declara como
 * campo o como parametro de constructor, y eso deja huella textual segura. Y se cuenta sobre el
 * fuente <b>sin comentarios</b>: un {@code {@code libro.abonadoPor(...)}} dentro de un docblock es
 * justo lo que esta guarda persigue —la API documentada que nadie llamo nunca—, y contarlo como
 * llamada la apagaria. Hoy da lo mismo con comentarios y sin ellos; se hace bien igual.
 *
 * <h2>Por metodo, y no por puerto</h2>
 *
 * <p>Porque un puerto con dos metodos de los cuales uno no se llama pasaba entero: {@code
 * EmisionDeValoresDeMultas} tenia consumidor —{@code emitirPorMulta}, desde {@code sanciones}— y su
 * segundo metodo llevaba muerto desde que se escribio.
 *
 * <h2>La lista solo baja</h2>
 *
 * <p>Es el patron de {@code busquedasDeTextoLibreConMotivo()}: quien queda dentro esta con su
 * motivo escrito y con la <b>dependencia que lo bloquea nombrada</b>, no con un «pendiente». Y es
 * la lista de trabajo pendiente, no una puerta: mientras tenga entradas, este sistema publica una
 * API que no decide nada. Anadir un puerto nuevo sin consumidor pone esto rojo el mismo dia.
 */
@DisplayName("#43/#266 — ningun metodo de puerto publicado se queda sin consumidor")
class PuertosSinConsumidorTest {

    /**
     * Los modulos que NO se censan, cada uno con lo que publica en vez de puertos.
     *
     * <p>Se escriben los que quedan fuera y no los que entran, para que un modulo nuevo entre solo:
     * una lista de incluidos se queda vieja en silencio, y ese es el modo de fallo que una guarda
     * de censo no se puede permitir. {@link #elCensoAlcanzaATodoModuloQueNoSeExcluyeConMotivo} lo
     * hace cumplir.
     */
    private static final Map<String, String> FUERA_DEL_CENSO =
            Map.of(
                    "kamayuk-rentas-aplicacion",
                    "no tiene paquete raiz propio: ensambla el artefacto y hospeda estas barreras",
                    "kamayuk-rentas-esquema",
                    "son migraciones y un `Migrador`; no publica ninguna API a otro contexto",
                    "kamayuk-rentas-dominio-compartido",
                    "es la libreria de objetos de valor —`kamayuk.rentas.dominio` y"
                            + " `kamayuk.rentas.compartido`—, no un contexto: sus tipos los usan"
                            + " los trece y no son puertos de nadie",
                    "kamayuk-rentas-plataforma",
                    "es transversal y publica SIETE paquetes raiz —auditoria, autorizacion, carga,"
                            + " documentos, persistencia, plataforma y web—: censar uno seria"
                            + " callar sobre los otros seis, y ninguno es una API entre contextos");

    /**
     * Los metodos de puerto que hoy no tienen consumidor, con su motivo y lo que los bloquea.
     *
     * <p><b>Cada entrada es trabajo pendiente con dueno</b>, y por eso se escribe que falta y no
     * que «no se usa todavia»: sin la dependencia nombrada, esta lista se convierte en el sitio
     * donde se aparca lo que estorba. La clave es {@code Puerto#metodo}.
     */
    private static final Map<String, String> SIN_CONSUMIDOR_CON_MOTIVO =
            new LinkedHashMap<>(
                    Map.of(
                            // El frente lineal de `catastro`#7. Lo consume el arbitrio de barrido,
                            // que se determina sobre metros LINEALES — y no hay tasa que aplicar a
                            // un metro lineal mientras D-02b siga abierta: la ordenanza de
                            // arbitrios y su ratificacion provincial son lo que falta, no codigo.
                            //
                            // Lo que SI se hizo mientras tanto es que el dia que llegue no pueda
                            // cobrar una PROPUESTA: #15 tipo el estado y partio la lista, y
                            // `NingunaPropuestaLlegaAUnaCifraTest` lo sostiene. Poner la compuerta
                            // ahora es barato; despues habria que buscar todos los consumidores.
                            "FrentesDelPredio#delPredio",
                            "D-02b: sin la ordenanza de arbitrios no hay tasa que aplicar a un"
                                    + " metro lineal. La compuerta PROPUESTA/CONFIRMADA ya esta"
                                    + " puesta (#15)",
                            // Los hallazgos por predio de `catastro`#17. Un exceso de area hallado
                            // en campo es el disparador de una fiscalizacion TRIBUTARIA, y que
                            // acto la abre —una orden de fiscalizacion, un requerimiento, una
                            // determinacion de oficio— es una decision de negocio que no esta
                            // tomada. Consumirlo antes de tomarla seria elegirla en silencio.
                            "HallazgosDelPredio#de",
                            "Sin decidir que acto de `fiscalizacion` abre un hallazgo catastral"
                                    + " firme: hoy ninguno lo hace, y elegirlo aqui seria decidir"
                                    + " por el dueno de ese contexto",
                            "HallazgosDelPredio#deLaCampania",
                            "Lo mismo que `HallazgosDelPredio#de`: el hallazgo de una campania no"
                                    + " abre nada mientras no se decida que acto lo abre",
                            // El cuadre del cierre de caja contra el libro. Su consumidor EXISTIO y
                            // se midio: en `sgtm` era `tesoreria.aplicacion.ArqueoDeTurno`, que en
                            // P5D se fue a `caja` con toda la ventanilla. El `ArqueoDeTurno` de
                            // `caja` cuadra hoy contra su propio buzon de salida y no le pregunta
                            // nada al libro, asi que este puerto quedo del lado de aca sin nadie
                            // al otro. No se retira porque la pregunta sigue siendo la correcta
                            // —un cierre firmado y el estado de cuenta tienen que decir la misma
                            // cifra—; lo que falta es decidir si el cuadre vuelve, y por donde:
                            // si vuelve, `caja` lo pedira por HTTP y no por este puerto de Java.
                            "ConciliacionDeCaja#abonadoPor",
                            "Su consumidor se fue a `caja` en P5D (`ArqueoDeTurno`), y el arqueo de"
                                    + " `caja` cuadra hoy contra su buzon de salida. Sin decidir si"
                                    + " el cierre vuelve a cuadrar contra el libro —y por que"
                                    + " camino— no hay a quien cablearlo"));

    @Test
    @DisplayName("todo metodo de puerto tiene consumidor, o esta en la lista con motivo")
    void todoMetodoDePuertoTieneConsumidorOMotivo() {
        Map<String, List<String>> encontrados = metodosConSusConsumidores();

        assertThat(encontrados)
                .as(
                        "sin sujeto esta guarda se cumpliria sola: si el recorrido no encuentra ni"
                                + " un metodo de puerto, «todos tienen consumidor» es cierto sobre"
                                + " el conjunto vacio")
                .isNotEmpty();
        assertThat(fuentesDeProduccion())
                .as(
                        "ni un archivo de `build/`: son copias del fuente, y el modulo que se"
                                + " deduce de su ruta no es el suyo — un puerto quedaria «consumido»"
                                + " por su propio adaptador con otro nombre de modulo")
                .noneMatch(ruta -> ruta.toString().contains("/build/"));

        List<String> sinConsumidor = new ArrayList<>();
        for (Map.Entry<String, List<String>> metodo : encontrados.entrySet()) {
            if (metodo.getValue().isEmpty()
                    && !SIN_CONSUMIDOR_CON_MOTIVO.containsKey(metodo.getKey())) {
                sinConsumidor.add(metodo.getKey());
            }
        }

        assertThat(sinConsumidor)
                .as(
                        "un metodo de puerto sin invocadores pasa verificarArquitectura, pasa los"
                                + " limites de Spring Modulith —que vigilan el limite y no el"
                                + " consumo—, pasa el contrato comprometido y pasa las tres mil"
                                + " pruebas; y la decision que existia para sostener se sigue"
                                + " tomando sin el (#43, #266)")
                .isEmpty();
    }

    @Test
    @DisplayName("y la lista de exentos no tiene entradas que sobren: la lista solo baja")
    void laListaDeExentosNoTieneEntradasQueSobren() {
        Map<String, List<String>> encontrados = metodosConSusConsumidores();

        List<String> yaConsumidos = new ArrayList<>();
        List<String> queYaNoExisten = new ArrayList<>();
        for (String exento : SIN_CONSUMIDOR_CON_MOTIVO.keySet()) {
            if (!encontrados.containsKey(exento)) {
                queYaNoExisten.add(exento);
            } else if (!encontrados.get(exento).isEmpty()) {
                yaConsumidos.add(exento);
            }
        }

        assertThat(queYaNoExisten)
                .as(
                        "un exento que ya no es un metodo de puerto deja la lista hablando de lo que"
                                + " no hay")
                .isEmpty();
        assertThat(yaConsumidos)
                .as(
                        "este ya tiene consumidor: la exencion sobra, y dejarla convierte el censo"
                                + " en una puerta abierta")
                .isEmpty();
    }

    @Test
    @DisplayName("el censo alcanza a todo modulo que no se excluya con motivo")
    void elCensoAlcanzaATodoModuloQueNoSeExcluyeConMotivo() {
        List<String> niCensadosNiExcluidos = new ArrayList<>();
        for (Path modulo : modulosDelBackend()) {
            String nombre = modulo.getFileName().toString();
            if (!Files.isDirectory(modulo.resolve("src/main/java"))) {
                continue;
            }
            if (FUERA_DEL_CENSO.containsKey(nombre)) {
                continue;
            }
            if (!Files.isDirectory(paqueteRaizDe(nombre))) {
                niCensadosNiExcluidos.add(nombre + " -> " + paqueteRaizDe(nombre));
            }
        }

        assertThat(niCensadosNiExcluidos)
                .as(
                        "o el modulo tiene su paquete raiz donde el censo lo busca, o esta en"
                                + " FUERA_DEL_CENSO con su motivo. Sin esto, un modulo nuevo —o uno"
                                + " renombrado— sale del censo sin que nadie lo decida, y la guarda"
                                + " pasa en verde sobre lo que ya no mira")
                .isEmpty();
        assertThat(modulosCensados())
                .as(
                        "si los paquetes raiz se mueven, el censo deja de encontrar puertos y pasa en"
                                + " verde sin haber mirado nada")
                .hasSize(13);
    }

    // ------------------------------------------------------------------

    /**
     * Cada {@code Puerto#metodo} publicado, con los archivos de {@code src/main} que lo llaman.
     *
     * <p>Un puerto es una {@code interface} publica en la raiz del paquete del modulo. No los
     * {@code record} —esos son lo que el puerto devuelve— ni lo que vive en {@code .aplicacion},
     * {@code .dominio} o {@code .infraestructura}, que Spring Modulith trata como interno.
     */
    private static Map<String, List<String>> metodosConSusConsumidores() {
        Map<String, String> puertos = puertosPorModulo();

        Map<String, List<String>> consumidores = new LinkedHashMap<>();
        for (Map.Entry<String, String> puerto : puertos.entrySet()) {
            String tipo = puerto.getKey();
            List<Path> candidatos =
                    fuentesDeProduccion().stream()
                            .filter(f -> !f.getFileName().toString().equals(tipo + ".java"))
                            .filter(f -> mencionaElTipo(limpio(f), tipo))
                            .filter(f -> !loImplementa(limpio(f), tipo))
                            .toList();
            for (String metodo : metodosDe(tipo, puerto.getValue())) {
                List<String> quienLoLlama = new ArrayList<>();
                for (Path fuente : candidatos) {
                    String texto = limpio(fuente);
                    if (texto.contains("." + metodo + "(") || texto.contains("::" + metodo)) {
                        quienLoLlama.add(fuente.getFileName().toString());
                    }
                }
                consumidores.put(tipo + "#" + metodo, quienLoLlama);
            }
        }
        return consumidores;
    }

    /**
     * El fuente de produccion ya leido y despojado de comentarios, memorizado.
     *
     * <p>Sin memorizarlo esto son sesenta y cinco metodos por mil archivos de lectura y de regex, y
     * una guarda que tarda es una guarda que alguien acaba sacando de `build`.
     */
    private static final Map<Path, String> LIMPIOS = new java.util.concurrent.ConcurrentHashMap<>();

    private static String limpio(Path fuente) {
        return LIMPIOS.computeIfAbsent(fuente, ruta -> sinComentarios(leer(ruta)));
    }

    /** Los puertos publicados, por nombre de tipo, con el paquete raiz donde viven. */
    private static Map<String, String> puertosPorModulo() {
        Map<String, String> puertos = new LinkedHashMap<>();
        Map<String, String> moduloDelPuerto = new LinkedHashMap<>();
        for (Map.Entry<String, Path> modulo : modulosCensados().entrySet()) {
            try (Stream<Path> archivos = Files.list(modulo.getValue())) {
                archivos.filter(archivo -> archivo.toString().endsWith(".java"))
                        .forEach(
                                archivo -> {
                                    String nombre =
                                            archivo.getFileName().toString().replace(".java", "");
                                    if (limpio(archivo).contains("public interface " + nombre)) {
                                        puertos.put(nombre, paqueteDe(modulo.getValue()));
                                        moduloDelPuerto.put(nombre, modulo.getKey());
                                    }
                                });
            } catch (IOException fallo) {
                throw new UncheckedIOException(fallo);
            }
        }

        // LOS PUNTOS DE EXTENSION NO SON PUERTOS, y hay que apartarlos o el censo miente. No es
        // una lista: es una REGLA, y se aplica sola. Si la interfaz la implementa un modulo
        // DISTINTO del que la declara, la llamada va del proveedor al implementador y «no la llama
        // nadie» es su forma normal: `ReglaTributaria` la declara `parametros` y la implementa
        // `nucleo`, y quien la llama es el motor de reglas, dentro de `parametros`.
        //
        // Lo encontro esta guarda al ponerse estricta, y acusarla habria sido gritar en lo
        // correcto —que es como una comprobacion se acaba apagando (#437)—. Hoy aparta cinco
        // puertos y diecisiete metodos; dos de ellos —`ReglaTributaria#descripcion` y
        // `ReglaDeAgregacion#descripcion`— tampoco tienen llamador, y tampoco sobran: son la cita
        // de la norma que RNF-090 le exige a toda regla, y lo que las obliga es implementarlas.
        puertos.keySet()
                .removeIf(
                        tipo ->
                                fuentesDeProduccion().stream()
                                        .filter(f -> !moduloDelPuerto.get(tipo).equals(moduloDe(f)))
                                        .anyMatch(f -> loImplementa(limpio(f), tipo)));
        return puertos;
    }

    /**
     * Los modulos censados: los que tienen su propio paquete raiz {@code kamayuk.rentas.<modulo>}.
     *
     * <p>Son los doce contextos acotados de ARQ-01 §3 mas {@code indicadores}. Quien no esta,
     * declara por que en {@link #FUERA_DEL_CENSO}.
     */
    private static Map<String, Path> modulosCensados() {
        Map<String, Path> censados = new LinkedHashMap<>();
        for (Path modulo : modulosDelBackend()) {
            String nombre = modulo.getFileName().toString();
            if (FUERA_DEL_CENSO.containsKey(nombre)) {
                continue;
            }
            Path raiz = paqueteRaizDe(nombre);
            if (Files.isDirectory(raiz)) {
                censados.put(nombre, raiz);
            }
        }
        return censados;
    }

    private static List<Path> modulosDelBackend() {
        Path backend = RaizDelRepositorio.ruta().resolve("backend");
        try (Stream<Path> hijos = Files.list(backend)) {
            return hijos.filter(Files::isDirectory)
                    .filter(ruta -> ruta.getFileName().toString().startsWith("kamayuk-rentas-"))
                    .sorted()
                    .toList();
        } catch (IOException fallo) {
            throw new UncheckedIOException(fallo);
        }
    }

    private static Path paqueteRaizDe(String modulo) {
        String paquete = modulo.replace("kamayuk-rentas-", "").replace("-", "");
        return RaizDelRepositorio.ruta()
                .resolve("backend")
                .resolve(modulo)
                .resolve("src/main/java/kamayuk/rentas")
                .resolve(paquete);
    }

    private static String paqueteDe(Path raizDelPaquete) {
        String ruta = raizDelPaquete.toString();
        return "kamayuk.rentas." + ruta.substring(ruta.lastIndexOf('/') + 1);
    }

    /**
     * Los metodos que el puerto declara, leidos del tipo compilado.
     *
     * <p>Por <b>reflexion</b> y no de una lista: una lista copiada aqui se quedaria vieja el dia
     * que el puerto gane una operacion, y entonces un consumidor que solo llamara a la nueva
     * contaria como ninguno. Es la misma razon por la que el censo de modulos se escribe por
     * exclusion.
     */
    private static Set<String> metodosDe(String tipo, String paquete) {
        Set<String> nombres = new LinkedHashSet<>();
        try {
            for (java.lang.reflect.Method metodo :
                    Class.forName(paquete + "." + tipo).getDeclaredMethods()) {
                if (metodo.isSynthetic() || metodo.isDefault()) {
                    continue;
                }
                nombres.add(metodo.getName());
            }
        } catch (ClassNotFoundException noEstaEnElClasspath) {
            throw new IllegalStateException(
                    "No se pudo cargar el puerto «"
                            + paquete
                            + "."
                            + tipo
                            + "»: sin sus metodos, este censo no puede distinguir «lo llama» de «lo"
                            + " nombra», y pasaria en verde con un puerto inyectado y sin usar",
                    noEstaEnElClasspath);
        }
        return nombres;
    }

    /**
     * La mencion tiene que ser al TIPO y no a una subcadena.
     *
     * <p>Sin el limite de palabra, {@code FrentesDelPredio} casaria dentro de {@code
     * FrentesDelPredioHttp} y un puerto quedaria «consumido» por su propio adaptador con otro
     * nombre.
     */
    private static boolean mencionaElTipo(String texto, String tipo) {
        Matcher busqueda = Pattern.compile("\\b" + Pattern.quote(tipo) + "\\b").matcher(texto);
        return busqueda.find();
    }

    /** Quien implementa el puerto no lo consume: se limita a contestarlo. */
    private static boolean loImplementa(String texto, String tipo) {
        return Pattern.compile("implements[^{;]*\\b" + Pattern.quote(tipo) + "\\b")
                .matcher(texto)
                .find();
    }

    /**
     * El fuente sin comentarios ni literales: lo que el programa HACE, no lo que dice que hace.
     *
     * <p>Un {@code {@code libro.abonadoPor(...)}} dentro de un docblock es exactamente la forma que
     * esta guarda persigue —la API documentada que nadie llamo nunca—, y contarlo como llamada la
     * apagaria. Los literales se vacian por lo mismo: un SQL que nombrara el metodo no lo llama.
     */
    private static String sinComentarios(String texto) {
        String sinBloque = texto.replaceAll("(?s)/\\*.*?\\*/", "");
        String sinLinea = sinBloque.replaceAll("//[^\n]*", "");
        return sinLinea.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    private static Set<Path> fuentesDeProduccion() {
        Path backend = RaizDelRepositorio.ruta().resolve("backend");
        Set<Path> fuentes = new LinkedHashSet<>();
        try (Stream<Path> recorrido = Files.walk(backend)) {
            recorrido
                    .filter(ruta -> ruta.toString().endsWith(".java"))
                    .filter(ruta -> ruta.toString().contains("/src/main/java/"))
                    // Y NO lo que Gradle deja en `build/`. Sin esta linea el recorrido entra en
                    // `build/spotless-clean/spotlessJava/src/main/java/...`, que es una COPIA del
                    // fuente: el modulo que se deduce de esa ruta es «spotlessJava», asi que todo
                    // puerto quedaba «consumido» por su propio adaptador copiado con otro nombre de
                    // modulo. Medido: con el recorrido entero, `FrentesDelPredio` salia consumido y
                    // su exencion «sobraba».
                    .filter(ruta -> !ruta.toString().contains("/build/"))
                    .forEach(fuentes::add);
        } catch (IOException fallo) {
            throw new UncheckedIOException(fallo);
        }
        return fuentes;
    }

    private static String moduloDe(Path fuente) {
        String ruta = fuente.toString();
        int corte = ruta.indexOf("/src/main/java/");
        String antes = ruta.substring(0, corte);
        return antes.substring(antes.lastIndexOf('/') + 1);
    }

    private static String leer(Path archivo) {
        try {
            return Files.readString(archivo, StandardCharsets.UTF_8);
        } catch (IOException fallo) {
            throw new UncheckedIOException(fallo);
        }
    }
}
