package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.CifraSinPublicar;
import kamayuk.rentas.verificaciones.muestras.MuestrasDeCifraFueraDeLaFamilia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #435 — toda cifra sin publicar es una {@link CifraSinPublicar}, y no algo que copia sus dos
 * accesores.
 *
 * <p>La base dice que la excepcion se <b>traduce</b>: {@code ManejadorDeLoQueFaltaPublicar} captura
 * la base, porque un {@code catch} solo se escribe sobre una clase. Hasta #629 la regla miraba
 * quien declaraba la interfaz {@code ParametroSinPublicar} sin extender la base; #629 fundio la
 * interfaz en la base, asi que esa forma ya no se puede escribir. La que queda es la de #723: una
 * excepcion que publica {@code ejercicio()} y {@code Optional llave()} escritos a mano —lo que era
 * el contrato de la interfaz— sin ser de la familia. Quedaria fuera de esa traduccion y contestaria
 * 500 con incidencia, que es el defecto que #435 cierra.
 */
@DisplayName("#435 — la familia «falta publicar» es un tipo, no una lista")
class LaFamiliaFaltaPublicarEsUnTipoTest {

    @Test
    @DisplayName("toda excepcion que publica ejercicio() y llave() extiende CifraSinPublicar")
    void todaCifraExtiendeLaBase() {
        JavaClasses produccion =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("kamayuk.rentas");

        assertThat(candidatas(produccion))
                .as(
                        "si no encuentra ninguna, la regla pasa sin revisar nada: las veintiuna de"
                                + " la familia publican los dos accesores, heredados de la base")
                .hasSizeGreaterThanOrEqualTo(21);
        assertThat(fueraDeLaFamilia(produccion))
                .as(
                        "extiende CifraSinPublicar en vez de escribir ejercicio() y llave() a mano:"
                                + " si no, el advice que traduce la familia no la ve y sale 500")
                .isEmpty();
    }

    @Test
    @DisplayName("la regla muerde sobre su muestra, y solo donde tiene que morder")
    void laReglaMuerdeSobreSuMuestra() {
        JavaClasses muestra =
                new ClassFileImporter()
                        .importClasses(
                                MuestrasDeCifraFueraDeLaFamilia.CifraQueCopiaLosAccesores.class,
                                MuestrasDeCifraFueraDeLaFamilia.CifraQueExtiendeLaBase.class);

        assertThat(fueraDeLaFamilia(muestra))
                .containsExactly(
                        MuestrasDeCifraFueraDeLaFamilia.CifraQueCopiaLosAccesores.class.getName());
    }

    static List<String> fueraDeLaFamilia(JavaClasses clases) {
        return clases.stream()
                .filter(LaFamiliaFaltaPublicarEsUnTipoTest::esCandidata)
                .filter(clase -> !clase.isAssignableTo(CifraSinPublicar.class))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    /** Las excepciones concretas que publican ejercicio y llave: de la familia o no. */
    private static List<String> candidatas(JavaClasses clases) {
        return clases.stream()
                .filter(LaFamiliaFaltaPublicarEsUnTipoTest::esCandidata)
                .map(JavaClass::getName)
                .toList();
    }

    private static boolean esCandidata(JavaClass clase) {
        return concreta(clase)
                && clase.isAssignableTo(Throwable.class)
                && publicaLoQuePublicaLaFamilia(clase);
    }

    private static boolean concreta(JavaClass clase) {
        return !clase.isInterface() && !clase.getModifiers().contains(JavaModifier.ABSTRACT);
    }

    /** {@code Ejercicio ejercicio()} y {@code Optional llave()}: el contrato de la familia. */
    private static boolean publicaLoQuePublicaLaFamilia(JavaClass clase) {
        return tiene(clase, "ejercicio", Ejercicio.class) && tiene(clase, "llave", Optional.class);
    }

    private static boolean tiene(JavaClass clase, String nombre, Class<?> devuelve) {
        return clase.getAllMethods().stream()
                .filter(metodo -> metodo.getName().equals(nombre))
                .filter(metodo -> metodo.getRawParameterTypes().isEmpty())
                .map(JavaMethod::getRawReturnType)
                .anyMatch(tipo -> tipo.isEquivalentTo(devuelve));
    }
}
