package kamayuk.rentas.verificaciones;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Stream;
import kamayuk.rentas.KamayukAplicacion;
import kamayuk.rentas.dominio.ZonaHoraria;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Que no vuelva un reloj sin la zona del producto (#316).
 *
 * <p>Hasta #316 el reloj era {@code Clock.systemDefaultZone()}, y los {@code LocalDate.now(reloj)}
 * de {@code src/main} fechaban con la zona del servidor. Que el bean lleve {@link
 * ZonaHoraria#DEL_PRODUCTO} lo prueba {@code ElDiaDelRelojEsElDelProductoTest} en la franja de las
 * 19:00 a la medianoche. Esto vigila las salidas de al lado: (1) un segundo reloj fabricado fuera
 * del bean, (2) leer la zona del servidor sin reloj —{@code ZoneId.systemDefault()}, {@code
 * TimeZone.getDefault()}, el {@code now()} sin argumentos de un tipo con zona— o cablear una en
 * {@code now(ZoneId)}, y (3) escribir el identificador de la zona en otro sitio.
 *
 * <p><b>Ruido medido el 2026-09-23</b> sobre las 1 844 clases de produccion: la regla (1) sin
 * exencion marca <b>un</b> sitio —el propio bean, que es el que tiene que fabricarlo— y la (2),
 * <b>cero</b>. No hay lista de excepciones que mantener. Con el importador de {@code
 * ReglasDeArquitectura.clasesDeProduccion()} saldrian dos mas, los {@code Clock.systemUTC()} de los
 * fixtures de {@code parametros}: su filtro busca {@code testFixtures} y el jar llega como {@code
 * -test-fixtures.jar}. Por eso el importador de aqui descarta las dos grafias.
 *
 * <p>Vive aqui y no en {@code comun-verificaciones} porque su sujeto es un bean de este artefacto y
 * una constante de este producto. Por eso no la alcanza {@code ReglasDeArquitecturaMuerdenTest}, y
 * que muerde lo demuestra {@link #lasReglasMuerdenSobreSuMuestra()}.
 */
@DisplayName("#316 — Ningun reloj sin la zona del producto")
class NingunRelojSinLaZonaDelProductoTest {

    /** Los tipos cuyo {@code now()} depende de una zona. {@code Instant} no la tiene. */
    private static final Set<String> TIPOS_CON_ZONA =
            Set.of(
                    "java.time.LocalDate",
                    "java.time.LocalDateTime",
                    "java.time.LocalTime",
                    "java.time.ZonedDateTime",
                    "java.time.OffsetDateTime",
                    "java.time.OffsetTime",
                    "java.time.Year",
                    "java.time.YearMonth",
                    "java.time.MonthDay");

    /** Las fabricas estaticas de {@link Clock} y {@code withZone}. */
    private static final Set<String> FABRICAS_DE_RELOJ =
            Set.of(
                    "system",
                    "systemUTC",
                    "systemDefaultZone",
                    "fixed",
                    "offset",
                    "tick",
                    "tickMillis",
                    "tickSeconds",
                    "tickMinutes",
                    "withZone");

    private static final DescribedPredicate<JavaMethodCall> FABRICA_UN_RELOJ =
            DescribedPredicate.describe(
                    "fabrica un reloj (una fabrica estatica de Clock, o Clock.withZone)",
                    llamada ->
                            llamada.getTargetOwner().isEquivalentTo(Clock.class)
                                    && FABRICAS_DE_RELOJ.contains(llamada.getName()));

    private static final DescribedPredicate<JavaMethodCall> LEE_LA_ZONA_DEL_SERVIDOR =
            DescribedPredicate.describe(
                    "lee la zona del servidor o cablea una (ZoneId.systemDefault,"
                            + " TimeZone.getDefault, now() o now(ZoneId) de un tipo con zona)",
                    llamada -> {
                        String duenio = llamada.getTargetOwner().getFullName();
                        String nombre = llamada.getName();
                        List<String> parametros =
                                llamada.getTarget().getRawParameterTypes().stream()
                                        .map(tipo -> tipo.getFullName())
                                        .toList();
                        return (duenio.equals("java.time.ZoneId") && nombre.equals("systemDefault"))
                                || (duenio.equals("java.util.TimeZone")
                                        && nombre.equals("getDefault"))
                                || (TIPOS_CON_ZONA.contains(duenio)
                                        && nombre.equals("now")
                                        && (parametros.isEmpty()
                                                || parametros.equals(List.of("java.time.ZoneId"))));
                    });

    static final ArchRule SOLO_EL_BEAN_FABRICA_EL_RELOJ =
            noClasses()
                    .that()
                    .resideInAPackage("kamayuk.rentas..")
                    .and()
                    .doNotHaveFullyQualifiedName(KamayukAplicacion.class.getName())
                    .should()
                    .callMethodWhere(FABRICA_UN_RELOJ)
                    .because(
                            "el reloj es uno, lo inyecta Spring y lleva ZonaHoraria.DEL_PRODUCTO"
                                    + " (#316): cualquier otro puede llevar la zona del servidor");

    static final ArchRule NADIE_LEE_LA_ZONA_DEL_SERVIDOR =
            noClasses()
                    .that()
                    .resideInAPackage("kamayuk.rentas..")
                    .should()
                    .callMethodWhere(LEE_LA_ZONA_DEL_SERVIDOR)
                    .because(
                            "el dia se lee del reloj inyectado, que lleva la zona del producto"
                                    + " (#316): la del servidor fecha manana lo que en Catacaos"
                                    + " ocurre despues de las 19:00");

    private static JavaClasses produccion;

    @BeforeAll
    static void importar() {
        produccion =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .withImportOption(
                                ubicacion ->
                                        !ubicacion.contains("testFixtures")
                                                && !ubicacion.contains("test-fixtures"))
                        .importPackages("kamayuk.rentas");
    }

    @Test
    @DisplayName("ninguna otra clase de produccion fabrica un reloj")
    void soloElBeanFabricaElReloj() {
        SOLO_EL_BEAN_FABRICA_EL_RELOJ.check(produccion);
    }

    @Test
    @DisplayName("nadie lee la zona del servidor ni cablea otra")
    void nadieLeeLaZonaDelServidor() {
        NADIE_LEE_LA_ZONA_DEL_SERVIDOR.check(produccion);
    }

    @Test
    @DisplayName("y hay relojes que mirar: el bean fabrica el suyo y el codigo lo lee")
    void hayRelojesQueMirar() {
        // Una regla de «nadie hace X» sale verde tambien cuando el importador no ve nada.
        assertThat(
                        produccion.get(KamayukAplicacion.class).getMethodCallsFromSelf().stream()
                                .filter(FABRICA_UN_RELOJ)
                                .count())
                .as("el bean fabrica su reloj, y solo uno: si no, la exencion no exime a nadie")
                .isEqualTo(1);

        long lecturasDelDia =
                produccion.stream()
                        .flatMap(clase -> clase.getMethodCallsFromSelf().stream())
                        .filter(
                                llamada ->
                                        llamada.getTargetOwner().isEquivalentTo(LocalDate.class)
                                                && llamada.getName().equals("now")
                                                && llamada
                                                        .getTarget()
                                                        .getRawParameterTypes()
                                                        .stream()
                                                        .anyMatch(
                                                                t -> t.isEquivalentTo(Clock.class)))
                        .count();
        assertThat(lecturasDelDia)
                .as(
                        "los LocalDate.now(reloj) de produccion (126 el 2026-09-23): si baja de"
                                + " cien, el importador dejo de verlos y las reglas salen verdes"
                                + " sin mirar nada")
                .isGreaterThan(100);
    }

    @Test
    @DisplayName("las dos reglas muerden sobre su muestra")
    void lasReglasMuerdenSobreSuMuestra() {
        JavaClasses muestra =
                new ClassFileImporter().importClasses(MuestraDeRelojesSinLaZonaDelProducto.class);

        assertThat(SOLO_EL_BEAN_FABRICA_EL_RELOJ.evaluate(muestra).getFailureReport().getDetails())
                .as("los tres relojes fabricados fuera del bean")
                .hasSize(3)
                .anyMatch(detalle -> detalle.contains("Clock.systemDefaultZone()"))
                .anyMatch(detalle -> detalle.contains("Clock.systemUTC()"))
                .anyMatch(detalle -> detalle.contains("Clock.withZone("));

        assertThat(NADIE_LEE_LA_ZONA_DEL_SERVIDOR.evaluate(muestra).getFailureReport().getDetails())
                .as("las cuatro lecturas de la zona del servidor, y ni una mas")
                .hasSize(4)
                .anyMatch(detalle -> detalle.contains("ZoneId.systemDefault()"))
                .anyMatch(detalle -> detalle.contains("TimeZone.getDefault()"))
                .anyMatch(detalle -> detalle.contains("LocalDate.now()"))
                .anyMatch(detalle -> detalle.contains("LocalDate.now(java.time.ZoneId)"));
    }

    @Test
    @DisplayName("el identificador de la zona sigue escrito una sola vez en backend/*/src/main")
    void laZonaSeEscribeUnaSolaVez() throws IOException {
        String identificador = ZonaHoraria.DEL_PRODUCTO.getId();
        Path backend = RaizDelRepositorio.ruta().resolve("backend");
        List<String> apariciones;
        try (Stream<Path> modulos = Files.list(backend)) {
            apariciones =
                    modulos.map(modulo -> modulo.resolve("src/main"))
                            .filter(Files::isDirectory)
                            .flatMap(NingunRelojSinLaZonaDelProductoTest::archivos)
                            .flatMap(
                                    archivo ->
                                            lineas(archivo).stream()
                                                    .filter(linea -> linea.contains(identificador))
                                                    .map(
                                                            linea ->
                                                                    backend.relativize(archivo)
                                                                            + ": "
                                                                            + linea.strip()))
                            .toList();
        }

        assertThat(apariciones)
                .as(
                        "la zona del producto se escribe en ZonaHoraria y en ningun otro sitio: un"
                                + " segundo ZoneId.of(...) es un reloj que puede dejar de coincidir")
                .singleElement()
                .asString()
                .contains("ZonaHoraria.java");
    }

    private static Stream<Path> archivos(Path raiz) {
        try (Stream<Path> todos = Files.walk(raiz)) {
            return todos.filter(Files::isRegularFile).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> lineas(Path archivo) {
        try {
            return Files.readString(archivo, StandardCharsets.ISO_8859_1).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Todo lo que las dos reglas prohiben, una vez cada cosa. */
    @SuppressWarnings("unused")
    static final class MuestraDeRelojesSinLaZonaDelProducto {

        Clock delServidor() {
            return Clock.systemDefaultZone();
        }

        Clock enUtc() {
            return Clock.systemUTC();
        }

        Clock cambiadoDeZona(Clock reloj) {
            return reloj.withZone(ZoneId.of("UTC"));
        }

        ZoneId zonaDelServidor() {
            return ZoneId.systemDefault();
        }

        TimeZone zonaDelServidorALaAntigua() {
            return TimeZone.getDefault();
        }

        LocalDate hoySinReloj() {
            return LocalDate.now();
        }

        LocalDate hoyEnUnaZonaCableada() {
            return LocalDate.now(ZoneId.of("UTC"));
        }
    }
}
