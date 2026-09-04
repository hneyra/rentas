package kamayuk.rentas.parametros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #604 — Lo que falta publicar se dice tambien <b>por programa</b>, no solo en la prosa.
 *
 * <h2>Que estaba mal</h2>
 *
 * <p>El issue afirma que «el dato existe ya en el dominio», y medido resulto valer para <b>una</b>
 * de las seis excepciones: {@code CondicionSinParametrizar} guardaba su llave. Las cinco de este
 * modulo recibian el ejercicio o el punto en el constructor, los metian en el texto del mensaje y
 * <b>no los publicaban</b>. Con eso, lo unico que un cliente podia hacer para separar «falta
 * publicar una cifra» de «falta un campo del formulario» era leer el texto, que es exactamente lo
 * que el contrato existe para no tener que hacer.
 *
 * <h2>Que mide cada caso</h2>
 *
 * <p>La llave, y cuando <b>no</b> hay llave. Que las tres del punto mal parametrizado nombren su
 * fila, que la del bloque entero nombre solo el tipo, y que la del conjunto sin sellar no nombre
 * ninguna: nombrar una cualquiera para rellenar el hueco seria una afirmacion verosimil y
 * equivocada, que es el modo de fallo que este repositorio lleva midiendo desde #51.
 */
@DisplayName("#604 — Las excepciones de lo que falta publicar lo dicen legible por programa")
class ParametroSinPublicarTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    @Test
    @DisplayName("sin conjunto sellado: publica su ejercicio y NINGUNA llave")
    void sinConjuntoSelladoNoHayLlaveQueNombrar() {
        ParametroSinPublicar falta = new LectorDeParametros.EjercicioSinSellar(EJERCICIO);

        assertThat(falta.ejercicio()).isEqualTo(EJERCICIO);
        assertThat(falta.llave())
                .as(
                        "lo que falta no es una fila sino el conjunto donde publicarla; nombrar"
                                + " una llave diria que basta con publicarla, y no basta")
                .isEmpty();
    }

    @Test
    @DisplayName("sin ningun punto de redondeo: la llave es el TIPO solo, sin clave")
    void sinPuntosObservadosLaLlaveEsElTipo() {
        assertThatThrownBy(() -> PoliticasDeRedondeoSelladas.de(conjunto().construir()))
                .isInstanceOfSatisfying(
                        PoliticasDeRedondeoSelladas.SinPuntosObservados.class,
                        falta -> {
                            assertThat(falta.ejercicio()).isEqualTo(EJERCICIO);
                            assertThat(falta.llave())
                                    .as(
                                            "quien lee las politicas no sabe cual de los trece"
                                                    + " puntos queria el que llamo: nombrar"
                                                    + " REDONDEO:CUOTA seria verosimil y equivocado")
                                    .contains(PoliticasDeRedondeoSelladas.TIPO);
                        });
    }

    @Test
    @DisplayName("media politica —escala sin modo—: la llave es la fila que hay que republicar")
    void mediaPoliticaNombraSuFila() {
        assertThatThrownBy(
                        () ->
                                PoliticasDeRedondeoSelladas.de(
                                        conjunto()
                                                .numero(
                                                        PoliticasDeRedondeoSelladas.TIPO,
                                                        PuntoDeRedondeo.CUOTA.name(),
                                                        ValorNormativo.de("2"))
                                                .construir()))
                .isInstanceOfSatisfying(
                        PoliticasDeRedondeoSelladas.MediaPolitica.class,
                        falta -> {
                            assertThat(falta.ejercicio()).isEqualTo(EJERCICIO);
                            assertThat(falta.llave()).contains("REDONDEO:CUOTA");
                        });
    }

    @Test
    @DisplayName("una escala con decimales: la misma fila")
    void escalaNoEnteraNombraSuFila() {
        assertThatThrownBy(() -> PoliticasDeRedondeoSelladas.de(conCuota("2.5", "HALF_UP")))
                .isInstanceOfSatisfying(
                        PoliticasDeRedondeoSelladas.EscalaNoEntera.class,
                        falta -> {
                            assertThat(falta.ejercicio()).isEqualTo(EJERCICIO);
                            assertThat(falta.llave()).contains("REDONDEO:CUOTA");
                        });
    }

    @Test
    @DisplayName("un modo de redondeo que no existe: la misma fila")
    void modoDesconocidoNombraSuFila() {
        assertThatThrownBy(() -> PoliticasDeRedondeoSelladas.de(conCuota("2", "HACIA_ARRIBA")))
                .isInstanceOfSatisfying(
                        PoliticasDeRedondeoSelladas.ModoDesconocido.class,
                        falta -> {
                            assertThat(falta.ejercicio()).isEqualTo(EJERCICIO);
                            assertThat(falta.llave()).contains("REDONDEO:CUOTA");
                        });
    }

    @Test
    @DisplayName("y el ejercicio del punto mal parametrizado es el del conjunto, no otro")
    void elEjercicioDelPuntoEsElDelConjunto() {
        Ejercicio otro = new Ejercicio(2031);

        assertThatThrownBy(
                        () ->
                                PoliticasDeRedondeoSelladas.de(
                                        ParametrosSellados.de(otro, 1)
                                                .numero(
                                                        PoliticasDeRedondeoSelladas.TIPO,
                                                        PuntoDeRedondeo.CUOTA.name(),
                                                        ValorNormativo.de("2"))
                                                .texto(
                                                        PoliticasDeRedondeoSelladas.TIPO,
                                                        PuntoDeRedondeo.CUOTA.name(),
                                                        "HACIA_ARRIBA")
                                                .construir()))
                .isInstanceOfSatisfying(
                        PoliticasDeRedondeoSelladas.ModoDesconocido.class,
                        falta ->
                                assertThat(falta.ejercicio())
                                        .as(
                                                "los tres puntos no lo reciben del que los"
                                                        + " construye sino del conjunto que se estaba"
                                                        + " leyendo: si no, el 422 mandaria a publicar"
                                                        + " en el ano equivocado")
                                        .isEqualTo(otro));
    }

    @Test
    @DisplayName("#633 — el punto que el conjunto no observo: la fila que hay que publicar")
    void elPuntoSinObservarNombraSuFila() {
        assertThatThrownBy(
                        () ->
                                PoliticasDeRedondeoSelladas.en(
                                        conCuota("2", RoundingMode.HALF_UP.name()),
                                        PuntoDeRedondeo.IMPUESTO_POR_TRAMO))
                .isInstanceOfSatisfying(
                        PoliticasDeRedondeoSelladas.PuntoSinObservar.class,
                        falta -> {
                            assertThat(falta.ejercicio()).isEqualTo(EJERCICIO);
                            assertThat(falta.llave())
                                    .as(
                                            "aqui SI se sabe cual falta —lo pidio el calculo—, asi"
                                                    + " que la llave es la fila y no el TIPO solo")
                                    .contains("REDONDEO:IMPUESTO_POR_TRAMO");
                        });
    }

    @Test
    @DisplayName("CONTRASTE: un conjunto bien parametrizado no lanza ninguna de las cinco")
    void unConjuntoBienParametrizadoNoLanzaNada() {
        assertThat(
                        PoliticasDeRedondeoSelladas.de(conCuota("2", RoundingMode.HALF_UP.name()))
                                .puntos())
                .containsExactly(PuntoDeRedondeo.CUOTA);
    }

    private static ParametrosSellados conCuota(String escala, String modo) {
        return conjunto()
                .numero(
                        PoliticasDeRedondeoSelladas.TIPO,
                        PuntoDeRedondeo.CUOTA.name(),
                        ValorNormativo.de(escala))
                .texto(PoliticasDeRedondeoSelladas.TIPO, PuntoDeRedondeo.CUOTA.name(), modo)
                .construir();
    }

    private static ParametrosSellados.Constructor conjunto() {
        return ParametrosSellados.de(EJERCICIO, 1);
    }
}
