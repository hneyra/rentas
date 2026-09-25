package kamayuk.rentas.verificaciones;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.LoadInstruction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * {@code now(ZoneId)}, (3) escribir el identificador de la zona en otro sitio, y (4) —desde #325—
 * truncar un instante a un dia con una zona escrita a mano, que es la forma exacta del defecto de
 * #273 y la unica que las dos reglas primeras no veian ({@link
 * #NADIE_TRUNCA_UN_INSTANTE_CON_OTRA_ZONA}).
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
 * que muerde lo demuestran {@link #lasReglasMuerdenSobreSuMuestra()} y {@link
 * #laTerceraMuerdeSobreSuMuestra()}.
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

    /**
     * La tercera regla (#325): <b>nadie trunca un instante a un dia con una zona que no sea {@link
     * ZonaHoraria#DEL_PRODUCTO}</b>.
     *
     * <p>Las dos de arriba cierran la puerta grande —el reloj del contexto— y dejaban abierta la
     * pequena, que es por donde entro el defecto la primera vez: {@code
     * instante.atZone(ZoneOffset.UTC).toLocalDate()} en {@code InternamientoRepositoryJdbc} y
     * {@code LocalDate.ofInstant(instante, ZoneOffset.UTC)} en {@code LiberarVehiculoInternado}
     * cobraban un dia de custodia de menos y rechazaban una liberacion legitima (#273). Ninguna de
     * las dos formas fabrica un {@code Clock} ni lee la zona del servidor, asi que pasaban las dos
     * reglas: <b>medido</b>, con {@code InternamientoRepositoryJdbc} devuelto a su forma de #273,
     * las 291 pruebas de este modulo salian en verde.
     *
     * <p><b>Por que no basta ArchUnit.</b> ArchUnit ve que se llama a {@code LocalDate.ofInstant},
     * pero no <b>con que argumento</b>: la zona es un operando de la pila y no un acceso que el
     * importador registre. Asi que la condicion lee el bytecode con la API {@code
     * java.lang.classfile} del JDK —sin dependencia nueva— y mira la instruccion que apila la zona,
     * que es la inmediatamente anterior a la llamada: el ultimo argumento se evalua el ultimo. Solo
     * {@code GETSTATIC ZonaHoraria.DEL_PRODUCTO} pasa. Un {@code ZoneOffset.UTC}, un {@code
     * ZoneId.of(...)}, una constante propia —que es como estaba escrito en #273: {@code private
     * static final ZoneOffset UTC}— o una variable son rojos, porque de ninguno se sabe que sea la
     * zona del producto.
     *
     * <p><b>Que formas mira</b>, las dos que nombra el issue: {@code LocalDate.ofInstant(_, zona)}
     * y {@code instante.atZone(zona).toLocalDate()} encadenado. <b>No mira {@code atOffset}</b>, y
     * es a proposito: los cuatro {@code atOffset(ZoneOffset.UTC)} de {@code src/main} —tres en
     * {@code AplicarUnEventoDeIdentidad} y uno en {@code ValorMasivoRepositoryJdbc}— van como
     * parametro a una columna {@code timestamptz} y no truncan nada; cubrir {@code atOffset} los
     * marcaria a los cuatro. <b>Ruido medido el 2026-09-25</b> sobre las clases de produccion:
     * cero; el unico truncamiento que la regla encuentra es el de {@link ZonaHoraria#diaDe}, con la
     * zona del producto.
     */
    static final ArchRule NADIE_TRUNCA_UN_INSTANTE_CON_OTRA_ZONA =
            classes()
                    .that()
                    .resideInAPackage("kamayuk.rentas..")
                    .should(new TruncaSoloConLaZonaDelProducto())
                    .because(
                            "el dia de un instante es el de la zona del producto (#325): truncar en"
                                    + " UTC fecha manana lo que en Catacaos ocurre despues de las"
                                    + " 19:00, y eso cobro un dia de custodia de menos (#273)."
                                    + " Se trunca con ZonaHoraria.diaDe(instante)");

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
    @DisplayName("nadie trunca un instante a un dia con una zona que no sea la del producto")
    void nadieTruncaUnInstanteConOtraZona() {
        NADIE_TRUNCA_UN_INSTANTE_CON_OTRA_ZONA.check(produccion);
    }

    @Test
    @DisplayName("y la tercera lee el bytecode de verdad: ve el truncamiento de ZonaHoraria.diaDe")
    void laTerceraVeElTruncamientoDeLaZonaHoraria() {
        // Si la lectura del bytecode no encontrara nada —un origen que no se abre, una forma que
        // deja de casar—, la regla saldria verde sin haber mirado. El truncamiento de referencia
        // es el unico que hay hoy en produccion, y tiene que verse, y con la zona del producto.
        List<Truncamiento> truncamientos =
                produccion.stream().flatMap(clase -> truncamientosDe(clase).stream()).toList();

        assertThat(truncamientos)
                .as("los truncamientos de produccion que la tercera regla ve")
                .anySatisfy(
                        truncamiento -> {
                            assertThat(truncamiento.donde())
                                    .contains(ZonaHoraria.class.getName() + ".diaDe");
                            assertThat(truncamiento.conLaZonaDelProducto()).isTrue();
                        });
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
    @DisplayName("la tercera muerde sobre su muestra, y no marca lo que no trunca")
    void laTerceraMuerdeSobreSuMuestra() {
        JavaClasses muestra =
                new ClassFileImporter().importClasses(MuestraDeDiasTruncadosConOtraZona.class);

        assertThat(
                        NADIE_TRUNCA_UN_INSTANTE_CON_OTRA_ZONA
                                .evaluate(muestra)
                                .getFailureReport()
                                .getDetails())
                .as(
                        "los cinco truncamientos con otra zona, y ni uno mas: ni los dos con la"
                                + " zona del producto ni los dos que pasan por UTC sin truncar")
                .hasSize(5)
                .anyMatch(detalle -> detalle.contains(".enUtc>") && detalle.contains("ofInstant"))
                .anyMatch(
                        detalle ->
                                detalle.contains(".enUtcEncadenado>")
                                        && detalle.contains("atZone(ZoneOffset.UTC).toLocalDate()"))
                .anyMatch(
                        detalle ->
                                detalle.contains(".comoEnElDefectoDe273>")
                                        && detalle.contains(
                                                "atZone(MuestraDeDiasTruncadosConOtraZona.UTC)"))
                .anyMatch(detalle -> detalle.contains("ofInstant(_, ZoneId.of(...))"))
                .anyMatch(detalle -> detalle.contains("ofInstant(_, una variable)"))
                .noneMatch(detalle -> detalle.contains("ConLaZonaDelProducto>"))
                .noneMatch(detalle -> detalle.contains("paraUnaColumnaTimestamptz"));
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

    /**
     * Un sitio donde un instante se trunca a un dia, y con que zona.
     *
     * @param donde el metodo y la linea, con la forma de ArchUnit: {@code Method <x.y> ... (X:n)}
     * @param forma lo que esta escrito, con la zona dentro: {@code LocalDate.ofInstant(_, zona)} o
     *     {@code atZone(zona).toLocalDate()}
     * @param conLaZonaDelProducto si la zona es {@code GETSTATIC ZonaHoraria.DEL_PRODUCTO}
     */
    record Truncamiento(String donde, String forma, boolean conLaZonaDelProducto) {

        @Override
        public String toString() {
            return donde + " trunca un instante a un dia con " + forma;
        }
    }

    /** El nombre interno de la constante, que es lo unico que la tercera regla deja pasar. */
    private static final String ZONA_HORARIA = ZonaHoraria.class.getName().replace('.', '/');

    /**
     * Los truncamientos de un instante a un dia que hay en el bytecode de una clase (#325).
     *
     * <p>Primero pregunta a ArchUnit si la clase llama a {@code LocalDate.ofInstant} o a {@code
     * Instant.atZone}: si no, no hay nada que leer, y asi solo se abre el bytecode de las pocas
     * clases que pueden truncar. Las lambdas no se escapan: javac las compila como metodos
     * sinteticos de la misma clase, y se recorren todos los metodos.
     */
    static List<Truncamiento> truncamientosDe(JavaClass clase) {
        boolean puedeTruncar =
                clase.getMethodCallsFromSelf().stream()
                        .anyMatch(
                                llamada ->
                                        (llamada.getTargetOwner().isEquivalentTo(LocalDate.class)
                                                        && llamada.getName().equals("ofInstant"))
                                                || (llamada.getTargetOwner()
                                                                .isEquivalentTo(Instant.class)
                                                        && llamada.getName().equals("atZone")));
        if (!puedeTruncar) {
            return List.of();
        }

        ClassModel modelo = ClassFile.of().parse(bytecodeDe(clase));
        String archivo =
                modelo.findAttribute(Attributes.sourceFile())
                        .map(atributo -> atributo.sourceFile().stringValue())
                        .orElse(clase.getSimpleName() + ".java");
        List<Truncamiento> truncamientos = new ArrayList<>();
        for (MethodModel metodo : modelo.methods()) {
            metodo.code()
                    .ifPresent(
                            codigo ->
                                    truncamientosDe(
                                            clase.getFullName()
                                                    + "."
                                                    + metodo.methodName().stringValue(),
                                            archivo,
                                            codigo,
                                            truncamientos));
        }
        return truncamientos;
    }

    private static void truncamientosDe(
            String metodo, String archivo, CodeModel codigo, List<Truncamiento> truncamientos) {
        // Las instrucciones en orden, cada una con su linea. Las etiquetas, las tablas de
        // variables y los marcos de pila no apilan nada y se saltan.
        List<Instruction> instrucciones = new ArrayList<>();
        List<Integer> lineas = new ArrayList<>();
        int linea = 0;
        for (CodeElement elemento : codigo) {
            if (elemento instanceof LineNumber numero) {
                linea = numero.line();
            } else if (elemento instanceof Instruction instruccion) {
                instrucciones.add(instruccion);
                lineas.add(linea);
            }
        }

        for (int i = 1; i < instrucciones.size(); i++) {
            if (!(instrucciones.get(i) instanceof InvokeInstruction llamada)) {
                continue;
            }
            // La zona es el ULTIMO argumento de las dos llamadas, y se evalua la ultima: la
            // instruccion que la apila es la inmediatamente anterior.
            Instruction zona = instrucciones.get(i - 1);
            String forma;
            if (esLlamadaA(llamada, "java/time/LocalDate", "ofInstant")) {
                forma = "LocalDate.ofInstant(_, " + describir(zona) + ")";
            } else if (esLlamadaA(llamada, "java/time/Instant", "atZone")
                    && i + 1 < instrucciones.size()
                    && instrucciones.get(i + 1) instanceof InvokeInstruction siguiente
                    && esLlamadaA(siguiente, "java/time/ZonedDateTime", "toLocalDate")) {
                forma = "atZone(" + describir(zona) + ").toLocalDate()";
            } else {
                continue;
            }
            truncamientos.add(
                    new Truncamiento(
                            "Method <" + metodo + "> en (" + archivo + ":" + lineas.get(i) + ")",
                            forma,
                            zona instanceof FieldInstruction campo
                                    && campo.opcode() == Opcode.GETSTATIC
                                    && campo.owner().asInternalName().equals(ZONA_HORARIA)
                                    && campo.name().equalsString("DEL_PRODUCTO")));
        }
    }

    private static boolean esLlamadaA(InvokeInstruction llamada, String duenio, String nombre) {
        return llamada.owner().asInternalName().equals(duenio)
                && llamada.name().equalsString(nombre);
    }

    /** Lo que apila la zona, escrito como se leeria en la fuente. */
    private static String describir(Instruction zona) {
        if (zona instanceof FieldInstruction campo) {
            return nombreCorto(campo.owner().asInternalName()) + "." + campo.name().stringValue();
        }
        if (zona instanceof InvokeInstruction llamada) {
            return nombreCorto(llamada.owner().asInternalName())
                    + "."
                    + llamada.name().stringValue()
                    + "(...)";
        }
        if (zona instanceof LoadInstruction) {
            return "una variable";
        }
        return "lo que apila " + zona.opcode();
    }

    private static String nombreCorto(String nombreInterno) {
        String sinPaquete = nombreInterno.substring(nombreInterno.lastIndexOf('/') + 1);
        return sinPaquete.substring(sinPaquete.lastIndexOf('$') + 1);
    }

    /**
     * El bytecode tal como ArchUnit lo importo: del directorio de clases o de dentro de un jar.
     *
     * <p>Si no hay origen, la regla falla en vez de saltarse la clase: una clase que no se puede
     * leer es una clase que no se miro.
     */
    private static byte[] bytecodeDe(JavaClass clase) {
        Source origen =
                clase.getSource()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "ArchUnit no sabe de donde leyo "
                                                        + clase.getName()
                                                        + ": sin su bytecode no se ve con que"
                                                        + " zona trunca"));
        try (InputStream entrada = origen.getUri().toURL().openStream()) {
            return entrada.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class TruncaSoloConLaZonaDelProducto extends ArchCondition<JavaClass> {

        TruncaSoloConLaZonaDelProducto() {
            super("truncar un instante a un dia solo con ZonaHoraria.DEL_PRODUCTO");
        }

        @Override
        public void check(JavaClass clase, ConditionEvents eventos) {
            truncamientosDe(clase).stream()
                    .filter(truncamiento -> !truncamiento.conLaZonaDelProducto())
                    .forEach(
                            truncamiento ->
                                    eventos.add(
                                            SimpleConditionEvent.violated(
                                                    clase, truncamiento.toString())));
        }
    }

    /**
     * Lo que la tercera regla prohibe, una vez cada cosa, y a su lado lo que tiene que dejar pasar.
     */
    @SuppressWarnings("unused")
    static final class MuestraDeDiasTruncadosConOtraZona {

        /** Como estaba escrito en {@code InternamientoRepositoryJdbc} antes de #273. */
        private static final ZoneOffset UTC = ZoneOffset.UTC;

        LocalDate enUtc(Instant instante) {
            return LocalDate.ofInstant(instante, ZoneOffset.UTC);
        }

        LocalDate enUtcEncadenado(Instant instante) {
            return instante.atZone(ZoneOffset.UTC).toLocalDate();
        }

        LocalDate comoEnElDefectoDe273(Instant instante) {
            return instante.atZone(UTC).toLocalDate();
        }

        LocalDate enUnaZonaFabricada(Instant instante) {
            return LocalDate.ofInstant(instante, ZoneId.of("UTC"));
        }

        LocalDate enLaZonaQueLeLlegue(Instant instante, ZoneId zona) {
            return LocalDate.ofInstant(instante, zona);
        }

        LocalDate ofInstantConLaZonaDelProducto(Instant instante) {
            return LocalDate.ofInstant(instante, ZonaHoraria.DEL_PRODUCTO);
        }

        LocalDate encadenadoConLaZonaDelProducto(Instant instante) {
            return instante.atZone(ZonaHoraria.DEL_PRODUCTO).toLocalDate();
        }

        /** Los cuatro de {@code src/main} son asi: el mismo instante, sin truncar nada. */
        OffsetDateTime paraUnaColumnaTimestamptz(Instant instante) {
            return instante.atOffset(ZoneOffset.UTC);
        }

        /** Tampoco trunca: pasa por UTC y se queda en instante, que es lo mismo que atOffset. */
        OffsetDateTime paraUnaColumnaTimestamptzPorAtZone(Instant instante) {
            return instante.atZone(ZoneOffset.UTC).toOffsetDateTime();
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
