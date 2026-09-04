package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import kamayuk.comun.verificaciones.RevisorDeCodigoFuente.Hallazgo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #691 — Todo 422 de «falta publicar una cifra normativa» lleva su discriminador.
 *
 * <p>#604 puso el miembro {@code parametroQueFalta} en el cuerpo del problema y lo cableo en las
 * tres capturas de {@code ConvenioController}. Las otras veintidos de los demas modulos seguian
 * contestando un 422 con {@code codigo} y {@code mensaje} y nada mas. <b>Dentro de tesoreria la
 * ausencia del miembro significa «es un campo de la peticion»</b>, y eso es cierto porque sus rutas
 * siempre lo llevan; aplicada fuera, esa regla manda a quien atiende a buscar en el formulario un
 * dato que no esta mal, cuando lo que falta es sellar un conjunto de parametros.
 *
 * <p>Arreglar los veintidos sitios no cierra el defecto: lo cierra que el veintitres no pueda nacer
 * mudo. El sintoma de «este 422 no lleva el miembro» es exactamente el mismo que el de «este 422 es
 * de un campo», asi que sin guarda el hueco se vuelve a abrir con el modulo siguiente que se
 * conecte —y eso es literalmente lo que paso entre #604 y este issue—.
 */
@DisplayName("#691 — El discriminador de lo que falta publicar, en los ocho modulos")
class DiscriminadorDeLoQueFaltaPublicarTest {

    @Test
    @DisplayName("ningun catch del backend traduce «falta publicar» a un 422 sin el discriminador")
    void ningunCatchTraduceSinElDiscriminador() throws IOException {
        Map<String, String> fuentes = fuentesDeProduccion();

        assertThat(fuentes)
                .as("si el recorrido no encuentra fuentes, la prueba pasa sin revisar nada")
                .hasSizeGreaterThan(200);

        Set<String> familia = RevisorDelDiscriminador.familiaSegunLasFuentes(fuentes);

        List<Hallazgo> hallazgos = new ArrayList<>();
        fuentes.forEach(
                (nombre, contenido) ->
                        hallazgos.addAll(
                                RevisorDelDiscriminador.revisar(nombre, contenido, familia)));

        assertThat(hallazgos).isEmpty();
    }

    @Test
    @DisplayName("la familia se computa del codigo fuente, no de una lista escrita a mano")
    void laFamiliaSaleDelCodigoFuente() throws IOException {
        Set<String> familia = RevisorDelDiscriminador.familiaSegunLasFuentes(fuentesDeProduccion());

        assertThat(familia)
                .as(
                        "toda excepcion que declare ParametroSinPublicar entra sola; anadir una no"
                                + " obliga a tocar la guarda")
                .contains(
                        "EjercicioSinSellar",
                        "ParametroAusente",
                        "CondicionSinParametrizar",
                        "SinPuntosObservados",
                        "PuntoSinObservar",
                        "MediaPolitica",
                        "EscalaNoEntera",
                        "ModoDesconocido",
                        "PlazoSinParametrizar",
                        "ArancelSinParametrizar",
                        "TasaSinParametrizar",
                        "DerechoSinParametrizar",
                        "ParametroDelPredialAusente",
                        "CampaniaSinParametrizar",
                        "CampaniaIncompleta",
                        "BaseDesconocida",
                        "CondicionesSinPublicar");
        // El censo va PRIMERO, y no es un detalle de orden: AssertJ para en la primera
        // asercion que falla, asi que con el `contains` delante el rojo nombraria la
        // excepcion y no diria cuantas quedan. Asi la primera cifra que se lee es «18
        // donde deberia haber 19», que es lo que el AC 2 de #723 pide.
        assertThat(familia)
                .as(
                        "y el censo no puede encoger sin que se vea. Es un suelo y no una cifra"
                                + " exacta a proposito: anadir una excepcion a la familia no obliga a"
                                + " tocar esta guarda, pero quitarle la interfaz a una si la pone roja"
                                + " diciendo las dos cifras")
                .hasSizeGreaterThanOrEqualTo(19);
        assertThat(familia)
                .as(
                        "y la del cuadro de valores unitarios, que tenia llave() y no declaraba la"
                                + " interfaz: hoy no la traduce nadie a una respuesta, y por eso"
                                + " justamente la trampa solo saltaba al pisarla (#723)")
                .contains("ValorUnitarioSinParametrizar");
        assertThat(familia)
                .as(
                        "y la del dominio puro, que no puede declararla: vive bajo la interfaz en el"
                                + " grafo de modulos y no sabe de que ejercicio son sus politicas")
                .contains(RevisorDelDiscriminador.LA_DEL_DOMINIO_PURO);
        assertThat(familia)
                .as("una excepcion cualquiera no entra por parecerse en el nombre")
                .doesNotContain("IllegalArgumentException", "SinValorReferencial", "SinDireccion");
    }

