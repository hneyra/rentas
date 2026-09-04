package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import kamayuk.comun.verificaciones.ProhibicionesEnElCodigoFuenteTestBase;
import kamayuk.comun.verificaciones.RevisorDeCodigoFuente;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las prohibiciones de texto de ARQ-04 §2, sobre el codigo de {@code sgtm}.
 *
 * <p>Hereda de {@code comun-verificaciones} el escaner y las pruebas que lo demuestran, y añade las
 * dos que son <b>de este repositorio</b>: el censo de las clases que componen el area a mano —esa
 * lista es suya, no de la libreria— y la celda del historial, que se afirma leyendo una clase de
 * produccion de {@code kamayuk-rentas-fiscalizacion}.
 */
@DisplayName("ARQ-04 §2 — Prohibiciones en el codigo fuente")
class ProhibicionesEnElCodigoFuenteTest extends ProhibicionesEnElCodigoFuenteTestBase {

    @Test
    @DisplayName("las seis clases de sgtm que componen el area a mano, una a una")
    void elCensoDeLasClasesQueComponenElArea() {
        // La misma linea, byte a byte, en dos archivos: en uno es un hallazgo y en el otro no.
        // Lo que decide es el NOMBRE DE LA CLASE, y por eso la lista se escribe por clase y no
        // por paquete: anadir una sexta es una linea visible en el diff.
        String fuente =
                """
                final class Modelo {
                    static Tabla de(Fue fue) {
                        return Campo.de("Area del terreno (m2)",
                                fue.areaTerreno().valor().toPlainString());
                    }
                }
                """;

        assertThat(RevisorDeCodigoFuente.revisarAreas("UnRecursoCualquiera.java", fuente))
                .as("fuera de la lista, la misma linea es un hallazgo")
                .hasSize(1);
        assertThat(RevisorDeCodigoFuente.revisarAreas("ModeloDelFue.java", fuente))
                .as("el papel no tiene serializador y la unidad va en el rotulo de la fila")
                .isEmpty();
        assertThat(new ConfiguracionDelSgtm().componenElAreaAManoConMotivo())
                .as(
                        "las seis de hoy: cuatro modelos de documento y las DOS descripciones de"
                                + " auditoria. La columna JSON de la bitacora SI sale por HTTP"
                                + " —`GET /seguridad/auditoria` la publica verbatim—, asi que el"
                                + " motivo de esas dos no es «no llega al cliente» sino que ahi el"
                                + " area no es un campo tipado sino texto libre, y se escribe sin"
                                + " la unidad para que diga lo mismo que el resto")
                .containsExactlyInAnyOrder(
                        "ModeloDelFue",
                        "ModeloDeLaLicencia",
                        "ModeloDeLaResolucionDeDeterminacion",
                        "ModeloDeLaFichaDelContribuyente",
                        "RegistrarAnuncio",
                        "ActualizarFichaCatastral");
    }

    @Test
    @DisplayName("la celda del historial NO esta en la lista porque el escaner no la alcanza")
    void laCeldaDelHistorialNoLaAlcanzaElEscaner() throws IOException {
        // La otra excepcion legitima de #607 —«120.00 m2» en `cambios[].antes`, donde la misma
        // columna lleva un periodo, una condicion o un importe segun la fila, asi que sin la
        // unidad «120.00 → 164.50» no dice que cambio—. NO esta en la lista de excepciones, y
        // esta prueba es el motivo: convierte con un `texto(Object)` propio, de modo que en su
        // codigo no aparece ningun `area…().toString()` que casar. Una entrada muerta en una
        // lista de excepciones es exactamente el defecto que esa lista existe para no tener.
        //
        // Lo que sostiene esa celda son las tres pruebas que afirman «300.00 m2» letra por
        // letra: LiquidacionControllerTest, LiquidacionYSusVersionesTest y LiquidarYReliquidarTest.
        Path celda =
                raizDelBackend()
                        .resolve(
                                "kamayuk-rentas-fiscalizacion/src/main/java/kamayuk/rentas/fiscalizacion")
                        .resolve("dominio/DiferenciaEntreLiquidaciones.java");

        assertThat(celda).as("la clase tiene que existir para poder afirmar esto de ella").exists();

        String fuente = Files.readString(celda, StandardCharsets.UTF_8);

        assertThat(fuente)
                .as("convierte con un ayudante propio, no con `area…().toString()`")
                .contains("texto(vieja.areaDeclarada())");
        assertThat(RevisorDeCodigoFuente.revisarAreas(celda.getFileName().toString(), fuente))
                .isEmpty();
        assertThat(new ConfiguracionDelSgtm().componenElAreaAManoConMotivo())
                .doesNotContain("DiferenciaEntreLiquidaciones");
    }
}
