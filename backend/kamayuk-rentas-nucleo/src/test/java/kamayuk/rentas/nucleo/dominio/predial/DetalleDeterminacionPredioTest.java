package kamayuk.rentas.nucleo.dominio.predial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #362 — «que declaro el contribuyente» tiene una sola respuesta, y depende del origen.
 *
 * <p>La siembra que distingue es la del issue: declarado 100 000 y sellado 180 000. Con las dos
 * cifras iguales, leer {@code autovaluo()} en vez de la regla daria el mismo numero y ninguna de
 * estas pruebas veria nada.
 */
@DisplayName("#362 — El declarado de un detalle, segun su origen")
class DetalleDeterminacionPredioTest {

    private static final Dinero DECLARADO = Dinero.de("100000.00");
    private static final Dinero SELLADO = Dinero.de("180000.00");
    private static final String HUELLA = "a".repeat(64);

    @Test
    @DisplayName("en DECLARADO, lo declarado es el propio autovaluo")
    void enDeclaradoEsElAutovaluo() {
        DetalleDeterminacionPredio detalle =
                DetalleDeterminacionPredio.nuevo(11L, DECLARADO, Porcentaje.total(), DECLARADO);

        assertThat(detalle.autovaluoDeclarado())
                .as("no se guarda dos veces: la columna queda nula")
                .isNull();
        assertThat(detalle.autovaluoDeclaradoSegunElOrigen()).contains(DECLARADO);
    }

    @Test
    @DisplayName("en SELLADO, lo declarado es lo que se guardo al lado, no el autovaluo")
    void enSelladoEsLoGuardadoAlLado() {
        DetalleDeterminacionPredio detalle = sellado(DECLARADO);

        assertThat(detalle.autovaluo()).isEqualTo(SELLADO);
        assertThat(detalle.autovaluoDeclaradoSegunElOrigen())
                .as("hasta #362 el recalculo leia `autovaluo()`, que aqui es el sellado")
                .contains(DECLARADO);
    }

    @Test
    @DisplayName("en SELLADO sin declarada, no hay declarado: vacio, nunca el sellado")
    void enSelladoSinDeclaradaNoHayDeclarado() {
        assertThat(sellado(null).autovaluoDeclaradoSegunElOrigen()).isEmpty();
    }

    @Test
    @DisplayName("un DECLARADO con otra declarada al lado no se construye (V32)")
    void unDeclaradoNoLlevaOtraDeclarada() {
        assertThatThrownBy(
                        () ->
                                new DetalleDeterminacionPredio(
                                        null,
                                        11L,
                                        DECLARADO,
                                        Dinero.CERO,
                                        Porcentaje.total(),
                                        DECLARADO,
                                        OrigenDelAutovaluo.DECLARADO,
                                        null,
                                        null,
                                        SELLADO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("solo se guarda al lado cuando manda la sellada");
    }

    private static DetalleDeterminacionPredio sellado(@Nullable Dinero declarado) {
        return DetalleDeterminacionPredio.sellado(
                11L, SELLADO, Dinero.CERO, Porcentaje.total(), SELLADO, 42L, HUELLA, declarado);
    }
}
