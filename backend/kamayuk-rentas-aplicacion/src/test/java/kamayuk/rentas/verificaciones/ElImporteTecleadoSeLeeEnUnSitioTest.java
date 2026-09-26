package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.math.BigDecimal;
import java.util.List;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.verificaciones.muestras.infraestructura.web.MuestrasDeImporteLeidoAMano;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Un importe o un area TECLEADOS se leen en un solo sitio: {@code EntradaNumerica} (#395).
 *
 * <p>Hasta #395 habia doce parsers en doce controladores y ninguno miraba la escala: la columna
 * redondeaba al guardar y el papel sellado decia otra cifra que el libro. Esta guarda impide que
 * vuelva un decimotercero: ninguna clase de {@code ..infraestructura.web..} construye un {@code
 * BigDecimal} desde texto, ni llama a {@code Dinero.de(String)} o {@code AreaM2.de(String)}.
 *
 * <p>Las alicuotas y los porcentajes no entran: su escala no es la del dinero, y los lee su propio
 * objeto de valor.
 */
@DisplayName("#395 — ningun controlador lee un importe o un area a mano")
class ElImporteTecleadoSeLeeEnUnSitioTest {

    @Test
    @DisplayName(
            "ninguna clase de infraestructura.web lee un importe o un area con su propio parser")
    void ningunControladorLeeAMano() {
        JavaClasses produccion =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .withImportOption(
                                ubicacion ->
                                        !ubicacion.contains("testFixtures")
                                                && !ubicacion.contains("test-fixtures"))
                        .importPackages("kamayuk.rentas");

        assertThat(lecturasAMano(produccion))
                .as(
                        "un importe o un area tecleados se leen con EntradaNumerica.leer, que"
                                + " rechaza el tercer decimal; un parser propio lo acepta y la columna"
                                + " lo redondea en silencio")
                .isEmpty();
    }

    @Test
    @DisplayName("la regla muerde sobre su muestra, y solo donde tiene que morder")
    void laReglaMuerdeSobreSuMuestra() {
        JavaClasses muestra =
                new ClassFileImporter().importClasses(MuestrasDeImporteLeidoAMano.class);

        assertThat(lecturasAMano(muestra))
                .as("las tres lecturas a mano, y no la que pasa por EntradaNumerica")
                .hasSize(3)
                .allSatisfy(lectura -> assertThat(lectura).doesNotContain("comoSeLee"));
    }

    /** Las llamadas prohibidas desde un paquete {@code infraestructura.web}, por su origen. */
    static List<String> lecturasAMano(JavaClasses clases) {
        return clases.stream()
                .filter(clase -> clase.getPackageName().contains(".infraestructura.web"))
                .flatMap(clase -> clase.getCodeUnits().stream())
                .flatMap(unidad -> unidad.getCallsFromSelf().stream())
                .filter(ElImporteTecleadoSeLeeEnUnSitioTest::esUnaLecturaAMano)
                .map(llamada -> llamada.getOrigin().getFullName() + " -> " + llamada.getTarget())
                .toList();
    }

    private static boolean esUnaLecturaAMano(JavaCall<?> llamada) {
        var destino = llamada.getTarget();
        List<String> parametros =
                destino.getRawParameterTypes().stream().map(tipo -> tipo.getName()).toList();
        boolean desdeTexto = parametros.equals(List.of(String.class.getName()));
        if (!desdeTexto) {
            return false;
        }
        String duenio = destino.getOwner().getName();
        return (duenio.equals(BigDecimal.class.getName()) && destino.getName().equals("<init>"))
                || ((duenio.equals(Dinero.class.getName()) || duenio.equals(AreaM2.class.getName()))
                        && destino.getName().equals("de"));
    }
}
