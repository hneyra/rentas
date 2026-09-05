package kamayuk.rentas.nucleo.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La plantilla del numero de la declaracion jurada, sin base y sin reloj (#365, D-09).
 *
 * <p>Lo que aqui se defiende es la razon por la que la plantilla existe: con la composicion fuera
 * del codigo, cerrar D-09 es cambiar una plantilla. Se prueba con <b>dos</b> plantillas distintas,
 * que es la leccion de #40 —el analisis del numero funcionaba con la de omision y leia mal
 * cualquier otra—.
 */
@DisplayName("#365 — El numero de la declaracion jurada")
class PlantillaDeNumeroDeDeclaracionTest {

    private static final Ejercicio E2026 = new Ejercicio(2026);

    @Test
    @DisplayName("la plantilla por omision compone DJ-2026-000418")
    void laPlantillaPorOmision() {
        assertThat(
                        PlantillaDeNumeroDeDeclaracion.POR_OMISION.componer(
                                TipoDeDeclaracion.HR, E2026, 418))
                .isEqualTo("DJ-2026-000418");
    }

    @Test
    @DisplayName("otra plantilla compone otro numero con el mismo correlativo")
    void otraPlantilla() {
        PlantillaDeNumeroDeDeclaracion otra =
                new PlantillaDeNumeroDeDeclaracion("{ejercicio}-{correlativo:5}-DJ");

        assertThat(otra.componer(TipoDeDeclaracion.HR, E2026, 418)).isEqualTo("2026-00418-DJ");
    }

    @Test
    @DisplayName("{tipo} es opcional, y cuando esta distingue los formularios")
    void elTipoEsOpcional() {
        PlantillaDeNumeroDeDeclaracion conTipo =
                new PlantillaDeNumeroDeDeclaracion("{tipo}-{ejercicio}-{correlativo:4}");

        assertThat(conTipo.componer(TipoDeDeclaracion.HR, E2026, 7)).isEqualTo("HR-2026-0007");
        assertThat(conTipo.componer(TipoDeDeclaracion.PU, E2026, 8)).isEqualTo("PU-2026-0008");
    }

    @Test
    @DisplayName("sin {ejercicio} no se acepta: la 1 de un año chocaria con la 1 del siguiente")
    void sinEjercicioNoSeAcepta() {
        assertThatThrownBy(() -> new PlantillaDeNumeroDeDeclaracion("DJ-{correlativo:6}"))
                .as(
                        "el correlativo se reinicia con el ejercicio y dj_numero_uq es unica en la"
                                + " municipalidad entera, no dentro del año")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{ejercicio}");
    }

    @Test
    @DisplayName("sin {correlativo} tampoco: todas las DJ del año compondrian el mismo numero")
    void sinCorrelativoTampoco() {
        assertThatThrownBy(() -> new PlantillaDeNumeroDeDeclaracion("DJ-{ejercicio}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{correlativo}");
    }

    @Test
    @DisplayName("un correlativo que no es positivo no compone nada")
    void elCorrelativoEmpiezaEnUno() {
        assertThatThrownBy(
                        () ->
                                PlantillaDeNumeroDeDeclaracion.POR_OMISION.componer(
                                        TipoDeDeclaracion.HR, E2026, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un numero que no cabe en la columna se rechaza al componerlo, no al insertarlo")
    void elNumeroCabeEnLaColumna() {
        PlantillaDeNumeroDeDeclaracion larga =
                new PlantillaDeNumeroDeDeclaracion(
                        "DECLARACION-{tipo}-{ejercicio}-{correlativo:6}");

        assertThatThrownBy(() -> larga.componer(TipoDeDeclaracion.RECTIFICATORIA, E2026, 1))
                .as("declaracion_jurada.numero es varchar(20) desde V2")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");
    }
}
