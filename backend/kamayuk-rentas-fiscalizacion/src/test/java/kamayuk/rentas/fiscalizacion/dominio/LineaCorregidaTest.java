package kamayuk.rentas.fiscalizacion.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LineaDeLiquidacion#corregidaCon} sin caso de uso (#340): lo que el recorrido por {@code
 * ReliquidarFiscalizacion} no alcanza a mirar.
 *
 * <p>Los cuatro escenarios del issue se prueban de punta a punta en {@code
 * LiquidarYReliquidarTest.LaCorreccionParteDeLaBase}; aqui van los bordes del metodo.
 */
@DisplayName("#340 — la linea se corrige a si misma")
class LineaCorregidaTest {

    private static final Ejercicio E2024 = new Ejercicio(2024);
    private static final long CONJUNTO = 41L;
    private static final long PREDIO = 20L;

    @Test
    @DisplayName("una correccion de otro ejercicio se rechaza nombrando los dos")
    void unaCorreccionDeOtroEjercicioSeRechaza() {
        LineaDeLiquidacion linea =
                LineaDeLiquidacion.predialSinCifras(
                        E2024,
                        CONJUNTO,
                        PREDIO,
                        CondicionFiscalizada.CONFORME,
                        AreaM2.de("120.00"),
                        AreaM2.de("120.00"),
                        null,
                        null);

        assertThatThrownBy(
                        () ->
                                linea.corregidaCon(
                                        new CorreccionDeLinea(
                                                new Ejercicio(2025),
                                                null,
                                                AreaM2.de("150.00"),
                                                null,
                                                null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2025")
                .hasMessageContaining("2024");
    }

    @Test
    @DisplayName("un NO_UBICADO admite corregir lo declarado, y sigue NO_UBICADO")
    void unNoUbicadoAdmiteCorregirLoDeclarado() {
        // Lo que se rechaza es corregir lo HALLADO: lo declarado es de la DJ, no de la visita.
        LineaDeLiquidacion linea =
                LineaDeLiquidacion.predialSinCifras(
                        E2024,
                        CONJUNTO,
                        PREDIO,
                        CondicionFiscalizada.NO_UBICADO,
                        null,
                        null,
                        null,
                        null);

        LineaDeLiquidacion corregida =
                linea.corregidaCon(
                        new CorreccionDeLinea(E2024, AreaM2.de("90.00"), null, null, null));

        assertThat(corregida.areaDeclarada()).isEqualTo(AreaM2.de("90.00"));
        assertThat(corregida.condicion()).isEqualTo(CondicionFiscalizada.NO_UBICADO);
    }

    @Test
    @DisplayName("un uso en blanco no es un campo: la linea vehicular se conserva")
    void unUsoEnBlancoNoEsUnCampo() {
        LineaDeLiquidacion linea =
                LineaDeLiquidacion.vehicularSinCifras(
                        E2024, CONJUNTO, 55L, CondicionFiscalizada.SUBVALUADOR);

        assertThat(linea.corregidaCon(new CorreccionDeLinea(E2024, null, null, "  ", "")))
                .as("la forma de un formulario con la fila vacia")
                .isSameAs(linea);
    }
}
