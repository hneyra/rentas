package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La parte de D-03c que se puede construir sin la campana de observacion: <b>el tipo que puede
 * expresar la respuesta</b>.
 *
 * <p>Las escalas y los modos de estas pruebas son ficticios —D-03a y D-03b siguen abiertas—; lo que
 * se verifica no es cuanto se redondea sino <b>que pasa cuando un punto no esta parametrizado</b>,
 * que es donde estaba el modo de falla silencioso.
 */
@DisplayName("PoliticasDeRedondeo (D-03c)")
class PoliticasDeRedondeoTest {

    private static final PoliticaDeRedondeo FICTICIA = new PoliticaDeRedondeo(2, RoundingMode.DOWN);
    private static final PoliticaDeRedondeo OTRA_FICTICIA =
            new PoliticaDeRedondeo(0, RoundingMode.DOWN);

    @Test
    @DisplayName("cada punto resuelve su propia politica, no una comun")
    void cadaPuntoResuelveLaSuya() {
        PoliticasDeRedondeo politicas =
                PoliticasDeRedondeo.construir()
                        .en(PuntoDeRedondeo.METRADO_DE_OBRA, OTRA_FICTICIA)
                        .en(PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO, FICTICIA)
                        .construir();

        assertThat(politicas.en(PuntoDeRedondeo.METRADO_DE_OBRA)).isEqualTo(OTRA_FICTICIA);
        assertThat(politicas.en(PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO)).isEqualTo(FICTICIA);
    }

    @Test
    @DisplayName("un punto sin politica es una excepcion, nunca «no redondear»")
    void unPuntoSinPoliticaFalla() {
        PoliticasDeRedondeo politicas =
                PoliticasDeRedondeo.construir()
                        .en(PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO, FICTICIA)
                        .construir();

        assertThatThrownBy(() -> politicas.en(PuntoDeRedondeo.CUOTA))
                .isInstanceOf(PoliticasDeRedondeo.PuntoSinPolitica.class)
                .hasMessageContaining("CUOTA")
                .hasMessageContaining("AUTOVALUO_DEL_PREDIO");
    }

    @Test
    @DisplayName("el importe de un punto sin politica no sale sin redondear: no sale")
    void elImporteDeUnPuntoSinPoliticaNoSale() {
        PoliticasDeRedondeo politicas =
                PoliticasDeRedondeo.construir()
                        .en(PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO, FICTICIA)
                        .construir();
        Dinero importe = Dinero.de("1234.567");

        // Con una politica unica para todo el calculo, esto devolvia 1234.567 y nadie se enteraba.
        assertThatThrownBy(() -> importe.redondeadoEn(PuntoDeRedondeo.CUOTA, politicas))
                .isInstanceOf(PoliticasDeRedondeo.PuntoSinPolitica.class);

        assertThat(importe.redondeadoEn(PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO, politicas))
                .isEqualTo(Dinero.de("1234.56"));
    }

    @Test
    @DisplayName("preguntar por un punto no lo inventa")
    void preguntarPorUnPuntoNoLoInventa() {
        PoliticasDeRedondeo politicas =
                PoliticasDeRedondeo.construir()
                        .en(PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO, FICTICIA)
                        .construir();

        assertThat(politicas.politicaDe(PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO)).contains(FICTICIA);
        assertThat(politicas.politicaDe(PuntoDeRedondeo.REAJUSTE)).isEmpty();
        assertThat(politicas.puntos()).containsExactly(PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO);
    }

    @Test
    @DisplayName("una parametrizacion sin ningun punto se rechaza al construirla")
    void unaParametrizacionVaciaSeRechaza() {
        assertThatThrownBy(() -> PoliticasDeRedondeo.construir().construir())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no redondearia nada");
    }

    @Test
    @DisplayName("el ultimo punto parametrizado manda, y la parametrizacion es inmutable")
    void elUltimoPuntoParametrizadoManda() {
        PoliticasDeRedondeo.Constructor constructor =
                PoliticasDeRedondeo.construir().en(PuntoDeRedondeo.CUOTA, FICTICIA);
        PoliticasDeRedondeo primera = constructor.construir();

        constructor.en(PuntoDeRedondeo.CUOTA, OTRA_FICTICIA);

        assertThat(primera.en(PuntoDeRedondeo.CUOTA)).isEqualTo(FICTICIA);
        assertThat(constructor.construir().en(PuntoDeRedondeo.CUOTA)).isEqualTo(OTRA_FICTICIA);
    }
}
