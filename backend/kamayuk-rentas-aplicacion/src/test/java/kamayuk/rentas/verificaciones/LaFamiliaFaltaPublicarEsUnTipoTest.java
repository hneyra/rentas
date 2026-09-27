package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import kamayuk.rentas.parametros.CifraSinPublicar;
import kamayuk.rentas.parametros.ParametroSinPublicar;
import kamayuk.rentas.verificaciones.muestras.MuestrasDeCifraFueraDeLaFamilia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #435 — toda cifra sin publicar es una {@link CifraSinPublicar}, y no solo algo que declara la
 * interfaz.
 *
 * <p>La interfaz dice que se puede leer —ejercicio y llave—; la base dice que se <b>traduce</b>:
 * {@code ManejadorDeLoQueFaltaPublicar} captura la base, no la interfaz, porque un {@code catch}
 * solo se escribe sobre una clase. Una excepcion que declare la interfaz a mano quedaria fuera de
 * esa traduccion y contestaria 500 con incidencia, que es el defecto que #435 cierra.
 */
@DisplayName("#435 — la familia «falta publicar» es un tipo, no una lista")
class LaFamiliaFaltaPublicarEsUnTipoTest {

    @Test
    @DisplayName("toda clase que implementa ParametroSinPublicar extiende CifraSinPublicar")
    void todaCifraExtiendeLaBase() {
        JavaClasses produccion =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("kamayuk.rentas");

        List<String> implementaciones = implementaciones(produccion);
        assertThat(implementaciones)
                .as("si no encuentra ninguna, la regla pasa sin revisar nada")
                .hasSizeGreaterThanOrEqualTo(21);
        assertThat(fueraDeLaFamilia(produccion))
                .as(
                        "extiende CifraSinPublicar en vez de declarar ParametroSinPublicar a mano: si"
                                + " no, el advice que traduce la familia no la ve y sale 500")
                .isEmpty();
    }

    @Test
    @DisplayName("la regla muerde sobre su muestra, y solo donde tiene que morder")
    void laReglaMuerdeSobreSuMuestra() {
        JavaClasses muestra =
                new ClassFileImporter()
                        .importClasses(
                                MuestrasDeCifraFueraDeLaFamilia.CifraQueDeclaraLaInterfazAMano
                                        .class,
                                MuestrasDeCifraFueraDeLaFamilia.CifraQueExtiendeLaBase.class);

        assertThat(fueraDeLaFamilia(muestra))
                .containsExactly(
                        MuestrasDeCifraFueraDeLaFamilia.CifraQueDeclaraLaInterfazAMano.class
                                .getName());
    }

    static List<String> fueraDeLaFamilia(JavaClasses clases) {
        return clases.stream()
                .filter(LaFamiliaFaltaPublicarEsUnTipoTest::implementaLaInterfaz)
                .filter(clase -> !clase.isAssignableTo(CifraSinPublicar.class))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    private static List<String> implementaciones(JavaClasses clases) {
        return clases.stream()
                .filter(LaFamiliaFaltaPublicarEsUnTipoTest::implementaLaInterfaz)
                .map(JavaClass::getName)
                .toList();
    }

    private static boolean implementaLaInterfaz(JavaClass clase) {
        return !clase.isInterface()
                && !clase.getModifiers().contains(JavaModifier.ABSTRACT)
                && clase.isAssignableTo(ParametroSinPublicar.class);
    }
}
