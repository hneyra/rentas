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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los puertos hacia otro sistema que no tiene quien los llame (#43, AC-1).
 *
 * <h2>Que mide, y por que ninguna guarda anterior podia medirlo</h2>
 *
 * <p>El contrato comprometido —{@code docs/50-api/contratos-que-consume/catastro.json}— comprueba
 * que <b>el proveedor no retire</b> lo que este lado declara leer, y su CI se pone rojo si lo hace.
 * Eso funciona. Lo que ninguna guarda medía es lo contrario: que este lado <b>use</b> lo que se
 * comprometio a leer. Un puerto sin invocadores pasa {@code verificarArquitectura}, pasa el
 * contrato y pasa las tres mil pruebas — se declara, se adapta, se prueba, y de su modulo no sale.
 *
 * <p>Y lo que cuesta no es codigo muerto. Es que la decision que ese puerto existia para sostener
 * se sigue tomando <b>sin el</b>: mientras {@code ZonificacionDelPredio} no tuvo llamador, una
 * licencia se autorizaba contra una zona <b>tecleada</b> en la solicitud, y nadie la comparaba con
 * nada.
 *
 * <h2>La lista solo baja</h2>
 *
 * <p>Es el patron de {@code busquedasDeTextoLibreConMotivo()}: quien queda dentro esta con su
 * motivo escrito y con la <b>dependencia que lo bloquea nombrada</b>, no con un «pendiente». Y es
 * la lista de trabajo pendiente, no una puerta: mientras tenga entradas, este sistema tiene puertos
 * hacia otro sistema que no decide nada. Anadir un puerto nuevo sin consumidor pone esto rojo el
 * mismo dia.
 *
 * <h2>Como se cuenta un consumidor</h2>
 *
 * <p>Por el <b>fuente</b> y no por el bytecode, y a proposito: un puerto se declara como campo o
 * como parametro de constructor de un caso de uso, y eso deja huella textual segura. Se cuenta
 * cualquier mencion en {@code src/main} <b>fuera del modulo que lo publica</b> — dentro no vale, y
 * ese es todo el punto: el adaptador HTTP y la interfaz viven ahi, asi que un puerto «usado» solo
 * por su propio adaptador esta exactamente igual de muerto.
 */
@DisplayName("#43 — ningun puerto hacia otro sistema se queda sin consumidor")
class PuertosSinConsumidorTest {

    /**
     * Los modulos que son adaptadores CLIENTE de otro sistema (P5E §6).
     *
     * <p>Son los dos que {@code ConfiguracionDelSgtm} reparte fuera de {@code rentas}: sus tipos de
     * la raiz del paquete son la API que este sistema declara consumir del vecino. Los demas
     * modulos publican puertos <b>internos</b>, entre contextos de este mismo sistema, y esos ya
     * los vigila Spring Modulith.
     */
    private static final Map<String, String> MODULOS_CLIENTE =
            Map.of(
                    "kamayuk-rentas-catastro", "kamayuk/rentas/catastro",
                    "kamayuk-rentas-parametros", "kamayuk/rentas/parametros");

    /**
     * Los puertos que hoy no tienen consumidor, con su motivo y la dependencia que los bloquea.
     *
     * <p><b>Cada entrada es trabajo pendiente con dueno</b>, y por eso se escribe que falta y no
     * que «no se usa todavia»: sin la dependencia nombrada, esta lista se convierte en el sitio
     * donde se aparca lo que estorba.
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
                            "FrentesDelPredio",
                            "D-02b: sin la ordenanza de arbitrios no hay tasa que aplicar a un"
                                    + " metro lineal. La compuerta PROPUESTA/CONFIRMADA ya esta"
                                    + " puesta (#15)",
                            // Los hallazgos por predio de `catastro`#17. Un exceso de area hallado
                            // en campo es el disparador de una fiscalizacion TRIBUTARIA, y que
                            // acto la abre —una orden de fiscalizacion, un requerimiento, una
                            // determinacion de oficio— es una decision de negocio que no esta
                            // tomada. Consumirlo antes de tomarla seria elegirla en silencio.
                            "HallazgosDelPredio",
                            "Sin decidir que acto de `fiscalizacion` abre un hallazgo catastral"
                                    + " firme: hoy ninguno lo hace, y elegirlo aqui seria decidir"
                                    + " por el dueno de ese contexto"));

    @Test
    @DisplayName("los puertos hacia otro sistema tienen consumidor, o estan en la lista con motivo")
    void todoPuertoTieneConsumidorOMotivo() {
        Map<String, List<String>> encontrados = puertosConSusConsumidores();

        assertThat(encontrados)
                .as(
                        "sin sujeto esta guarda se cumpliria sola: si el recorrido no encuentra ni"
                                + " un puerto, «todos tienen consumidor» es cierto sobre el conjunto"
                                + " vacio")
                .isNotEmpty();

        List<String> sinConsumidor = new ArrayList<>();
        for (Map.Entry<String, List<String>> puerto : encontrados.entrySet()) {
            if (puerto.getValue().isEmpty()
                    && !SIN_CONSUMIDOR_CON_MOTIVO.containsKey(puerto.getKey())) {
                sinConsumidor.add(puerto.getKey());
            }
        }

        assertThat(sinConsumidor)
                .as(
                        "un puerto sin invocadores pasa verificarArquitectura, pasa el contrato"
                                + " comprometido y pasa las tres mil pruebas — y la decision que"
                                + " existia para sostener se sigue tomando sin el (#43)")
                .isEmpty();
    }

    @Test
    @DisplayName("y la lista de exentos no tiene entradas que sobren: la lista solo baja")
    void laListaDeExentosNoTieneEntradasQueSobren() {
        Map<String, List<String>> encontrados = puertosConSusConsumidores();

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
                .as("un exento que ya no es un puerto deja la lista hablando de lo que no hay")
                .isEmpty();
        assertThat(yaConsumidos)
                .as(
                        "este ya tiene consumidor: la exencion sobra, y dejarla convierte el censo"
                                + " en una puerta abierta")
                .isEmpty();
    }

    @Test
    @DisplayName("el censo mira los dos modulos cliente, y los dos estan en el disco")
    void elCensoMiraLosDosModulosCliente() {
        for (Map.Entry<String, String> modulo : MODULOS_CLIENTE.entrySet()) {
            Path raiz =
                    RaizDelRepositorio.ruta()
                            .resolve("backend")
                            .resolve(modulo.getKey())
                            .resolve("src/main/java")
                            .resolve(modulo.getValue());
            assertThat(Files.isDirectory(raiz))
                    .as(
                            "si este directorio se mueve, el censo deja de encontrar puertos y"
                                    + " pasa en verde sin haber mirado nada: "
                                    + raiz)
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------

    /**
     * Cada puerto de los modulos cliente, con los archivos de {@code src/main} de OTRO modulo que
     * lo nombran.
     *
     * <p>Un puerto es una {@code interface} publica en la raiz del paquete del modulo cliente. No
     * los {@code record} —esos son lo que el puerto devuelve— ni lo que vive en {@code
     * infraestructura}, que es el transporte.
     */
    private static Map<String, List<String>> puertosConSusConsumidores() {
        Map<String, String> puertos = new LinkedHashMap<>();
        for (Map.Entry<String, String> modulo : MODULOS_CLIENTE.entrySet()) {
            Path raiz =
                    RaizDelRepositorio.ruta()
                            .resolve("backend")
                            .resolve(modulo.getKey())
                            .resolve("src/main/java")
                            .resolve(modulo.getValue());
            if (!Files.isDirectory(raiz)) {
                continue;
            }
            try (Stream<Path> archivos = Files.list(raiz)) {
                archivos.filter(archivo -> archivo.toString().endsWith(".java"))
                        .forEach(
                                archivo -> {
                                    String nombre =
                                            archivo.getFileName().toString().replace(".java", "");
                                    if (esUnPuerto(archivo, nombre)) {
                                        puertos.put(nombre, modulo.getKey());
                                    }
                                });
            } catch (IOException fallo) {
                throw new UncheckedIOException(fallo);
            }
        }

        Map<String, List<String>> consumidores = new LinkedHashMap<>();
        for (Map.Entry<String, String> puerto : puertos.entrySet()) {
            consumidores.put(puerto.getKey(), new ArrayList<>());
        }
        for (Path fuente : fuentesDeProduccion()) {
            String modulo = moduloDe(fuente);
            String texto = leer(fuente);
            for (Map.Entry<String, String> puerto : puertos.entrySet()) {
                if (puerto.getValue().equals(modulo)) {
                    continue;
                }
                if (mencionaElTipo(texto, puerto.getKey())) {
                    consumidores.get(puerto.getKey()).add(fuente.getFileName().toString());
                }
            }
        }
        return consumidores;
    }

    private static boolean esUnPuerto(Path archivo, String nombre) {
        String texto = leer(archivo);
        return texto.contains("public interface " + nombre);
    }

    /**
     * La mencion tiene que ser al TIPO y no a una subcadena.
     *
     * <p>Sin el limite de palabra, {@code FrentesDelPredio} casaria dentro de {@code
     * FrentesDelPredioHttp} y un puerto quedaria «consumido» por su propio adaptador con otro
     * nombre.
     */
    private static boolean mencionaElTipo(String texto, String tipo) {
        java.util.regex.Matcher busqueda =
                java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(tipo) + "\\b")
                        .matcher(texto);
        return busqueda.find();
    }

    private static Set<Path> fuentesDeProduccion() {
        Path backend = RaizDelRepositorio.ruta().resolve("backend");
        Set<Path> fuentes = new LinkedHashSet<>();
        try (Stream<Path> recorrido = Files.walk(backend)) {
            recorrido
                    .filter(ruta -> ruta.toString().endsWith(".java"))
                    .filter(ruta -> ruta.toString().contains("/src/main/java/"))
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
