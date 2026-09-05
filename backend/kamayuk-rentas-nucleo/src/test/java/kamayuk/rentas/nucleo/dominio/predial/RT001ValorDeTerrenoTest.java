package kamayuk.rentas.nucleo.dominio.predial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.CaracteristicasDeLaPartida;
import kamayuk.rentas.parametros.CatalogoDeReglas;
import kamayuk.rentas.parametros.EntradaDeCalculo;
import kamayuk.rentas.parametros.EstadoDelCalculo;
import kamayuk.rentas.parametros.MotorDeReglas;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.ResultadoDelCalculo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RT-001, con un arancel <b>ficticio</b>: no el de ninguna via real, que sigue bloqueado por D-02a.
 * Lo que estas pruebas demuestran es la forma —area por arancel, arancel como parametro, via como
 * caracteristica— y, sobre todo, <b>que pasa cuando falta algo</b>.
 */
@DisplayName("RT-001 — Valor de terreno")
class RT001ValorDeTerrenoTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final PoliticasDeRedondeo REDONDEO =
            PoliticasDeRedondeo.construir()
                    .en(
                            PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO,
                            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP))
                    .construir();

    @Test
    @DisplayName("el valor del terreno es su area por el arancel de su via")
    void areaPorArancel() {
        ResultadoDelCalculo resultado =
                motor().aplicarA(
                                entrada(
                                        "250",
                                        "AV-GRAU",
                                        ParametrosSellados.de(EJERCICIO, 1)
                                                .numero(
                                                        "ARANCEL",
                                                        "AV-GRAU",
                                                        ValorNormativo.de("4"))
                                                .construir()));

        assertThat(resultado.exigir(RT001ValorDeTerreno.VALOR_TERRENO)).isEqualTo(Dinero.de(1000));
        assertThat(resultado.reglasComoTexto()).containsExactly("RT-001");
    }

    @Test
    @DisplayName("el arancel es de la via de la partida, no uno cualquiera del conjunto")
    void elArancelEsElDeSuVia() {
        ParametrosSellados dosVias =
                ParametrosSellados.de(EJERCICIO, 1)
                        .numero("ARANCEL", "AV-GRAU", ValorNormativo.de("4"))
                        .numero("ARANCEL", "JR-LIMA", ValorNormativo.de("7"))
                        .construir();

        assertThat(
                        motor().aplicarA(entrada("100", "JR-LIMA", dosVias))
                                .exigir(RT001ValorDeTerreno.VALOR_TERRENO))
                .isEqualTo(Dinero.de(700));
    }

    @Test
    @DisplayName("sin arancel para su via no devuelve cero: falla nombrando el que falta")
    void sinArancelFalla() {
        ParametrosSellados sinEsaVia =
                ParametrosSellados.de(EJERCICIO, 1)
                        .numero("ARANCEL", "AV-GRAU", ValorNormativo.de("4"))
                        .construir();

        assertThatThrownBy(() -> motor().aplicarA(entrada("250", "AV-NUEVA", sinEsaVia)))
                .isInstanceOf(ParametrosSellados.ParametroAusente.class)
                .hasMessageContaining("ARANCEL:AV-NUEVA")
                .satisfies(
                        fallo ->
                                assertThat(((ParametrosSellados.ParametroAusente) fallo).llave())
                                        .contains("ARANCEL:AV-NUEVA"));
    }

    @Test
    @DisplayName("sin saber a que via da el predio, la regla no adivina: falla")
    void sinViaFalla() {
        EntradaDeCalculo sinVia =
                new EntradaDeCalculo(
                        EJERCICIO,
                        EstadoDelCalculo.con(RT001ValorDeTerreno.AREA_TERRENO, Dinero.de("250")),
                        CaracteristicasDeLaPartida.ninguna(),
                        ParametrosSellados.de(EJERCICIO, 1)
                                .numero("ARANCEL", "AV-GRAU", ValorNormativo.de("4"))
                                .construir(),
                        REDONDEO);

        assertThatThrownBy(() -> motor().aplicarA(sinVia))
                .isInstanceOf(CaracteristicasDeLaPartida.CaracteristicaAusente.class)
                .hasMessageContaining("via");
    }

    @Test
    @DisplayName("ninguna cifra vive en la regla: cambiar el parametro cambia el resultado")
    void ningunaCifraViveEnLaRegla() {
        Dinero conCuatro =
                motor().aplicarA(
                                entrada(
                                        "100",
                                        "AV-GRAU",
                                        ParametrosSellados.de(EJERCICIO, 1)
                                                .numero(
                                                        "ARANCEL",
                                                        "AV-GRAU",
                                                        ValorNormativo.de("4"))
                                                .construir()))
                        .exigir(RT001ValorDeTerreno.VALOR_TERRENO);
        Dinero conOcho =
                motor().aplicarA(
                                entrada(
                                        "100",
                                        "AV-GRAU",
                                        ParametrosSellados.de(EJERCICIO, 1)
                                                .numero(
                                                        "ARANCEL",
                                                        "AV-GRAU",
                                                        ValorNormativo.de("8"))
                                                .construir()))
                        .exigir(RT001ValorDeTerreno.VALOR_TERRENO);

        assertThat(conOcho).isEqualTo(conCuatro.mas(conCuatro));
    }

    private static MotorDeReglas motor() {
        return new MotorDeReglas(CatalogoDeReglas.vacio().con(new RT001ValorDeTerreno()));
    }

    private static EntradaDeCalculo entrada(String area, String via, ParametrosSellados sellados) {
        return new EntradaDeCalculo(
                EJERCICIO,
                EstadoDelCalculo.con(RT001ValorDeTerreno.AREA_TERRENO, Dinero.de(area)),
                CaracteristicasDeLaPartida.de(RT001ValorDeTerreno.VIA, via).construir(),
                sellados,
                REDONDEO);
    }
}
