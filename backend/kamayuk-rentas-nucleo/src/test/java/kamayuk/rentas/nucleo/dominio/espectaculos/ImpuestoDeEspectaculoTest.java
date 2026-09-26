package kamayuk.rentas.nucleo.dominio.espectaculos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#32 — ImpuestoDeEspectaculo: alicuota por tipo sobre el ingreso declarado")
class ImpuestoDeEspectaculoTest {

    /** La politica de ADR-0018: escala 2, HALF_UP. Aqui es un dato de la prueba. */
    private static final PoliticaDeRedondeo ADR_0018 =
            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

    @Test
    @DisplayName("aplica la alicuota del tipo sobre el ingreso declarado")
    void aplicaLaAlicuotaSobreElIngreso() {
        Dinero resultado =
                ImpuestoDeEspectaculo.calcular(
                        Dinero.de("10000.00"), Alicuota.de("10.0"), ADR_0018);
        assertThat(resultado).isEqualTo(Dinero.de("1000.00"));
    }

    @Test
    @DisplayName("una alicuota cero no genera impuesto: un espectaculo exonerado")
    void unaAlicuotaCeroNoGeneraImpuesto() {
        Dinero resultado =
                ImpuestoDeEspectaculo.calcular(Dinero.de("10000.00"), Alicuota.de("0"), ADR_0018);
        assertThat(resultado).isEqualTo(Dinero.CERO);
    }

    /**
     * #378 — El medio centimo exacto: 12 345,65 × 10 % = 1 234,565.
     *
     * <p>Es la siembra que distingue: {@code HALF_UP} da 1 234,57 y {@code HALF_EVEN} y {@code
     * DOWN} dan 1 234,56. Un ingreso redondo —10 000 × 10 %— da lo mismo con los tres modos y sin
     * redondear, y no demuestra nada. Con los tres modos a la vez, la cifra sigue a la politica que
     * llega, y no a una escrita en la regla.
     */
    @Test
    @DisplayName("#378 — 12 345,65 al 10 %: HALF_UP da 1234.57, HALF_EVEN y DOWN 1234.56")
    void redondeaElMedioCentimoConElModoQueRecibe() {
        Dinero ingreso = Dinero.de("12345.65");
        Alicuota diezPorCiento = Alicuota.de("10.0");

        assertThat(
                        ImpuestoDeEspectaculo.calcular(ingreso, diezPorCiento, ADR_0018)
                                .valor()
                                .toPlainString())
                .isEqualTo("1234.57");
        assertThat(
                        ImpuestoDeEspectaculo.calcular(
                                        ingreso,
                                        diezPorCiento,
                                        new PoliticaDeRedondeo(2, RoundingMode.HALF_EVEN))
                                .valor()
                                .toPlainString())
                .isEqualTo("1234.56");
        assertThat(
                        ImpuestoDeEspectaculo.calcular(
                                        ingreso,
                                        diezPorCiento,
                                        new PoliticaDeRedondeo(2, RoundingMode.DOWN))
                                .valor()
                                .toPlainString())
                .isEqualTo("1234.56");
    }

    @Test
    @DisplayName("#378 — cierra en IMPUESTO_ESPECTACULO, y sin politica no calcula")
    void cierraEnSuPuntoYSinPoliticaNoCalcula() {
        assertThat(ImpuestoDeEspectaculo.PUNTO_DE_REDONDEO)
                .isEqualTo(PuntoDeRedondeo.IMPUESTO_ESPECTACULO);
        assertThatThrownBy(
                        () ->
                                ImpuestoDeEspectaculo.calcular(
                                        Dinero.de("12345.67"), Alicuota.de("10.0"), null))
                .as("no hay politica por omision: sin ella no se devuelve el producto crudo")
                .isInstanceOf(NullPointerException.class);
    }
}
