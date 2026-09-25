package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.verificaciones.muestras.aplicacion.MuestrasDeDosResoluciones;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #361 — Ningun metodo pide al {@link LectorDeParametros} los parametros y el identificador del
 * conjunto por separado.
 *
 * <h2>De que defecto viene</h2>
 *
 * <p>El puerto publicaba «que conjunto rige y que dice» partido en dos preguntas —{@code vigenteEn}
 * y {@code conjuntoVigenteEn}—, y once sitios las juntaban a mano. En produccion cada una es una
 * resolucion independiente, por red y con su propio repliegue, y dos resoluciones pueden contestar
 * dos conjuntos: la determinacion predial guardaba el {@code conjunto_id} de «2026 v2» con el
 * impuesto de los tramos de «2026 v1», y recalcular con lo guardado daba otra cifra. Desde #361 las
 * dos cosas se piden juntas con {@code vigenteConSuConjunto}, que devuelve un {@code
 * ConjuntoVigente} de una sola resolucion.
 *
 * <h2>Por que una regla y no un {@code @Deprecated}</h2>
 *
 * <p>El issue proponia marcar obsoleto «el uso de {@code conjuntoVigenteEn} junto a {@code
 * vigenteEn}», y eso no se puede escribir con la anotacion: las dos lecturas siguen siendo
 * legitimas <b>por separado</b> —{@code vigenteEn} para quien solo calcula, {@code
 * conjuntoVigenteEn} para quien solo lee una tabla por conjunto, como el catalogo de valores
 * referenciales o la liquidacion de fiscalizacion—, y deprecar cualquiera de las dos marcaria esos
 * usos, que estan bien, y los veintitantos dobles que las implementan. Lo que esta mal es la
 * <b>combinacion</b>, y la combinacion es lo que esta regla prohibe: el mismo metodo llamando a las
 * dos.
 *
 * <p>Es ArchUnit y no un escaner de texto porque lo que se mira es a quien se llama, y eso el
 * bytecode lo dice sin ambiguedad: el tipo del receptor es {@link LectorDeParametros} o una
 * implementacion suya, se llame como se llame la variable. Vive aqui y no en {@code
 * comun-verificaciones} porque su sujeto —el puerto— es de este sistema; por eso no la alcanza
 * {@code ReglasDeArquitecturaMuerdenTest}, y que muerde lo demuestra {@link
 * #laReglaMuerdeSobreSuMuestra()}.
 */
@DisplayName("#361 — Ningun metodo resuelve el conjunto dos veces: parametros e id, juntos")
class ElConjuntoSeResuelveUnaVezTest {

    private static final String VIGENTE_EN = "vigenteEn";
    private static final String CONJUNTO_VIGENTE_EN = "conjuntoVigenteEn";
    private static final String VIGENTE_CON_SU_CONJUNTO = "vigenteConSuConjunto";

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
    @DisplayName("ningun metodo de produccion llama a vigenteEn y a conjuntoVigenteEn")
    void ningunMetodoPideLasDosCosasPorSeparado() {
        assertThat(conElParSuelto(produccion))
                .as(
                        "los parametros y el identificador se piden juntos, con"
                                + " vigenteConSuConjunto: dos preguntas son dos resoluciones, y la"
                                + " segunda puede contestar otro conjunto (#361)")
                .isEmpty();
    }

    @Test
    @DisplayName("y hay a quien mirar: los que piden las dos cosas lo hacen con una sola llamada")
    void hayUsosDeLaLecturaConjunta() {
        long conjuntas =
                unidades(produccion)
                        .filter(
                                unidad ->
                                        llamadasAlLector(unidad).contains(VIGENTE_CON_SU_CONJUNTO))
                        .count();
        assertThat(conjuntas)
                .as(
                        "diez metodos el 2026-09-25 —los once sitios de #361 menos el predial, que"
                                + " ahora recibe el cuadro ya resuelto—: si bajan de ocho, el"
                                + " importador dejo de ver las llamadas y la regla sale verde sin"
                                + " mirar nada")
                .isGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("la regla muerde sobre su muestra, y solo donde tiene que morder")
    void laReglaMuerdeSobreSuMuestra() {
        JavaClasses muestra =
                new ClassFileImporter()
                        .importClasses(
                                Stream.concat(
                                                Stream.of(MuestrasDeDosResoluciones.class),
                                                Stream.of(
                                                        MuestrasDeDosResoluciones.class
                                                                .getDeclaredClasses()))
                                        .toArray(Class<?>[]::new));

        assertThat(conElParSuelto(muestra))
                .as(
                        "el par suelto de #361, y ni la lectura conjunta ni la que solo quiere el"
                                + " identificador")
                .singleElement()
                .asString()
                .contains("ElParSuelto.aLaFechaDe(");
    }

    // ------------------------------------------------------------------

    /** Los metodos que llaman a las dos lecturas sueltas, por su nombre completo. */
    static List<String> conElParSuelto(JavaClasses clases) {
        return unidades(clases)
                .filter(
                        unidad -> {
                            Set<String> llamadas = llamadasAlLector(unidad);
                            return llamadas.contains(VIGENTE_EN)
                                    && llamadas.contains(CONJUNTO_VIGENTE_EN);
                        })
                .map(JavaCodeUnit::getFullName)
                .sorted()
                .toList();
    }

    private static Stream<JavaCodeUnit> unidades(JavaClasses clases) {
        return clases.stream().flatMap(clase -> clase.getCodeUnits().stream());
    }

    /** Los nombres de los metodos del lector a los que llama esta unidad de codigo. */
    private static Set<String> llamadasAlLector(JavaCodeUnit unidad) {
        return unidad.getMethodCallsFromSelf().stream()
                .filter(
                        llamada ->
                                llamada.getTargetOwner().isAssignableTo(LectorDeParametros.class))
                .map(JavaMethodCall::getName)
                .collect(Collectors.toSet());
    }
}
