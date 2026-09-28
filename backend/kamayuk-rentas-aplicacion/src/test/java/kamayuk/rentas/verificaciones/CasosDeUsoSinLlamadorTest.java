package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaStaticInitializer;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import kamayuk.rentas.verificaciones.muestras.aplicacion.MuestrasDeCasosDeUsoSinLlamador;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

/**
 * Los casos de uso que no tiene quien los llame (#394), hermana de {@link
 * PuertosSinConsumidorTest}.
 *
 * <h2>Que mide, y por que la guarda de los puertos no lo ve</h2>
 *
 * <p>{@link PuertosSinConsumidorTest} da por consumido un metodo de puerto si algun archivo de
 * {@code src/main} lo llama, sin preguntar si ESE archivo esta vivo. Y los metodos publicos de los
 * {@code @Service} de {@code aplicacion} —los casos de uso— no los censaba nadie. Asi paso, medido
 * en #394, que {@code FormalizarConvenio} se quedo sin llamador cuando {@code CobrarDeuda} se fue a
 * {@code caja} en P5D, y arrastro como «vivo» a {@code AcogimientoAConvenio#acoger}; y que la
 * generacion masiva de valores se registra y nadie la procesa.
 *
 * <h2>Por tipo del receptor, y no por nombre</h2>
 *
 * <p>Se cuenta en el <b>bytecode</b> y no en el fuente, al reves que la guarda de los puertos: ahi
 * un nombre de metodo se repite entre clases —{@code generar} esta en {@code GenerarCorridaMasiva},
 * {@code GenerarCorridaDeValores}, {@code GenerarMuestra} y en {@code documentos.generar(…)}— y un
 * censo por texto los confunde. ArchUnit resuelve la clase dueña del metodo llamado. Una llamada a
 * traves de una interfaz que el caso de uso implementa cuenta: el receptor es la interfaz, y el
 * caso de uso es su implementacion.
 *
 * <h2>Un llamador VIVO, marcado desde las raices (#629)</h2>
 *
 * <p>Hasta #629 bastaba un llamador desde OTRA clase de {@code src/main}, estuviera vivo o no: un
 * caso de uso que solo llamaba otro muerto pasaba por vivo. Ahora el llamador tiene que ser
 * <b>alcanzable</b>: se marca vivo lo que el marco llama por su cuenta —las {@link #esRaiz raices}—
 * y se propaga por las llamadas y las referencias a metodo del bytecode, con el despacho virtual
 * incluido (llamar a un metodo de una interfaz marca vivas sus implementaciones). Un caso de uso
 * esta vivo si lo llama, desde otra clase, un metodo que este marcado.
 *
 * <h2>La lista solo baja</h2>
 *
 * <p>Cada entrada nombra la dependencia que la bloquea, y {@link #laListaSoloBaja()} exige que
 * ninguna entrada haya recuperado su llamador sin salir de aqui.
 */
@DisplayName("#394 — ningun caso de uso publico se queda sin llamador")
class CasosDeUsoSinLlamadorTest {

    /**
     * Los casos de uso que hoy no tienen llamador, con lo que los bloquea. Clave: {@link #clave}.
     */
    private static final Map<String, String> SIN_LLAMADOR_CON_MOTIVO = new LinkedHashMap<>();

