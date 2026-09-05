package kamayuk.rentas.nucleo.dominio.predial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import java.util.List;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RT-013, con un cuadro de tramos <b>ficticio</b> —no el del articulo 13, que sigue bloqueado por
 * D-02b—: solo demuestra el algoritmo (progresivo, acumulativo, sobre la base ya agregada).
 */
@DisplayName("TramosProgresivosAcumulativos (RT-013)")
class TramosProgresivosAcumulativosTest {

    /** Ficticia en su escala y su modo; el punto si es el que la regla nombra. */
    private static final PoliticasDeRedondeo REDONDEO =
            PoliticasDeRedondeo.construir()
                    .en(
                            PuntoDeRedondeo.IMPUESTO_POR_TRAMO,
                            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP))
                    .construir();

    /** Cuadro ficticio de tres tramos: 0.2 % hasta 1000, 0.6 % hasta 3000, 1.0 % en adelante. */
    private static final List<Tramo> CUADRO_FICTICIO =
            List.of(
                    Tramo.hasta(Dinero.de(1000), Alicuota.de("0.2")),
                    Tramo.hasta(Dinero.de(3000), Alicuota.de("0.6")),
                    Tramo.sinTope(Alicuota.de("1.0")));

    @Test
    @DisplayName("una base que cae entera en el primer tramo paga solo su alicuota")
    void unaBaseDentroDelPrimerTramo() {
        Dinero impuesto =
                TramosProgresivosAcumulativos.calcular(Dinero.de(500), CUADRO_FICTICIO, REDONDEO);

        // 500 * 0.2% = 1.00
        assertThat(impuesto).isEqualTo(Dinero.de("1.00"));
    }

    @Test
    @DisplayName(
            "acumulativo: cada tramo se aplica solo a la porcion que le cae, no a toda la base")
    void esAcumulativoNoUnSaltoDeTramo() {
        // 2000 = 1000 en el primer tramo (0.2%) + 1000 en el segundo (0.6%)
        Dinero impuesto =
                TramosProgresivosAcumulativos.calcular(Dinero.de(2000), CUADRO_FICTICIO, REDONDEO);

        // 1000*0.2% + 1000*0.6% = 2.00 + 6.00 = 8.00
        assertThat(impuesto).isEqualTo(Dinero.de("8.00"));
    }

    @Test
    @DisplayName("una base que excede todos los tramos con tope cae en el ultimo, sin tope")
    void unaBaseQueLlegaAlUltimoTramo() {
        // 5000 = 1000*0.2% + 2000*0.6% + 2000*1.0% = 2 + 12 + 20 = 34
        Dinero impuesto =
                TramosProgresivosAcumulativos.calcular(Dinero.de(5000), CUADRO_FICTICIO, REDONDEO);

        assertThat(impuesto).isEqualTo(Dinero.de("34.00"));
    }

    @Test
    @DisplayName("una base cero no paga nada")
    void unaBaseCeroNoPagaNada() {
        Dinero impuesto =
                TramosProgresivosAcumulativos.calcular(Dinero.CERO, CUADRO_FICTICIO, REDONDEO);

        assertThat(impuesto).isEqualTo(Dinero.CERO);
    }

    @Test
    @DisplayName("es lo mismo que aplicar predio por predio y sumar, siempre que se agregue antes")
    void demuestraElPuntoCriticoDeNeg05() {
        // Un contribuyente con dos predios de 1500 cada uno (base agregada: 3000) NO paga lo
        // mismo que dos determinaciones independientes de 1500 cada una: es exactamente el error
        // sistematico a la baja que NEG-05 §1 advierte.
        Dinero impuestoAgregado =
                TramosProgresivosAcumulativos.calcular(Dinero.de(3000), CUADRO_FICTICIO, REDONDEO);

        Dinero impuestoDeUnPredioSolo =
                TramosProgresivosAcumulativos.calcular(Dinero.de(1500), CUADRO_FICTICIO, REDONDEO);
        Dinero sumaIncorrectaPorPredio = impuestoDeUnPredioSolo.mas(impuestoDeUnPredioSolo);

        assertThat(impuestoAgregado).isNotEqualTo(sumaIncorrectaPorPredio);
        assertThat(impuestoAgregado.esMayorQue(sumaIncorrectaPorPredio)).isTrue();
    }

    @Test
    @DisplayName("una base negativa se rechaza")
    void unaBaseNegativaSeRechaza() {
        assertThatThrownBy(
                        () ->
                                TramosProgresivosAcumulativos.calcular(
                                        Dinero.de(-1), CUADRO_FICTICIO, REDONDEO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un cuadro de tramos vacio se rechaza")
    void unCuadroVacioSeRechaza() {
        assertThatThrownBy(
                        () ->
                                TramosProgresivosAcumulativos.calcular(
                                        Dinero.de(500), List.of(), REDONDEO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sin politica para su punto, el calculo falla en vez de no redondear")
    void sinPoliticaParaSuPuntoElCalculoFalla() {
        // Una parametrizacion que cubre otros puntos, pero no el que RT-013 redondea. Antes de
        // PuntoDeRedondeo este caso no existia: habia una politica para todo el calculo y el punto
        // que nadie habia observado simplemente no se redondeaba, produciendo un importe plausible.
        PoliticasDeRedondeo otroPunto =
                PoliticasDeRedondeo.construir()
                        .en(
                                PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO,
                                new PoliticaDeRedondeo(2, RoundingMode.HALF_UP))
                        .construir();

        assertThatThrownBy(
                        () ->
                                TramosProgresivosAcumulativos.calcular(
                                        Dinero.de(500), CUADRO_FICTICIO, otroPunto))
                .isInstanceOf(PoliticasDeRedondeo.PuntoSinPolitica.class)
                .hasMessageContaining("IMPUESTO_POR_TRAMO");
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("#395 — el desglose, para que la memoria de calculo no se recomponga")
    class Desglose {

        @Test
        @DisplayName("cada tramo dice su limite, su alicuota, la porcion que gravo y lo que puso")
        void cadaTramoDiceLoQueHizo() {
            List<AporteDeTramo> aportes =
                    TramosProgresivosAcumulativos.desglosar(Dinero.de(2000), CUADRO_FICTICIO);

            assertThat(aportes).hasSize(2);
            assertThat(aportes.get(0).orden()).isEqualTo(1);
            assertThat(aportes.get(0).limiteSuperior()).isEqualTo(Dinero.de(1000));
            assertThat(aportes.get(0).porcionGravada()).isEqualTo(Dinero.de(1000));
            assertThat(aportes.get(0).aporte()).isEqualTo(Dinero.de("2.00"));
            assertThat(aportes.get(1).orden()).isEqualTo(2);
            assertThat(aportes.get(1).porcionGravada()).isEqualTo(Dinero.de(1000));
            assertThat(aportes.get(1).aporte()).isEqualTo(Dinero.de("6.00"));
        }

        @Test
        @DisplayName("un tramo que no recibio nada de la base no aparece: no aporto")
        void elTramoQueNoRecibeNoAparece() {
            List<AporteDeTramo> aportes =
                    TramosProgresivosAcumulativos.desglosar(Dinero.de(500), CUADRO_FICTICIO);

            assertThat(aportes).hasSize(1);
            assertThat(aportes.get(0).orden()).isEqualTo(1);
        }

        @Test
        @DisplayName("el ultimo tramo no tiene tope, y lo dice")
        void elUltimoNoTieneTope() {
            List<AporteDeTramo> aportes =
                    TramosProgresivosAcumulativos.desglosar(Dinero.de(5000), CUADRO_FICTICIO);

            assertThat(aportes).hasSize(3);
            assertThat(aportes.get(2).tieneTope()).isFalse();
            assertThat(aportes.get(2).limiteSuperior()).isNull();
            assertThat(aportes.get(2).porcionGravada()).isEqualTo(Dinero.de(2000));
        }

        @Test
        @DisplayName("el desglose es el mismo calculo: sus aportes suman el impuesto")
        void elDesgloseEsElMismoCalculo() {
            Dinero base = Dinero.de(5000);

            Dinero sumaDeAportes = Dinero.CERO;
            for (AporteDeTramo aporte :
                    TramosProgresivosAcumulativos.desglosar(base, CUADRO_FICTICIO)) {
                sumaDeAportes = sumaDeAportes.mas(aporte.aporte());
            }

            assertThat(sumaDeAportes)
                    .isEqualTo(
                            TramosProgresivosAcumulativos.calcular(
                                    base, CUADRO_FICTICIO, REDONDEO));
        }

        @Test
        @DisplayName("los aportes salen SIN redondear: el redondeo es del impuesto, una sola vez")
        void losAportesNoSeRedondean() {
            // 0.2 % de 71 156.75 es 142.3135, con cuatro decimales. ADR-0018: los intermedios
            // corren sin redondear y solo el cierre de la regla redondea. Si el desglose
            // redondeara cada aporte, la suma de las porciones dejaria de ser el impuesto.
            List<AporteDeTramo> aportes =
                    TramosProgresivosAcumulativos.desglosar(
                            Dinero.de("71156.75"), List.of(Tramo.sinTope(Alicuota.de("0.2"))));

            assertThat(aportes.get(0).aporte().valor().stripTrailingZeros().toPlainString())
                    .isEqualTo("142.3135");
        }
    }
}