    @Test
    @DisplayName("el escaner detecta la muestra que traduce a 422 sin el miembro")
    void elEscanerDetectaLaMuestra() throws IOException {
        // La muestra no vive en un literal de esta prueba sino en un archivo propio, y se lee del
        // disco: asi se verifica el escaner sobre un archivo de verdad, con su javadoc mencionando
        // el ayudante. Si el escaner contara los comentarios, los dos metodos en regla contarian
        // como violaciones y los tres malos se colarian por su comentario.
        Path muestra =
                raizDelBackend()
                        .resolve(
                                "kamayuk-rentas-aplicacion/src/test/java/kamayuk/rentas/verificaciones")
                        .resolve("muestras/web/MuestraDeControladorSinDiscriminador.java");

        assertThat(muestra).as("la muestra tiene que existir para poder detectarla").exists();

        List<Hallazgo> hallazgos =
                RevisorDelDiscriminador.revisar(
                        muestra.getFileName().toString(),
                        Files.readString(muestra, StandardCharsets.UTF_8),
                        RevisorDelDiscriminador.familiaSegunLasFuentes(fuentesDeProduccion()));

        assertThat(hallazgos)
                .as("los cuatro que traducen sin el miembro; los tres en regla no")
                .hasSize(4);
        assertThat(hallazgos.stream().map(Hallazgo::fragmento).toList())
                .as("y el hallazgo nombra la clase y el metodo, no «el catch de la linea 40»")
                .containsExactlyInAnyOrder(
                        "MuestraDeControladorSinDiscriminador.sinConjuntoSellado captura"
                                + " EjercicioSinSellar",
                        "MuestraDeControladorSinDiscriminador.sinLaFila captura ParametroAusente",
                        "MuestraDeControladorSinDiscriminador.sinElPuntoDeRedondeo captura"
                                + " PuntoSinPolitica",
                        "MuestraDeControladorSinDiscriminador.sinConjuntoSelladoEnUn404 captura"
                                + " EjercicioSinSellar");
    }

    @Test
    @DisplayName("el hallazgo nombra el METODO, no la anotacion que lo precede")
    void elHallazgoNombraElMetodoYNoLaAnotacion() {
        // Lo encontro la primera medicion de la guarda: con la mutacion puesta sobre
        // `PredialController`, el hallazgo decia «PredialController.PostMapping». La anotacion
        // `@PostMapping("/calculo-individual")` casa con el patron de un metodo y se come la firma
        // de su propio metodo hasta la llave del cuerpo, asi que el nombre util desaparecia.
        String fuente =
                """
                @RestController
                class EjemploController {
                    @PostMapping("/calculo-individual")
                    @ResponseStatus(HttpStatus.CREATED)
                    @RequiereAcceso(acceso = "predial_individual", privilegio = Privilegio.REGISTRO)
                    public Recurso calcularIndividual(
                            @RequestParam(required = false) @Nullable String ejercicio,
                            @RequestBody Peticion peticion) {
                        try {
                            return determinar(peticion);
                        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
                            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "falta");
                        }
                    }
                }
                """;
        assertThat(
                        RevisorDelDiscriminador.revisar(
                                "EjemploController.java", fuente, Set.of("EjercicioSinSellar")))
                .singleElement()
                .satisfies(
                        hallazgo ->
                                assertThat(hallazgo.fragmento())
                                        .as(
                                                "«el catch de la linea 400» no dice que endpoint quedo mudo")
                                        .isEqualTo(
                                                "EjemploController.calcularIndividual captura"
                                                        + " EjercicioSinSellar"));
    }

