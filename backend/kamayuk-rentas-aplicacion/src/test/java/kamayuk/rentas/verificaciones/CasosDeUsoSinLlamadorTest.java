package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
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
 * <h2>El paso mas pequeno, dicho</h2>
 *
 * <p>Exige un llamador desde OTRA clase de {@code src/main}, no que ese llamador sea alcanzable
 * desde un punto de entrada. El marcado desde raices —controladores, {@code ApplicationRunner}— es
 * el paso siguiente que #394 describe; este ya saca rojo lo que hoy esta muerto y no lo dice.
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

    private static final String SIN_DUENO =
            "sin llamador en src/main y sin dueño: se retira, se hace no publico o se cablea (#611)";

    static {
        SIN_LLAMADOR_CON_MOTIVO.put(
                "FormalizarConvenio#formalizar(NumeroDeConvenio, long, Dinero, LocalDate, Observacion)",
                "Lo llamaba `CobrarDeuda`, que se fue a `caja` en P5D: formalizar exige que `caja` avise del cobro de la cuota inicial. Bloqueado por `caja` (#430)");
        SIN_LLAMADOR_CON_MOTIVO.put(
                "FormalizarConvenio#cuotaInicialDe(NumeroDeConvenio)",
                "La leia `CobrarDeuda` para cobrar la cuota inicial en ventanilla, y se fue a `caja` en P5D (#430)");
        SIN_LLAMADOR_CON_MOTIVO.put(
                "ConciliacionDeCajaCuentaCorriente#abonadoPor(Collection, LocalDate)",
                "El cierre de caja ya no la pregunta desde P5D: el cierre vive en `caja` y no cuadra contra el libro (#280)");
        SIN_LLAMADOR_CON_MOTIVO.put(
                "ImprimirCorridaMasiva#imprimir(long, FormatoDeDocumento, Function)",
                "#400 cerro el PROCESAMIENTO de la generacion masiva, no su impresion: no hay ruta ni proceso que imprima la corrida (#611)");
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
        // Sin dueño todavia: la tabla de #611 dice, para cada uno, que pruebas lo ejercitan y
        // si es una sobrecarga que la ruta no usa, un metodo solo de pruebas o un heredado sin
        // ruta.
        SIN_LLAMADOR_CON_MOTIVO.put(
                "CambiarDireccionReferencial#cambiar(String, String, String, Observacion)",
                SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put(
                "CambiarDireccionReferencial#vigenteDe(ExpedienteCoactivo)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put(
                "CambiarEstadoDelExpediente#cambiar(String, EstadoDelExpediente, String, LocalDate, String, Observacion)",
                SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put("CambiarPlaca#cambiar(long, Placa, Observacion)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put("ConciliarConElPadron#huellasDeLaProyeccion()", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put(
                "ConciliarConElPadron#lotesQueDifieren(String, List)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put("ConsultaDeCostas#porNumero(String, LocalDate)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put(
                "ConsultaDeLaCorridaDeValores#items(long, long, int)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put("ConsultaDeResoluciones#deContribuyente(long)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put("ConsultarDeuda#deudaActualizadaA(CriterioDeDeuda)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put(
                "ImportarValoresACoactiva#importar(Peticion, Observacion)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put(
                "InsumosNormativosDeLaLiquidacion#conjuntoQueUsa(LineaDeLiquidacion)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put(
                "InsumosNormativosDeLaLiquidacion#de(LineaDeLiquidacion)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put(
                "MantenerCatalogoDeInfracciones#modificar(Familia, String, CodigoInfraccion, Observacion)",
                SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put(
                "MantenerCatalogoDeInfracciones#registrar(CodigoInfraccion, Observacion)",
                SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put("ReconstruirPadron#reconstruir(long)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put("ReconstruirSaldo#conciliar(long)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put("RegistrarCorridaDeEmision#ultimas(int)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put(
                "RegistrarMovimientoDeDeuda#registrar(MovimientoDeDeuda, RangoDeCuotas, String, Observacion)",
                SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put(
                "RegistrarMovimientoDeDeuda#registrar(MovimientoDeDeuda, String, Observacion)",
                SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put(
                "ReimprimirActoCoactivo#reimprimir(String, FormatoDeDocumento, Observacion)",
                SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put(
                "SubsanarNotificacion#subsanar(String, LocalDate, Observacion)", SIN_DUENO);
        SIN_LLAMADOR_CON_MOTIVO.put("ValoresReferenciales#catalogoDe(Ejercicio)", SIN_DUENO);
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
                        "el caso de uso huerfano; ni el que llama un controlador, ni el que se llama"
                                + " por su interfaz, ni el que se llama solo a si mismo cuenta como"
                                + " vivo por eso")
                .containsExactlyInAnyOrder(
                        "Huerfano#hacer()", "SeLlamaASiMismo#hacer()", "SeLlamaASiMismo#otraVez()");
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

    /** {@code Clase#metodo} de cada caso de uso sin un llamador de otra clase. */
    static Set<String> sinLlamador(JavaClasses clases) {
        Set<String> sinLlamador = new TreeSet<>();
        metodosCandidatos(clases)
                .filter(metodo -> !tieneLlamadorDeFuera(metodo))
                .forEach(metodo -> sinLlamador.add(clave(metodo)));
        return sinLlamador;
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
     * Si alguna clase distinta de la suya lo llama o lo referencia, directamente o por un metodo
     * que el suyo implementa —la interfaz o la superclase con el mismo nombre y los mismos
     * parametros—.
     */
    private static boolean tieneLlamadorDeFuera(JavaMethod metodo) {
        JavaClass suya = metodo.getOwner();
        return conLosQueImplementa(metodo)
                .anyMatch(
                        destino ->
                                Stream.concat(
                                                destino.getCallsOfSelf().stream()
                                                        .map(llamada -> llamada.getOrigin()),
                                                destino.getReferencesToSelf().stream()
                                                        .map(referencia -> referencia.getOrigin()))
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
