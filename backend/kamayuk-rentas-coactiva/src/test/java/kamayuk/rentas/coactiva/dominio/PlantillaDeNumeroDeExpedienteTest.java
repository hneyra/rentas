package kamayuk.rentas.coactiva.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * D-09 — La numeracion del expediente coactivo es un parametro, no un formato compilado.
 *
 * <p><b>Lo que estas pruebas defienden.</b> D-09 sigue abierta: «numeracion de valores y
 * expedientes: correlativo por municipalidad y ejercicio, con que formato y que reinicio». Mientras
 * lo este, cerrar la decision tiene que costar <b>una plantilla</b> y no una migracion de columna
 * mas una reescritura del analizador. Por eso se prueba con dos plantillas distintas, no solo con
 * la de omision: si el formato se hubiera colado dentro del codigo, la segunda plantilla fallaria.
 *
 * <p>Mismo precedente que {@code ComposicionCatastral} para D-10 (RegistrarPredioTest): «cerrar
 * D-10 sera fijar el parametro, no reescribir la validacion».
 *
 * <p>Sin base y sin reloj (regla 6).
 */
@DisplayName("D-09 — La plantilla del numero de expediente")
class PlantillaDeNumeroDeExpedienteTest {

    private static final Ejercicio E2026 = new Ejercicio(2026);

    @Nested
    @DisplayName("La plantilla por omision, mientras D-09 no cierre")
    class PorOmision {

        @Test
        @DisplayName("compone EXP-2026-000001")
        void compone() {
            assertThat(PlantillaDeNumeroDeExpediente.POR_OMISION.componer(E2026, 1))
                    .isEqualTo("EXP-2026-000001");
        }

        @Test
        @DisplayName("y analiza de vuelta lo que compuso, sin un segundo analizador")
        void analizaLoQueCompuso() {
            String impreso = PlantillaDeNumeroDeExpediente.POR_OMISION.componer(E2026, 123);

            NumeroDeExpediente leido = PlantillaDeNumeroDeExpediente.POR_OMISION.analizar(impreso);

            assertThat(leido.ejercicio()).isEqualTo(E2026);
            assertThat(leido.correlativo()).isEqualTo(123);
            assertThat(leido.impreso(PlantillaDeNumeroDeExpediente.POR_OMISION)).isEqualTo(impreso);
        }

        @Test
        @DisplayName("acepta el numero en minusculas y con espacios: llega tecleado")
        void aceptaLoTecleado() {
            assertThat(PlantillaDeNumeroDeExpediente.POR_OMISION.analizar("  exp-2026-000007  "))
                    .isEqualTo(new NumeroDeExpediente(E2026, 7));
        }

        @Test
        @DisplayName("rechaza lo que no tiene su forma, diciendo cual es")
        void rechazaLoQueNoTieneSuForma() {
            assertThatThrownBy(() -> PlantillaDeNumeroDeExpediente.POR_OMISION.analizar("7"))
                    .isInstanceOf(PlantillaDeNumeroDeExpediente.NumeroIlegible.class)
                    .hasMessageContaining("EXP-2026-000001");
        }
    }

    @Nested
    @DisplayName("Cerrar D-09 es cambiar la plantilla, no el codigo")
    class OtraPlantilla {

        /**
         * Lo que podria salir de la municipalidad piloto: otro prefijo, otro orden, otros ceros.
         */
        private static final PlantillaDeNumeroDeExpediente OTRA =
                new PlantillaDeNumeroDeExpediente("{correlativo:4}-{ejercicio}-EC");

        @Test
        @DisplayName("compone y analiza con un formato completamente distinto")
        void componeYAnalizaConOtroFormato() {
            String impreso = OTRA.componer(E2026, 42);

            assertThat(impreso).isEqualTo("0042-2026-EC");
            assertThat(OTRA.analizar(impreso)).isEqualTo(new NumeroDeExpediente(E2026, 42));
        }

        @Test
        @DisplayName("sin relleno de ceros tambien: {correlativo} sin :N")
        void sinRelleno() {
            PlantillaDeNumeroDeExpediente desnuda =
                    new PlantillaDeNumeroDeExpediente("{ejercicio}/{correlativo}");

            assertThat(desnuda.componer(E2026, 9)).isEqualTo("2026/9");
            assertThat(desnuda.analizar("2026/9").correlativo()).isEqualTo(9);
        }

        @Test
        @DisplayName("una plantilla no analiza lo que compuso otra: el numero se lee con la suya")
        void cadaPlantillaLeeLoSuyo() {
            String conLaDeOmision = PlantillaDeNumeroDeExpediente.POR_OMISION.componer(E2026, 42);

            assertThatThrownBy(() -> OTRA.analizar(conLaDeOmision))
                    .as(
                            "es lo que obliga a que el numero guardado y el correlativo desnudo"
                                    + " convivan en la tabla (V33): el entero no depende de como"
                                    + " se imprimia entonces")
                    .isInstanceOf(PlantillaDeNumeroDeExpediente.NumeroIlegible.class);
        }
    }

    @Nested
    @DisplayName("Una plantilla que no numera no es una plantilla")
    class PlantillasInvalidas {

        @Test
        @DisplayName("sin {ejercicio}, dos expedientes de anios distintos compartirian numero")
        void sinEjercicio() {
            assertThatThrownBy(() -> new PlantillaDeNumeroDeExpediente("EXP-{correlativo:6}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("{ejercicio}");
        }

        @Test
        @DisplayName("sin {correlativo}, todos los expedientes del anio serian el mismo")
        void sinCorrelativo() {
            assertThatThrownBy(() -> new PlantillaDeNumeroDeExpediente("EXP-{ejercicio}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("{correlativo}");
        }

        @Test
        @DisplayName(
                "un numero que no cabe en la columna se rechaza al componerlo, no al guardarlo")
        void numeroQueNoCabe() {
            PlantillaDeNumeroDeExpediente larga =
                    new PlantillaDeNumeroDeExpediente(
                            "EXPEDIENTE-COACTIVO-{ejercicio}-{correlativo:8}");

            assertThatThrownBy(() -> larga.componer(E2026, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("20");
        }

        @Test
        @DisplayName("el correlativo empieza en 1: no hay expediente cero")
        void correlativoDesdeUno() {
            assertThatThrownBy(() -> PlantillaDeNumeroDeExpediente.POR_OMISION.componer(E2026, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