    static {
        SIN_LLAMADOR_CON_MOTIVO.put(
                "FormalizarConvenio#formalizar(NumeroDeConvenio, long, Dinero, LocalDate, Observacion)",
                "Lo llamaba `CobrarDeuda`, que se fue a `caja` en P5D: formalizar exige que `caja` avise del cobro de la cuota inicial. Bloqueado por `caja` (#430)");
        SIN_LLAMADOR_CON_MOTIVO.put(
                "FormalizarConvenio#cuotaInicialDe(NumeroDeConvenio)",
                "La leia `CobrarDeuda` para cobrar la cuota inicial en ventanilla, y se fue a `caja` en P5D (#430)");
        SIN_LLAMADOR_CON_MOTIVO.put(
                "AcogimientoAConvenioCuentaCorriente#acoger(long, List, LocalDate, String, Observacion)",
                "Solo lo llama `FormalizarConvenio#formalizar`, que esta en esta lista por `caja` (#430): hasta #629 ese llamador muerto lo hacia pasar por vivo");
        SIN_LLAMADOR_CON_MOTIVO.put(
                "RegistrarBeneficio#registrar(Beneficio, Observacion)",
                "El alta de un beneficio no se publica todavia: `BeneficioController` es de solo lectura y el contrato no declara POST (NEG-03)");
        SIN_LLAMADOR_CON_MOTIVO.put(
                "RegistrarBeneficio#cesar(long, LocalDate, Observacion)",
                "El cese de un beneficio no se publica todavia, por lo mismo que el alta");
        SIN_LLAMADOR_CON_MOTIVO.put(
                "RegistrarPapeleta#registrarTransito(String, String, LocalDate, LocalTime, String, String, Long, String, Long, Long, long, Dinero, Alicuota, Dinero, Alicuota, Dinero, Dinero, Observacion)",
                "El registro de papeletas no se publica todavia: `PapeletasController` es de solo lectura (RF-060)");
        SIN_LLAMADOR_CON_MOTIVO.put(
                "RegistrarPapeleta#registrarAdministrativa(String, String, LocalDate, LocalTime, String, Long, Long, Long, long, Dinero, Alicuota, Dinero, Alicuota, Dinero, Dinero, Observacion)",
                "Por lo mismo que la de transito");
        SIN_LLAMADOR_CON_MOTIVO.put(
                "DeterminarArbitrios#determinarPredio(long, Ejercicio, Observacion)",
                "La determinacion de arbitrios no se publica: sus tasas son de ordenanza local, bloqueado por D-02b (`ArbitriosController`)");
        SIN_LLAMADOR_CON_MOTIVO.put(
                "InsumosNormativosDeLaLiquidacion#de(LineaDeLiquidacion)",
                "La valorizacion de la liquidacion de fiscalizacion espera las cifras de D-02a: esta escrita para que el dia que lleguen no haya que escribirla (#49, bloqueado)");
        SIN_LLAMADOR_CON_MOTIVO.put(
                "MantenerCatalogoDeInfracciones#modificar(Familia, String, CodigoInfraccion, Observacion)",
                "Sin ruta ni pantalla: si el catalogo se mantiene desde la aplicacion o llega como dato de ordenanza esta por decidir (#632)");
        SIN_LLAMADOR_CON_MOTIVO.put(
                "MantenerCatalogoDeInfracciones#registrar(CodigoInfraccion, Observacion)",
                "Por lo mismo que modificar (#632)");
    }

    @Test
    @DisplayName(
            "todo metodo publico de un @Service de `aplicacion` tiene un llamador de otra clase")
    void todoCasoDeUsoTieneLlamador() {
        Set<String> sinLlamador = sinLlamador(produccion());
        sinLlamador.removeAll(SIN_LLAMADOR_CON_MOTIVO.keySet());

        assertThat(sinLlamador)
                .as(
                        "estos casos de uso no los llama nadie: o se cablean, o entran en la lista"
                                + " con la dependencia que los bloquea nombrada")
                .isEmpty();
    }

    @Test
    @DisplayName("la lista solo baja: una entrada que ya tiene llamador tiene que salir")
    void laListaSoloBaja() {
        Set<String> sinLlamador = sinLlamador(produccion());

        assertThat(SIN_LLAMADOR_CON_MOTIVO.keySet())
                .as("estas entradas ya tienen llamador, o ya no existen: hay que quitarlas")
                .allSatisfy(clave -> assertThat(sinLlamador).contains(clave));
    }

