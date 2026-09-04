package kamayuk.rentas.indicadores.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalInt;
import kamayuk.rentas.dominio.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Las dos funciones puras del panel: como se escribe una cifra y como se mide un avance (#56).
 *
 * <p>Sin Spring, sin base y sin reloj: es lo que se gana separandolas del caso de uso.
 */
@DisplayName("#56 — El formato de una cifra y el avance de cobranza")
class FormatoYAvanceTest {

    @Nested
    @DisplayName("Formato")
    class Formato {

        @Test
        @DisplayName("un importe lleva su moneda, sus miles y sus dos decimales")
        void unImporteSeEscribeCompleto() {
            assertThat(FormatoDeCifra.importe(Dinero.de("18415232.40")))
                    .isEqualTo("S/ 18,415,232.40");
            assertThat(FormatoDeCifra.importe(Dinero.de("999.99"))).isEqualTo("S/ 999.99");
            assertThat(FormatoDeCifra.importe(Dinero.de("1000.00"))).isEqualTo("S/ 1,000.00");
            assertThat(FormatoDeCifra.importe(Dinero.CERO)).isEqualTo("S/ 0.00");
        }

        @Test
        @DisplayName("los decimales que faltan se completan")
        void losDecimalesQueFaltanSeCompletan() {
            // Dinero.de(long) no trae decimales, y una pantalla que dijera «S/ 100» al
            // lado de «S/ 99.50» invita a leer mal la columna.
            assertThat(FormatoDeCifra.importe(Dinero.de(100))).isEqualTo("S/ 100.00");
            assertThat(FormatoDeCifra.importe(Dinero.de("12.5"))).isEqualTo("S/ 12.50");
        }

        @Test
        @DisplayName("los decimales que sobran NO se recortan: recortar seria redondear")
        void losDecimalesQueSobranSeMuestran() {
            // Un importe con mas de dos decimales no deberia llegar aqui —el dominio
            // `dinero` es numeric(15,2)—, pero si llega se ve. Recortarlo exigiria una
            // PoliticaDeRedondeo que sale de un conjunto sellado (D-03), y escribir el
            // setScale a mano lo rechaza el escaner de fuentes. Ademas: un numero largo
            // en pantalla se nota, y un centimo desaparecido no.
            assertThat(FormatoDeCifra.importe(Dinero.de("12.345"))).isEqualTo("S/ 12.345");
        }

        @Test
        @DisplayName("el negativo lleva el signo delante, no un parentesis")
        void elNegativoLlevaSuSigno() {
            assertThat(FormatoDeCifra.importe(Dinero.de("-1234.50"))).isEqualTo("-S/ 1,234.50");
        }

        @Test
        @DisplayName("un recuento lleva sus miles, y un porcentaje su signo")
        void unRecuentoYUnPorcentaje() {
            assertThat(FormatoDeCifra.cantidad(24118)).isEqualTo("24,118");
            assertThat(FormatoDeCifra.cantidad(0)).isEqualTo("0");
            assertThat(FormatoDeCifra.cantidad(1000000)).isEqualTo("1,000,000");
            assertThat(FormatoDeCifra.porcentaje(77)).isEqualTo("77 %");
        }
    }

    @Nested
    @DisplayName("Avance")
    class Avance {

        @Test
        @DisplayName("es la parte sobre la base, en porcentaje entero")
        void esLaParteSobreLaBase() {
            assertThat(AvanceDeCobranza.de(Dinero.de("50.00"), Dinero.de("200.00")))
                    .isEqualTo(OptionalInt.of(25));
            assertThat(AvanceDeCobranza.de(Dinero.de("200.00"), Dinero.de("200.00")))
                    .isEqualTo(OptionalInt.of(100));
        }

        @Test
        @DisplayName("trunca, no redondea: el 99,7 % se dibuja como 99")
        void trunca() {
            // Una barra que dijera 100 con deuda viva es peor que una que se queda corta.
            assertThat(AvanceDeCobranza.de(Dinero.de("997.00"), Dinero.de("1000.00")))
                    .isEqualTo(OptionalInt.of(99));
            assertThat(AvanceDeCobranza.de(Dinero.de("0.01"), Dinero.de("1000.00")))
                    .isEqualTo(OptionalInt.of(0));
        }

        @Test
        @DisplayName("sin base no hay porcentaje: devuelve vacio, no cero")
        void sinBaseNoHayPorcentaje() {
            // Es la regla 5 donde suele escaparse: la cifra que no existe no se rellena
            // con la que quede mas a mano. Un 0 % se lee como «no se ha cobrado nada»,
            // que es un juicio sobre la gestion de un tributo que no tiene ni cargos.
            assertThat(AvanceDeCobranza.de(Dinero.de("100.00"), Dinero.CERO))
                    .isEqualTo(OptionalInt.empty());
            assertThat(AvanceDeCobranza.de(Dinero.CERO, Dinero.de("-5.00")))
                    .isEqualTo(OptionalInt.empty());
        }

        @Test
        @DisplayName("nunca pasa de 100 ni baja de 0")
        void nuncaSaleDelRango() {
            // Pasar de 100 es posible de verdad: se cobra tambien el interes, que no se
            // carga como insoluto. La barra se queda en 100 y el importe de la linea
            // sigue diciendo la cifra exacta.
            assertThat(AvanceDeCobranza.de(Dinero.de("115.00"), Dinero.de("100.00")))
                    .isEqualTo(OptionalInt.of(AvanceDeCobranza.COMPLETO));
            assertThat(AvanceDeCobranza.de(Dinero.de("-10.00"), Dinero.de("100.00")))
                    .isEqualTo(OptionalInt.of(0));
        }
    }
}
