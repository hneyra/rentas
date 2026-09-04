package kamayuk.rentas.parametros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E-7 §Entregable 3 (#203): la respuesta de D-03c entra como dato, no como codigo.
 *
 * <p>Los valores de estas pruebas son <b>ficticios</b>: no son los del SRTM del MEF, que siguen sin
 * observar. Lo que verifican es el camino y, sobre todo, <b>que pasa cuando el dato llega a
 * medias</b>.
 */
@DisplayName("Las politicas de redondeo salen del conjunto sellado (D-03c)")
class PoliticasDeRedondeoSelladasTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    @Test
    @DisplayName("un punto parametrizado sale con su escala y su modo")
    void unPuntoSaleConSuEscalaYSuModo() {
        PoliticasDeRedondeo politicas =
                PoliticasDeRedondeoSelladas.de(
                        conjunto()
                                .numero(
                                        PoliticasDeRedondeoSelladas.TIPO,
                                        PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO.name(),
                                        ValorNormativo.de("2"))
                                .texto(
                                        PoliticasDeRedondeoSelladas.TIPO,
                                        PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO.name(),
                                        "HALF_UP")
                                .construir());

        assertThat(politicas.en(PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO))
                .isEqualTo(new PoliticaDeRedondeo(2, RoundingMode.HALF_UP));
        assertThat(politicas.puntos()).containsExactly(PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO);
    }

    @Test
    @DisplayName("cada punto lleva la suya: dos puntos, dos escalas distintas")
    void cadaPuntoLlevaLaSuya() {
        PoliticasDeRedondeo politicas =
                PoliticasDeRedondeoSelladas.de(
                        conjunto()
                                .numero(
                                        PoliticasDeRedondeoSelladas.TIPO,
                                        PuntoDeRedondeo.METRADO_DE_OBRA.name(),
                                        ValorNormativo.de("0"))
                                .texto(
                                        PoliticasDeRedondeoSelladas.TIPO,
                                        PuntoDeRedondeo.METRADO_DE_OBRA.name(),
                                        "DOWN")
                                .numero(
                                        PoliticasDeRedondeoSelladas.TIPO,
                                        PuntoDeRedondeo.IMPUESTO_ANUAL.name(),
                                        ValorNormativo.de("2"))
                                .texto(
                                        PoliticasDeRedondeoSelladas.TIPO,
                                        PuntoDeRedondeo.IMPUESTO_ANUAL.name(),
                                        "HALF_EVEN")
                                .construir());

        assertThat(politicas.en(PuntoDeRedondeo.METRADO_DE_OBRA))
                .as("el metrado que M02 mostro redondeado no tiene por que ir a dos decimales")
                .isEqualTo(new PoliticaDeRedondeo(0, RoundingMode.DOWN));
        assertThat(politicas.en(PuntoDeRedondeo.IMPUESTO_ANUAL))
                .isEqualTo(new PoliticaDeRedondeo(2, RoundingMode.HALF_EVEN));
    }

    @Test
    @DisplayName("un punto que el conjunto no parametriza sigue fallando al usarlo")
    void unPuntoNoParametrizadoSigueFallando() {
        PoliticasDeRedondeo politicas =
                PoliticasDeRedondeoSelladas.de(
                        conjunto()
                                .numero(
                                        PoliticasDeRedondeoSelladas.TIPO,
                                        PuntoDeRedondeo.CUOTA.name(),
                                        ValorNormativo.de("2"))
                                .texto(
                                        PoliticasDeRedondeoSelladas.TIPO,
                                        PuntoDeRedondeo.CUOTA.name(),
                                        "HALF_UP")
                                .construir());

        assertThatThrownBy(() -> politicas.en(PuntoDeRedondeo.INTERES))
                .as("leerlas del conjunto no relaja la garantia: lo no observado falla")
                .isInstanceOf(PoliticasDeRedondeo.PuntoSinPolitica.class);
    }

    @Test
    @DisplayName("la escala sin el modo no pasa: media politica aparenta estar resuelta")
    void laEscalaSinElModoNoPasa() {
        assertThatThrownBy(
                        () ->
                                PoliticasDeRedondeoSelladas.de(
                                        conjunto()
                                                .numero(
                                                        PoliticasDeRedondeoSelladas.TIPO,
                                                        PuntoDeRedondeo.CUOTA.name(),
                                                        ValorNormativo.de("2"))
                                                .construir()))
                .isInstanceOf(PoliticasDeRedondeoSelladas.MediaPolitica.class)
                .hasMessageContaining("escala sin modo")
                .hasMessageContaining("CUOTA");
    }

    @Test
    @DisplayName("el modo sin la escala tampoco")
    void elModoSinLaEscalaTampoco() {
        assertThatThrownBy(
                        () ->
                                PoliticasDeRedondeoSelladas.de(
                                        conjunto()
                                                .texto(
                                                        PoliticasDeRedondeoSelladas.TIPO,
                                                        PuntoDeRedondeo.CUOTA.name(),
                                                        "HALF_UP")
                                                .construir()))
                .isInstanceOf(PoliticasDeRedondeoSelladas.MediaPolitica.class)
                .hasMessageContaining("modo sin escala");
    }

    @Test
    @DisplayName("una escala con decimales no significa nada, y se dice")
    void unaEscalaConDecimalesNoSignificaNada() {
        assertThatThrownBy(
                        () ->
                                PoliticasDeRedondeoSelladas.de(
                                        conjunto()
                                                .numero(
                                                        PoliticasDeRedondeoSelladas.TIPO,
                                                        PuntoDeRedondeo.CUOTA.name(),
                                                        ValorNormativo.de("2.5"))
                                                .texto(
                                                        PoliticasDeRedondeoSelladas.TIPO,
                                                        PuntoDeRedondeo.CUOTA.name(),
                                                        "HALF_UP")
                                                .construir()))
                .isInstanceOf(PoliticasDeRedondeoSelladas.EscalaNoEntera.class)
                .hasMessageContaining("2.5");
    }

    @Test
    @DisplayName("«2.000000» es 2: la escala llega con los decimales del dominio monto_calc")
    void laEscalaLlegaConLosDecimalesDelDominio() {
        PoliticasDeRedondeo politicas =
                PoliticasDeRedondeoSelladas.de(
                        conjunto()
                                .numero(
                                        PoliticasDeRedondeoSelladas.TIPO,
                                        PuntoDeRedondeo.CUOTA.name(),
                                        ValorNormativo.de("2.000000"))
                                .texto(
                                        PoliticasDeRedondeoSelladas.TIPO,
                                        PuntoDeRedondeo.CUOTA.name(),
                                        "HALF_UP")
                                .construir());

        assertThat(politicas.en(PuntoDeRedondeo.CUOTA).escala())
                .as("valor_numerico es numeric(18,6): 2 llega como 2.000000 y sigue siendo 2")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("un modo que no existe se rechaza nombrando los que si")
    void unModoQueNoExisteSeRechaza() {
        assertThatThrownBy(
                        () ->
                                PoliticasDeRedondeoSelladas.de(
                                        conjunto()
                                                .numero(
                                                        PoliticasDeRedondeoSelladas.TIPO,
                                                        PuntoDeRedondeo.CUOTA.name(),
                                                        ValorNormativo.de("2"))
                                                .texto(
                                                        PoliticasDeRedondeoSelladas.TIPO,
                                                        PuntoDeRedondeo.CUOTA.name(),
                                                        "AL_ALZA")
                                                .construir()))
                .isInstanceOf(PoliticasDeRedondeoSelladas.ModoDesconocido.class)
                .hasMessageContaining("AL_ALZA")
                .hasMessageContaining("HALF_UP");
    }

    @Test
    @DisplayName("UNNECESSARY no se admite ni viniendo de la base")
    void unnecessaryNoSeAdmiteNiDesdeLaBase() {
        assertThatThrownBy(
                        () ->
                                PoliticasDeRedondeoSelladas.de(
                                        conjunto()
                                                .numero(
                                                        PoliticasDeRedondeoSelladas.TIPO,
                                                        PuntoDeRedondeo.CUOTA.name(),
                                                        ValorNormativo.de("2"))
                                                .texto(
                                                        PoliticasDeRedondeoSelladas.TIPO,
                                                        PuntoDeRedondeo.CUOTA.name(),
                                                        "UNNECESSARY")
                                                .construir()))
                .as("la guarda del record vale igual para un dato que para un literal")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNNECESSARY");
    }

    @Test
    @DisplayName("un conjunto sin ningun punto falla aqui, no trece veces mas adelante")
    void unConjuntoSinNingunPuntoFalla() {
        assertThatThrownBy(
                        () ->
                                PoliticasDeRedondeoSelladas.de(
                                        conjunto()
                                                .numero("UIT", null, ValorNormativo.de("1"))
                                                .construir()))
                .isInstanceOf(PoliticasDeRedondeoSelladas.SinPuntosObservados.class)
                .hasMessageContaining("2026")
                .hasMessageContaining("D-03c");
    }

    @Test
    @DisplayName("ninguna escala ni ningun modo viven en esta clase: cambiar el dato cambia todo")
    void ningunaCifraViveEnLaClase() {
        PoliticasDeRedondeo conDos = politicasDeCuota("2", "HALF_UP");
        PoliticasDeRedondeo conCero = politicasDeCuota("0", "DOWN");

        assertThat(conDos.en(PuntoDeRedondeo.CUOTA))
                .isNotEqualTo(conCero.en(PuntoDeRedondeo.CUOTA));
        assertThat(conCero.en(PuntoDeRedondeo.CUOTA))
                .isEqualTo(new PoliticaDeRedondeo(0, RoundingMode.DOWN));
    }

    // -------------------------------------------------- #633: el punto que el conjunto no observo

    @Test
    @DisplayName("#633 — pedir el punto por `en` dice de QUE ejercicio es la fila que falta")
    void elPuntoQueFaltaDiceSuEjercicio() {
        ParametrosSellados conjunto =
                conjunto()
                        .numero(
                                PoliticasDeRedondeoSelladas.TIPO,
                                PuntoDeRedondeo.IMPUESTO_POR_TRAMO.name(),
                                ValorNormativo.de("2"))
                        .texto(
                                PoliticasDeRedondeoSelladas.TIPO,
                                PuntoDeRedondeo.IMPUESTO_POR_TRAMO.name(),
                                "HALF_UP")
                        .construir();

        assertThatThrownBy(() -> PoliticasDeRedondeoSelladas.en(conjunto, PuntoDeRedondeo.CUOTA))
                .as(
                        "`PuntoSinPolitica` es dominio puro y solo puede nombrar el punto; quien"
                                + " sabe el ejercicio es quien acaba de leer el conjunto")
                .isInstanceOfSatisfying(
                        PoliticasDeRedondeoSelladas.PuntoSinObservar.class,
                        falta -> {
                            assertThat(falta.ejercicio()).isEqualTo(EJERCICIO);
                            assertThat(falta.llave()).contains("REDONDEO:CUOTA");
                        })
                .hasMessageContaining("2026")
                .hasMessageContaining("REDONDEO:CUOTA")
                .as("y conserva los que SI estan: es la mitad del trabajo de quien va a publicar")
                .hasMessageContaining("IMPUESTO_POR_TRAMO")
                .hasCauseInstanceOf(PoliticasDeRedondeo.PuntoSinPolitica.class);
    }

    @Test
    @DisplayName("#633 — un conjunto sin ningun punto sigue siendo la OTRA falta, no esta")
    void sinNingunPuntoSigueSiendoLaOtraFalta() {
        ParametrosSellados sinRedondeo =
                conjunto().numero("UIT", null, ValorNormativo.de("1")).construir();

        assertThatThrownBy(() -> PoliticasDeRedondeoSelladas.en(sinRedondeo, PuntoDeRedondeo.CUOTA))
                .as(
                        "son dos estados distintos y se publican distinto: aqui falta el bloque"
                                + " entero y nadie sabe cual de los trece puntos queria el que"
                                + " llamo; en `PuntoSinObservar` falta uno y se sabe cual")
                .isInstanceOf(PoliticasDeRedondeoSelladas.SinPuntosObservados.class);
    }

    @Test
    @DisplayName("#633 — CONTRASTE: si el punto esta, `en` devuelve su politica y no lanza nada")
    void siElPuntoEstaNoLanzaNada() {
        assertThat(
                        PoliticasDeRedondeoSelladas.en(
                                politicasSelladasDeCuota("2", "HALF_UP"), PuntoDeRedondeo.CUOTA))
                .as("una traduccion que se dispara siempre no traduce, rompe")
                .isEqualTo(new PoliticaDeRedondeo(2, RoundingMode.HALF_UP));
    }

    private static ParametrosSellados politicasSelladasDeCuota(String escala, String modo) {
        return conjunto()
                .numero(
                        PoliticasDeRedondeoSelladas.TIPO,
                        PuntoDeRedondeo.CUOTA.name(),
                        ValorNormativo.de(escala))
                .texto(PoliticasDeRedondeoSelladas.TIPO, PuntoDeRedondeo.CUOTA.name(), modo)
                .construir();
    }

    private static PoliticasDeRedondeo politicasDeCuota(String escala, String modo) {
        return PoliticasDeRedondeoSelladas.de(
                conjunto()
                        .numero(
                                PoliticasDeRedondeoSelladas.TIPO,
                                PuntoDeRedondeo.CUOTA.name(),
                                ValorNormativo.de(escala))
                        .texto(PoliticasDeRedondeoSelladas.TIPO, PuntoDeRedondeo.CUOTA.name(), modo)
                        .construir());
    }

    private static ParametrosSellados.Constructor conjunto() {
        return ParametrosSellados.de(EJERCICIO, 1);
    }
}