    @Test
    @DisplayName("la regla muerde sobre su muestra, y solo donde tiene que morder")
    void laReglaMuerdeSobreSuMuestra() {
        JavaClasses muestra =
                new ClassFileImporter()
                        .importClasses(
                                Stream.concat(
                                                Stream.of(MuestrasDeCasosDeUsoSinLlamador.class),
                                                Stream.of(
                                                        MuestrasDeCasosDeUsoSinLlamador.class
                                                                .getDeclaredClasses()))
                                        .toArray(Class<?>[]::new));

        assertThat(sinLlamador(muestra))
                .as(
                        "el huerfano, el que se llama solo a si mismo, y el que solo llama un muerto"
                                + " (#629); ni el del controlador, ni el que este llama, ni el de"
                                + " la interfaz, ni el de la corrida, ni el de la lambda")
                .containsExactlyInAnyOrder(
                        "Huerfano#hacer()",
                        "SeLlamaASiMismo#hacer()",
                        "SeLlamaASiMismo#otraVez()",
                        "MuertoQueLlama#hacer()",
                        "LlamadoSoloPorUnMuerto#hacer()");
    }

    @Test
    @DisplayName("el censo mira algo: hay casos de uso, y la mayoria tiene llamador")
    void elCensoMiraAlgo() {
        JavaClasses clases = produccion();
        long casosDeUso = metodosCandidatos(clases).count();

        assertThat(casosDeUso)
                .as(
                        "si el importador deja de ver los @Service de `aplicacion`, la regla sale"
                                + " verde sin mirar nada")
                .isGreaterThan(150);
    }

    // ------------------------------------------------------------------

