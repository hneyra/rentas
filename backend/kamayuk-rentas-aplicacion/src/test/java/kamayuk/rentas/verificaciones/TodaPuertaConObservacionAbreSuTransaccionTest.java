package kamayuk.rentas.verificaciones;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.verificaciones.muestras.aplicacion.MuestrasDePuertasSinTransaccion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * #406 — Todo metodo publico de un caso de uso que recibe una {@link Observacion} abre su
 * transaccion.
 *
 * <h2>De que defecto viene</h2>
 *
 * <p>{@code FraccionarEnCoactiva} tenia dos sobrecargas de {@code fraccionar}: la de dos argumentos
 * llevaba {@code @Transactional} y llamaba a la de tres, que no la llevaba — y el controlador
 * llamaba a la de tres. El proxy busca el atributo transaccional en el metodo que se <b>invoca</b>,
 * asi que por el borde la lectura del expediente corria en autocommit, sin {@code SET LOCAL}, y RLS
 * la rechazaba: {@code POST /coactiva/convenios} contestaba 500 en cada registro. Las pruebas
 * entraban por la otra puerta y no lo vieron.
 *
 * <h2>Es la inversa de la regla 10, y por eso ninguna de las dos la cubria</h2>
 *
 * <p>{@code TODO_CASO_DE_USO_DE_ESCRITURA_EXIGE_OBSERVACION} exige la {@code Observacion} en el
 * metodo <b>transaccional</b>; no exige la transaccion en el metodo que <b>recibe</b> la {@code
 * Observacion}. Recibirla es la declaracion mas fiable de «esto escribe» que tiene una firma, y una
 * escritura que entra sin transaccion no tiene {@code SET LOCAL}: en este producto eso no es un
 * detalle de consistencia, es que la consulta no corre.
 *
 * <h2>Medido antes de escribirla (2026-09-25)</h2>
 *
 * <p>Sobre las clases de produccion, ya con #406 arreglado: <b>118 metodos</b> publicos que reciben
 * una {@code Observacion} en <b>82</b> {@code @Service} de {@code ..aplicacion..}, y <b>9</b> sin
 * transaccion —10 antes de #406—. Los nueve son deliberados y cada uno lo dice en su propio
 * javadoc: van en {@link #SIN_TRANSACCION_PROPIA} con su motivo. <b>#450 anadio tres</b>: las dos
 * emisiones de licencia y la revalidacion, que preguntan a los vecinos sin transaccion y delegan la
 * escritura en su {@code Registrar...}, que es donde se abre. Ninguna clase fuera de
 * {@code @Service} tenia un metodo asi.
 *
 * <p>Vive aqui y no en {@code comun-verificaciones} porque las exenciones son metodos de este
 * sistema. Por eso no la alcanza {@code ReglasDeArquitecturaMuerdenTest}, y que muerde lo demuestra
 * {@link #laReglaMuerdeSobreSuMuestra()}.
 */
@DisplayName("#406 — Todo metodo publico que recibe una Observacion abre su transaccion")
class TodaPuertaConObservacionAbreSuTransaccionTest {

    private static final String RAIZ = "kamayuk.rentas.";
    private static final String OBSERVACION = "kamayuk.rentas.dominio.Observacion";
    private static final String EN_EL_SUYO = "(java.io.Reader, kamayuk.rentas.dominio.Observacion)";

    /**
     * Los metodos que reciben la observacion y <b>no</b> abren transaccion porque la abre, a
     * proposito, otro bean al que delegan.
     *
     * <p>Se nombra el metodo entero, no la clase: cualquier otro metodo que se agregue a la misma
     * clase vuelve a estar sujeto a la regla. Y {@link #cadaExencionEximeAAlguien()} comprueba que
     * cada entrada siga existiendo y siga sin transaccion: una exencion que no exime a nadie es una
     * puerta abierta para el siguiente que se llame igual.
     */
    static final Map<String, String> SIN_TRANSACCION_PROPIA =
            Map.ofEntries(
                    Map.entry(
                            RAIZ
                                    + "contribuyentes.aplicacion.ImportarContribuyentes.importar"
                                    + EN_EL_SUYO,
                            "cada fila abre la suya al llamar a RegistrarContribuyente: envolver el bucle"
                                    + " haria que una fila rechazada deshiciera las buenas"),
                    Map.entry(
                            RAIZ + "nucleo.aplicacion.ImportarVehiculos.importar" + EN_EL_SUYO,
                            "cada fila abre la suya al llamar a RegistrarVehiculo, el mismo reparto"),
                    Map.entry(
                            RAIZ
                                    + "nucleo.aplicacion.ImportarDeudaDeDemostracion.importar"
                                    + EN_EL_SUYO,
                            "el mismo reparto por fila que ImportarVias"),
                    Map.entry(
                            RAIZ + "nucleo.aplicacion.ImportarTransferencias.importar" + EN_EL_SUYO,
                            "el mismo reparto por fila que ImportarVias"),
                    Map.entry(
                            RAIZ
                                    + "nucleo.aplicacion.DeterminarPredialMasivo.ejecutar(kamayuk.rentas"
                                    + ".nucleo.aplicacion.DeterminarPredialMasivo$Peticion, "
                                    + OBSERVACION
                                    + ")",
                            "una transaccion por determinacion (#328, #247 §2): la fila rechazada no"
                                    + " puede marcar como rollback-only a las demas"),
                    Map.entry(
                            RAIZ
                                    + "nucleo.aplicacion.DeterminarPredial.determinar(kamayuk.rentas"
                                    + ".nucleo.aplicacion.DeterminarPredial$Peticion, "
                                    + OBSERVACION
                                    + ")",
                            "la abre RegistrarDeterminacionPredial.asentar, que es quien escribe (#54, #72, #359): los"
                                    + " colaboradores ajenos traen la suya"),
                    Map.entry(
                            RAIZ
                                    + "cuentacorriente.aplicacion.GeneradorDeCargosCuentaCorriente"
                                    + ".generarCargo(kamayuk.rentas.dominio.Ejercicio, long,"
                                    + " java.lang.String, java.lang.Integer, java.lang.Long,"
                                    + " java.lang.Long, java.lang.String, kamayuk.rentas.dominio.Dinero,"
                                    + " java.time.LocalDate, java.lang.String, "
                                    + OBSERVACION
                                    + ")",
                            "adaptador de puerto: arma el asiento sin leer nada y delega en"
                                    + " RegistrarAsiento.asentar, que es @Transactional"),
                    Map.entry(
                            RAIZ
                                    + "cuentacorriente.aplicacion.GeneradorDeCargosCuentaCorriente"
                                    + ".generarGastoDelProcedimiento(kamayuk.rentas.dominio.Ejercicio,"
                                    + " long, java.lang.String, java.lang.String,"
                                    + " kamayuk.rentas.dominio.Dinero, java.time.LocalDate,"
                                    + " java.lang.String, "
                                    + OBSERVACION
                                    + ")",
                            "el mismo adaptador, y la misma delegacion en RegistrarAsiento.asentar"),
                    Map.entry(
                            RAIZ
                                    + "tesoreria.aplicacion.FraccionamientoCoactivoTesoreria.registrar("
                                    + "kamayuk.rentas.tesoreria.SolicitudDeConvenioCoactivo,"
                                    + " java.lang.String, "
                                    + OBSERVACION
                                    + ")",
                            "adaptador de puerto: delega en RegistrarPreconvenio.registrar, que es"
                                    + " @Transactional; y quien lo llama, FraccionarEnCoactiva, ya abrio"
                                    + " la suya (#406)"),
                    Map.entry(
                            RAIZ
                                    + "licencias.aplicacion.EmitirLicenciaDeFuncionamiento.emitir(kamayuk.rentas"
                                    + ".licencias.aplicacion.EmitirLicenciaDeFuncionamiento$Solicitud,"
                                    + " kamayuk.rentas.documentos.FormatoDeDocumento, "
                                    + OBSERVACION
                                    + ")",
                            "la abre RegistrarLicenciaDeFuncionamiento.registrar, que es quien escribe (#450):"
                                    + " aqui se pregunta a caja y a catastro, y con una transaccion abierta cada"
                                    + " pregunta retendria una conexion del pool mientras el vecino contesta"),
                    Map.entry(
                            RAIZ
                                    + "licencias.aplicacion.EmitirLicenciaDeEdificacion.emitir(java.lang.String,"
                                    + " java.time.LocalDate, java.time.LocalDate, java.lang.String,"
                                    + " kamayuk.rentas.documentos.FormatoDeDocumento, "
                                    + OBSERVACION
                                    + ")",
                            "la abre RegistrarLicenciaDeEdificacion.registrar (#450), por lo mismo: el cuadro"
                                    + " de catastro y el recibo de caja se piden fuera de la transaccion"),
                    Map.entry(
                            RAIZ
                                    + "licencias.aplicacion.RevalidarLicenciaDeEdificacion.revalidar(java.lang.String,"
                                    + " java.time.LocalDate, java.time.LocalDate, java.lang.String,"
                                    + " kamayuk.rentas.documentos.FormatoDeDocumento, "
                                    + OBSERVACION
                                    + ")",
                            "la abre RegistrarRevalidacionDeEdificacion.registrar (#450), por lo mismo"));

    static final ArchRule TODA_PUERTA_CON_OBSERVACION_ABRE_SU_TRANSACCION =
            classes()
                    .that()
                    .resideInAPackage("..aplicacion..")
                    .and()
                    .areAnnotatedWith(Service.class)
                    .should(new AbreSuTransaccion())
                    .because(
                            "sin transaccion no hay SET LOCAL, y sin SET LOCAL RLS rechaza la"
                                    + " consulta: el proxy mira el metodo que se INVOCA, no el que"
                                    + " este llama despues (#406)");

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
    @DisplayName("ningun caso de uso recibe una Observacion sin abrir su transaccion")
    void todaPuertaAbreSuTransaccion() {
        TODA_PUERTA_CON_OBSERVACION_ABRE_SU_TRANSACCION.check(produccion);
    }

    @Test
    @DisplayName("y hay puertas que mirar: sin sujeto la regla sale verde sin haber mirado nada")
    void hayPuertasQueMirar() {
        long puertas =
                produccion.stream()
                        .filter(TodaPuertaConObservacionAbreSuTransaccionTest::esCasoDeUso)
                        .flatMap(clase -> puertasDe(clase))
                        .count();
        assertThat(puertas)
                .as(
                        "118 metodos el 2026-09-25: si baja de cien, el importador dejo de ver los"
                                + " casos de uso")
                .isGreaterThan(100);
    }

    @Test
    @DisplayName("cada exencion sigue existiendo y sigue sin transaccion propia")
    void cadaExencionEximeAAlguien() {
        for (String metodo : SIN_TRANSACCION_PROPIA.keySet()) {
            Optional<JavaMethod> encontrado =
                    produccion.stream()
                            .filter(TodaPuertaConObservacionAbreSuTransaccionTest::esCasoDeUso)
                            .flatMap(clase -> puertasDe(clase))
                            .filter(puerta -> puerta.getFullName().equals(metodo))
                            .findFirst();
            assertThat(encontrado)
                    .as("la exencion %s ya no nombra ninguna puerta: retirala", metodo)
                    .isPresent();
            assertThat(abreTransaccion(encontrado.orElseThrow()))
                    .as("%s ya abre su transaccion: la exencion sobra, retirala", metodo)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("la regla muerde sobre su muestra, y solo donde tiene que morder")
    void laReglaMuerdeSobreSuMuestra() {
        JavaClasses muestra =
                new ClassFileImporter()
                        .importClasses(
                                Stream.concat(
                                                Stream.of(MuestrasDePuertasSinTransaccion.class),
                                                Stream.of(
                                                        MuestrasDePuertasSinTransaccion.class
                                                                .getDeclaredClasses()))
                                        .toArray(Class<?>[]::new));

        List<String> detalles =
                TODA_PUERTA_CON_OBSERVACION_ABRE_SU_TRANSACCION
                        .evaluate(muestra)
                        .getFailureReport()
                        .getDetails();
        assertThat(detalles)
                .as(
                        "la sobrecarga de tres argumentos de #406 y la puerta unica sin"
                                + " transaccion, y ninguna de las cuatro buenas")
                .hasSize(2)
                .anyMatch(
                        detalle ->
                                detalle.contains(
                                        "DosPuertasYLaAnotacionEnLaQueNoUsaElBorde.registrar("
                                                + "java.lang.String, java.lang.String, "
                                                + OBSERVACION
                                                + ")"))
                .anyMatch(detalle -> detalle.contains("UnaPuertaSinTransaccion.anular("));
    }

    // ------------------------------------------------------------------

    private static boolean esCasoDeUso(JavaClass clase) {
        return clase.getPackageName().contains(".aplicacion")
                && clase.isAnnotatedWith(Service.class);
    }

    /** Los metodos publicos de instancia que reciben una {@link Observacion}. */
    private static Stream<JavaMethod> puertasDe(JavaClass clase) {
        return clase.getMethods().stream()
                .filter(metodo -> metodo.getModifiers().contains(JavaModifier.PUBLIC))
                .filter(
                        metodo ->
                                !metodo.getModifiers().contains(JavaModifier.STATIC)
                                        && !metodo.getModifiers().contains(JavaModifier.SYNTHETIC)
                                        && !metodo.getModifiers().contains(JavaModifier.BRIDGE))
                .filter(
                        metodo ->
                                metodo.getRawParameterTypes().stream()
                                        .anyMatch(tipo -> tipo.getName().equals(OBSERVACION)));
    }

    /** En el metodo o en la clase: Spring aplica la de la clase a todos sus metodos publicos. */
    private static boolean abreTransaccion(JavaMethod metodo) {
        return metodo.isAnnotatedWith(Transactional.class)
                || metodo.getOwner().isAnnotatedWith(Transactional.class);
    }

    private static final class AbreSuTransaccion extends ArchCondition<JavaClass> {

        AbreSuTransaccion() {
            super("abrir su transaccion en todo metodo publico que reciba una Observacion");
        }

        @Override
        public void check(JavaClass clase, ConditionEvents eventos) {
            puertasDe(clase)
                    .filter(metodo -> !abreTransaccion(metodo))
                    .filter(metodo -> !SIN_TRANSACCION_PROPIA.containsKey(metodo.getFullName()))
                    .forEach(
                            metodo ->
                                    eventos.add(
                                            SimpleConditionEvent.violated(
                                                    metodo,
                                                    "el metodo "
                                                            + metodo.getFullName()
                                                            + " recibe una Observacion y no abre"
                                                            + " transaccion: si lo llama el borde,"
                                                            + " corre sin SET LOCAL (#406)")));
        }
    }
}
