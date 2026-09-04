package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

@DisplayName("Codigo de referencia catastral (RF-005)")
class CodigoReferenciaCatastralTest {

    /** DDPPddSSMMMLLLEEeeppUUU con la plantilla del manual: 23 posiciones. */
    private static final String VALIDO = "20260100120030040050060";

    @Test
    @DisplayName("la plantilla del manual son 23 posiciones")
    void laPlantillaDelManualSon23Posiciones() {
        assertThat(ComposicionCatastral.DEL_MANUAL.longitud()).isEqualTo(VALIDO.length());
        assertThat(CodigoReferenciaCatastral.de(VALIDO).valor()).isEqualTo(VALIDO);
    }

    @Test
    @DisplayName("los tramos se leen por nombre")
    void losTramosSeLeenPorNombre() {
        CodigoReferenciaCatastral codigo = CodigoReferenciaCatastral.de("20260100120030040050060");

        assertThat(codigo.tramo("departamento")).isEqualTo("20");
        assertThat(codigo.tramo("provincia")).isEqualTo("26");
        assertThat(codigo.tramo("distrito")).isEqualTo("01");
        assertThat(codigo.ubigeo()).isEqualTo("202601");
        assertThat(codigo.tramo("unidad")).isEqualTo("060");
    }

    /**
     * Un caso rojo por <b>cada posicion</b>, no uno por el codigo entero.
     *
     * <p>Una validacion que solo mira la longitud deja pasar una letra en la posicion 7, y eso no
     * se nota al escribirlo: se nota cuando dos predios colisionan o cuando el padron deja de
     * cuadrar con el catastro. Aqui se exige que cada una de las 23 posiciones se revise.
     */
    @TestFactory
    @DisplayName("cada posicion se valida: una letra en cualquiera de ellas se rechaza")
    Stream<DynamicTest> cadaPosicionSeValida() {
        return IntStream.range(0, VALIDO.length())
                .mapToObj(
                        posicion -> {
                            String roto =
                                    VALIDO.substring(0, posicion)
                                            + "X"
                                            + VALIDO.substring(posicion + 1);
                            return DynamicTest.dynamicTest(
                                    "posicion " + (posicion + 1),
                                    () ->
                                            assertThatThrownBy(
                                                            () ->
                                                                    CodigoReferenciaCatastral.de(
                                                                            roto))
                                                    .isInstanceOf(IllegalArgumentException.class)
                                                    .hasMessageContaining(
                                                            "posicion " + (posicion + 1)));
                        });
    }

    @Test
    @DisplayName("una longitud distinta a la de la composicion se rechaza")
    void unaLongitudDistintaSeRechaza() {
        assertThatThrownBy(() -> CodigoReferenciaCatastral.de(VALIDO.substring(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("23 posiciones");
        assertThatThrownBy(() -> CodigoReferenciaCatastral.de(VALIDO + "0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("la composicion se recibe: cerrar D-10 sera cambiar una lista de tramos")
    void laComposicionSeRecibe() {
        // La alternativa de 21 posiciones que traen los ejemplos del prototipo. Se
        // escribe aqui como prueba de que el tipo no depende de cual gane D-10, no
        // como afirmacion de que sea la correcta.
        ComposicionCatastral deVeintiuna =
                new ComposicionCatastral(
                        List.of(
                                new ComposicionCatastral.Tramo("departamento", 2),
                                new ComposicionCatastral.Tramo("provincia", 2),
                                new ComposicionCatastral.Tramo("distrito", 2),
                                new ComposicionCatastral.Tramo("sector", 2),
                                new ComposicionCatastral.Tramo("manzana", 3),
                                new ComposicionCatastral.Tramo("lote", 3),
                                new ComposicionCatastral.Tramo("edificacion", 2),
                                new ComposicionCatastral.Tramo("piso", 2),
                                new ComposicionCatastral.Tramo("unidad", 3)));

        assertThat(deVeintiuna.longitud()).isEqualTo(21);
        assertThat(CodigoReferenciaCatastral.de("202601001200300400500", deVeintiuna).ubigeo())
                .isEqualTo("202601");
        assertThatThrownBy(() -> CodigoReferenciaCatastral.de(VALIDO, deVeintiuna))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("pedir un tramo que la composicion no tiene falla y lo dice")
    void pedirUnTramoInexistenteFalla() {
        assertThatThrownBy(() -> CodigoReferenciaCatastral.de(VALIDO).tramo("entrada2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entrada2");
    }

    @Test
    @DisplayName("una composicion sin tramos no valida nada, y se rechaza")
    void unaComposicionSinTramosSeRechaza() {
        assertThatThrownBy(() -> new ComposicionCatastral(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComposicionCatastral.Tramo("sector", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("componer arma el codigo tramo a tramo, rellenando con ceros a la izquierda")
    void componerArmaElCodigoTramoATramo() {
        CodigoReferenciaCatastral codigo =
                CodigoReferenciaCatastral.componer(
                        java.util.Map.of(
                                "departamento", "20",
                                "provincia", "01",
                                "distrito", "04",
                                "sector", "1",
                                "manzana", "2",
                                "lote", "3"),
                        ComposicionCatastral.DEL_MANUAL);

        assertThat(codigo.valor())
                .as("los tramos que no se dan valen cero, que es el predio sin edificacion")
                .isEqualTo("20010401002003000000000");
        assertThat(codigo.ubigeo()).isEqualTo("200104");
        assertThat(codigo.tramo("manzana")).isEqualTo("002");
    }

    @Test
    @DisplayName("componer sin ningun tramo da un codigo de ceros, de la longitud que toca")
    void componerSinTramosDaCeros() {
        CodigoReferenciaCatastral codigo =
                CodigoReferenciaCatastral.componer(
                        java.util.Map.of(), ComposicionCatastral.DEL_MANUAL);

        assertThat(codigo.valor())
                .isEqualTo("0".repeat(ComposicionCatastral.DEL_MANUAL.longitud()));
    }

    @Test
    @DisplayName("un tramo que no cabe en sus digitos se rechaza, no se recorta")
    void unTramoQueNoCabeSeRechaza() {
        // Recortarlo daria un codigo con la longitud correcta y el sector equivocado, que
        // es la peor de las respuestas: pasa todas las validaciones y apunta a otro predio.
        assertThatThrownBy(
                        () ->
                                CodigoReferenciaCatastral.componer(
                                        java.util.Map.of("sector", "123"),
                                        ComposicionCatastral.DEL_MANUAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sector");
    }

    @Test
    @DisplayName("un tramo que la composicion vigente no tiene se rechaza, no se ignora")
    void unTramoDesconocidoSeRechaza() {
        // El dia que D-10 se cierre en una composicion sin «entrada», un archivo que la
        // traiga tiene que fallar: ignorarla en silencio perderia el dato y compondria un
        // codigo que parece bueno.
        assertThatThrownBy(
                        () ->
                                CodigoReferenciaCatastral.componer(
                                        java.util.Map.of("callejon", "01"),
                                        ComposicionCatastral.DEL_MANUAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callejon");
    }

    @Test
    @DisplayName("un tramo con algo que no es un digito se rechaza al construir el codigo")
    void unTramoNoNumericoSeRechaza() {
        assertThatThrownBy(
                        () ->
                                CodigoReferenciaCatastral.componer(
                                        java.util.Map.of("sector", "A1"),
                                        ComposicionCatastral.DEL_MANUAL))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