    private static JavaClasses produccion() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(
                        ubicacion ->
                                !ubicacion.contains("testFixtures")
                                        && !ubicacion.contains("test-fixtures"))
                .importPackages("kamayuk.rentas");
    }

    /**
     * Los metodos publicos de instancia de las clases {@code @Service} de un paquete aplicacion.
     */
    private static Stream<JavaMethod> metodosCandidatos(JavaClasses clases) {
        return clases.stream()
                .filter(clase -> clase.getPackageName().contains(".aplicacion"))
                .filter(clase -> clase.isAnnotatedWith(Service.class))
                .flatMap(clase -> clase.getMethods().stream())
                .filter(metodo -> metodo.getModifiers().contains(JavaModifier.PUBLIC))
                .filter(metodo -> !metodo.getModifiers().contains(JavaModifier.STATIC))
                .filter(metodo -> !metodo.getModifiers().contains(JavaModifier.SYNTHETIC))
                .filter(metodo -> !metodo.getModifiers().contains(JavaModifier.BRIDGE));
    }

    /** {@code Clase#metodo} de cada caso de uso sin un llamador vivo de otra clase. */
    static Set<String> sinLlamador(JavaClasses clases) {
        Set<JavaCodeUnit> vivos = vivos(clases);
        Set<String> sinLlamador = new TreeSet<>();
        metodosCandidatos(clases)
                .filter(metodo -> !esRaiz(metodo) && !tieneLlamadorDeFuera(metodo, vivos))
                .forEach(metodo -> sinLlamador.add(clave(metodo)));
        return sinLlamador;
    }

    /** Lo que el marco llama por su cuenta, sin que ninguna llamada suya quede en el bytecode. */
    private static final Set<String> ANOTACIONES_DE_ENTRADA =
            Set.of(
                    "org.springframework.context.annotation.Bean",
                    "org.springframework.scheduling.annotation.Scheduled",
                    "org.springframework.context.event.EventListener",
                    "org.springframework.transaction.event.TransactionalEventListener",
                    "org.springframework.modulith.events.ApplicationModuleListener",
                    "jakarta.annotation.PostConstruct",
                    "jakarta.annotation.PreDestroy");

    /**
     * Una raiz: lo que se ejecuta sin que otro metodo de {@code kamayuk.rentas} lo llame.
     *
     * <ul>
     *   <li>los manejadores de Spring MVC: todo metodo con una anotacion de {@code
     *       org.springframework.web.bind.annotation} —{@code @GetMapping}, {@code @PostMapping},
     *       {@code @ExceptionHandler}…—, que es lo que publica una ruta;
     *   <li>lo anotado con {@link #ANOTACIONES_DE_ENTRADA}: {@code @Bean}, {@code @Scheduled}, los
     *       oyentes y el ciclo de vida;
     *   <li>lo que implementa o sobrescribe un metodo de un tipo de <b>fuera</b> de {@code
     *       kamayuk.rentas}: {@code ApplicationRunner#run} —las corridas del perfil {@code batch},
     *       las cargas y la implantacion—, los filtros, los conversores, {@code toString}… Lo llama
     *       el marco o el JDK, y desde aqui no se ve quien;
     *   <li>{@code main}, los inicializadores estaticos y los constructores de lo que Spring
     *       instancia (un {@code @Component} o lo que lo lleve como meta-anotacion).
     * </ul>
     *
     * <p>El tercer punto es conservador a proposito: un {@code Runnable} anonimo dentro de un
     * metodo muerto cuenta como raiz. Es preferible a acusar de muerto lo que llama el marco.
     */
    static boolean esRaiz(JavaCodeUnit unidad) {
        if (unidad instanceof JavaStaticInitializer) {
            return true;
        }
        if (unidad instanceof JavaConstructor) {
            return esDeSpring(unidad.getOwner());
        }
        if (unidad.getModifiers().contains(JavaModifier.STATIC)) {
            return unidad.getName().equals("main");
        }
        return unidad.getAnnotations().stream().anyMatch(CasosDeUsoSinLlamadorTest::esDeEntrada)
                || sobrescribeUnoDeFuera((JavaMethod) unidad);
    }

    private static boolean esDeEntrada(JavaAnnotation<?> anotacion) {
        String tipo = anotacion.getRawType().getName();
        return tipo.startsWith("org.springframework.web.bind.annotation.")
                || ANOTACIONES_DE_ENTRADA.contains(tipo);
    }

    private static boolean esDeSpring(JavaClass clase) {
        return clase.isMetaAnnotatedWith("org.springframework.stereotype.Component")
                || clase.isAnnotatedWith("org.springframework.stereotype.Component");
    }

    private static boolean sobrescribeUnoDeFuera(JavaMethod metodo) {
        return conLosQueImplementa(metodo)
                .skip(1)
                .anyMatch(otro -> !otro.getOwner().getPackageName().startsWith("kamayuk.rentas"));
    }

    /**
     * Lo alcanzable desde las raices, por llamadas y referencias a metodo o constructor. Llamar a
     * un metodo marca vivo el metodo que resuelve y todo lo que lo sobrescribe en los subtipos del
     * receptor: es el despacho virtual, y es por donde un controlador llega a un caso de uso a
     * traves de su interfaz. Una lambda no hace falta seguirla: ArchUnit atribuye lo que llama al
     * metodo que la escribe.
     */
    static Set<JavaCodeUnit> vivos(JavaClasses clases) {
        Set<JavaCodeUnit> vivos = new HashSet<>();
        Deque<JavaCodeUnit> pendientes = new ArrayDeque<>();
        clases.stream()
                .flatMap(clase -> clase.getCodeUnits().stream())
                .filter(CasosDeUsoSinLlamadorTest::esRaiz)
                .forEach(
                        raiz -> {
                            vivos.add(raiz);
                            pendientes.add(raiz);
                        });
        while (!pendientes.isEmpty()) {
            JavaCodeUnit unidad = pendientes.poll();
            Stream.concat(
                            unidad.getCallsFromSelf().stream().map(llamada -> llamada.getTarget()),
                            unidad.getCodeUnitReferencesFromSelf().stream()
                                    .map(referencia -> referencia.getTarget()))
                    .flatMap(CasosDeUsoSinLlamadorTest::conSusSobrescrituras)
                    .filter(vivos::add)
                    .forEach(pendientes::add);
        }
        return vivos;
    }

    /** El metodo o constructor al que resuelve la llamada, y lo que lo sobrescribe debajo. */
    private static Stream<JavaCodeUnit> conSusSobrescrituras(CodeUnitAccessTarget destino) {
        Stream<JavaCodeUnit> resuelto =
                destino.resolveMember().stream().map(JavaCodeUnit.class::cast);
        if (destino.getName().equals(JavaConstructor.CONSTRUCTOR_NAME)) {
            return resuelto;
        }
        List<String> parametros =
                destino.getRawParameterTypes().stream().map(JavaClass::getName).toList();
        Stream<JavaCodeUnit> debajo =
                destino.getOwner().getAllSubclasses().stream()
                        .flatMap(subtipo -> subtipo.getMethods().stream())
                        .filter(otro -> otro.getName().equals(destino.getName()))
                        .filter(
                                otro ->
                                        otro.getRawParameterTypes().stream()
                                                .map(JavaClass::getName)
                                                .toList()
                                                .equals(parametros))
                        .map(JavaCodeUnit.class::cast);
        return Stream.concat(resuelto, debajo);
    }

    /**
     * {@code Clase#metodo(Tipo, Tipo)}, con nombres simples. Los parametros van porque una
     * sobrecarga puede estar viva y la otra no: con el nombre solo, una ocultaria a la otra.
     */
    static String clave(JavaMethod metodo) {
        return metodo.getOwner().getSimpleName()
                + "#"
                + metodo.getName()
                + "("
                + String.join(
                        ", ",
                        metodo.getRawParameterTypes().stream()
                                .map(JavaClass::getSimpleName)
                                .toList())
                + ")";
    }

    /**
     * Si un metodo vivo de una clase distinta de la suya lo llama o lo referencia, directamente o
     * por un metodo que el suyo implementa —la interfaz o la superclase con el mismo nombre y los
     * mismos parametros—.
     */
    private static boolean tieneLlamadorDeFuera(JavaMethod metodo, Set<JavaCodeUnit> vivos) {
        JavaClass suya = metodo.getOwner();
        return conLosQueImplementa(metodo)
                .anyMatch(
                        destino ->
                                Stream.concat(
                                                destino.getCallsOfSelf().stream()
                                                        .map(llamada -> llamada.getOrigin()),
                                                destino.getReferencesToSelf().stream()
                                                        .map(referencia -> referencia.getOrigin()))
                                        .filter(vivos::contains)
                                        .map(JavaCodeUnit::getOwner)
                                        .anyMatch(origen -> !esLaMisma(origen, suya)));
    }

    /** El metodo y los que implementa o sobrescribe en sus supertipos. */
    private static Stream<JavaMethod> conLosQueImplementa(JavaMethod metodo) {
        List<String> parametros =
                metodo.getRawParameterTypes().stream().map(JavaClass::getName).toList();
        Stream<JavaMethod> heredados =
                Stream.concat(
                                metodo.getOwner().getAllRawInterfaces().stream(),
                                metodo.getOwner().getAllRawSuperclasses().stream())
                        .flatMap(supertipo -> supertipo.getMethods().stream())
                        .filter(otro -> otro.getName().equals(metodo.getName()))
                        .filter(
                                otro ->
                                        otro.getRawParameterTypes().stream()
                                                .map(JavaClass::getName)
                                                .toList()
                                                .equals(parametros));
        return Stream.concat(Stream.of(metodo), heredados);
    }

    /** La misma clase, o una anidada en ella: una llamada desde dentro no es un llamador. */
    private static boolean esLaMisma(JavaClass origen, JavaClass suya) {
        JavaClass exterior = origen;
        while (true) {
            if (exterior.equals(suya)) {
                return true;
            }
            if (exterior.getEnclosingClass().isEmpty()) {
                return false;
            }
            exterior = exterior.getEnclosingClass().get();
        }
    }
}