    @Test
    @DisplayName("un comentario que mencione el ayudante no deja pasar un catch mudo")
    void elComentarioNoCuenta() {
        String fuente =
                """
                class Ejemplo {
                    void malo() {
                        try {
                            leer();
                        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
                            // Aqui habria que usar FaltaPublicar.problema(sinSellar).
                            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "falta");
                        }
                    }
                }
                """;
        assertThat(
                        RevisorDelDiscriminador.revisar(
                                "Ejemplo.java", fuente, Set.of("EjercicioSinSellar")))
                .as("el compilador no lee comentarios, y la guarda tampoco")
                .hasSize(1);
    }

    @Test
    @DisplayName("un catch de la familia que no compone ninguna respuesta se deja pasar")
    void loQueNoContestaNadaNoEsAsuntoDeEstaGuarda() {
        String fuente =
                """
                class Ejemplo {
                    Fila contar() {
                        try {
                            return leer();
                        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
                            return Fila.sinDerecho(sinSellar.getMessage());
                        }
                    }
                }
                """;
        assertThat(
                        RevisorDelDiscriminador.revisar(
                                "Ejemplo.java", fuente, Set.of("EjercicioSinSellar")))
                .as(
                        "contar lo que si se puede contar y decir por que falta el resto no deja"
                                + " ninguna respuesta sin miembro: no hay respuesta")
                .isEmpty();
    }

    @Test
    @DisplayName("y uno que contesta 404 SI lo es: el miembro falta igual (#723)")
    void unCuatrocientosCuatroTambienNecesitaElMiembro() {
        String fuente =
                """
                class Ejemplo {
                    Cuadro cuadro() {
                        try {
                            return leerCuadro();
                        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
                            throw new ProblemaDeNegocio(
                                    CodigoDeError.NO_ENCONTRADO, sinSellar.getMessage());
                        }
                    }

                    Cuadro conMiembro() {
                        try {
                            return leerCuadro();
                        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
                            throw FaltaPublicar.noEncontrado(sinSellar);
                        }
                    }
                }
                """;
        assertThat(
                        RevisorDelDiscriminador.revisar(
                                "Ejemplo.java", fuente, Set.of("EjercicioSinSellar")))
                .as(
                        "#691 escribio el criterio literal —«a un 422»— y con el las tres lecturas"
                                + " de cuadro de catastro se quedaron fuera de la guarda, mudas. Lo que"
                                + " le falta a la respuesta no depende del numero")
                .singleElement()
                .satisfies(
                        hallazgo ->
                                assertThat(hallazgo.fragmento())
                                        .isEqualTo("Ejemplo.cuadro captura EjercicioSinSellar"));
    }

    @Test
    @DisplayName("un catch que no nombra ninguna de la familia no le importa a la guarda")
    void loQueNoEsDeLaFamiliaNoCuenta() {
        String fuente =
                """
                class Ejemplo {
                    void otro() {
                        try {
                            hacer();
                        } catch (IllegalArgumentException invalido) {
                            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "mal");
                        }
                    }
                }
                """;
        assertThat(
                        RevisorDelDiscriminador.revisar(
                                "Ejemplo.java", fuente, Set.of("EjercicioSinSellar")))
                .as("un campo que falta sigue siendo un 422 sin miembro, y eso es lo correcto")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    private static Map<String, String> fuentesDeProduccion() throws IOException {
        Path raiz = raizDelBackend();
        Map<String, String> fuentes = new LinkedHashMap<>();
        try (Stream<Path> rutas = Files.walk(raiz)) {
            for (Path ruta : rutas.filter(Files::isRegularFile).toList()) {
                String texto = ruta.toString().replace('\\', '/');
                if (!texto.contains("/src/main/")
                        || texto.contains("/build/")
                        || !texto.endsWith(".java")) {
                    continue;
                }
                fuentes.put(
                        raiz.relativize(ruta).toString(),
                        Files.readString(ruta, StandardCharsets.UTF_8));
            }
        }
        return fuentes;
    }

    /** El directorio de trabajo de la prueba es el del modulo; el backend esta encima. */
    private static Path raizDelBackend() {
        Path actual = Path.of("").toAbsolutePath();
        while (actual != null) {
            if (Files.exists(actual.resolve("settings.gradle.kts"))) {
                return actual;
            }
            actual = actual.getParent();
        }
        throw new IllegalStateException("No se encontro la raiz del build del backend");
    }
}
