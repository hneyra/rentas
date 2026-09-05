package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.nucleo.dominio.predial.Tramo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * De donde sale el cuadro del predial (#395). Sin base y sin reloj: lo que se verifica es que
 * <b>ninguna cifra sale del codigo</b> y que la que falta se nombra.
 *
 * <p>Las cifras de las pruebas son las que el corpus ya publica y verifica —UIT 5 500,00 de 2026 y
 * los tres tramos del articulo 13: 0.2 % hasta 15 UIT, 0.6 % hasta 60 UIT, 1.0 % en adelante—, y
 * estan aqui para que se vea el efecto de la conversion UIT→soles, no para fijarlas: la clase bajo
 * prueba no conoce ninguna.
 */
@DisplayName("#395 — El cuadro del predial sale del conjunto sellado")
class CuadroPredialParametrizadoTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    @Test
    @DisplayName("los limites llegan en UIT y salen en soles, con la UIT del mismo conjunto")
    void losLimitesSeConviertenConLaUitDelConjunto() {
        List<Tramo> tramos = cuadroCompleto().vigenteEn(EJERCICIO).tramos();

        assertThat(tramos).hasSize(3);
        assertThat(tramos.get(0).limiteSuperior()).isEqualTo(Dinero.de("82500.00"));
        assertThat(tramos.get(0).alicuota().valor().toPlainString()).isEqualTo("0.2");
        assertThat(tramos.get(1).limiteSuperior()).isEqualTo(Dinero.de("330000.00"));
        assertThat(tramos.get(2).tieneTope()).isFalse();
        assertThat(tramos.get(2).alicuota().valor().toPlainString()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("el minimo imponible es un % de la UIT, y se convierte con esa misma UIT")
    void elMinimoEsUnPorcentajeDeLaUit() {
        assertThat(cuadroCompleto().vigenteEn(EJERCICIO).minimoImponible())
                .isEqualTo(Dinero.de("33.00"));
    }

    @Test
    @DisplayName("cuantos tramos hay lo dice el conjunto, no el codigo")
    void elNumeroDeTramosEsDato() {
        LectorDeParametros dos =
                lector(
                        ParametrosSellados.de(EJERCICIO, 1)
                                .numero("UIT", null, ValorNormativo.de("5500.00"))
                                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.5"))
                                .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("10"))
                                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("1.5"))
                                .construir());

        List<Tramo> tramos = new CuadroPredialParametrizado(dos).vigenteEn(EJERCICIO).tramos();

        assertThat(tramos).hasSize(2);
        assertThat(tramos.get(1).tieneTope()).isFalse();
    }

    @Test
    @DisplayName("las claves de tramo se ordenan por su ordinal, no alfabeticamente")
    void elOrdenEsNumerico() {
        // Con diez tramos el orden alfabetico pone el «10» entre el «1» y el «2», y el cuadro
        // progresivo se aplicaria mal sin que ninguna cifra pareciera rara.
        ParametrosSellados.Constructor constructor =
                ParametrosSellados.de(EJERCICIO, 1)
                        .numero("UIT", null, ValorNormativo.de("100.00"));
        for (int i = 1; i <= 10; i++) {
            constructor.numero(
                    "TRAMO_PREDIAL", String.valueOf(i), ValorNormativo.de("0." + i % 10));
            if (i < 10) {
                constructor.numero(
                        "TRAMO_PREDIAL_LIMITE",
                        String.valueOf(i),
                        ValorNormativo.de(String.valueOf(i)));
            }
        }

        List<Tramo> tramos =
                new CuadroPredialParametrizado(lector(constructor.construir()))
                        .vigenteEn(EJERCICIO)
                        .tramos();

        assertThat(tramos).hasSize(10);
        assertThat(tramos.get(0).limiteSuperior()).isEqualTo(Dinero.de("100.00"));
        assertThat(tramos.get(8).limiteSuperior()).isEqualTo(Dinero.de("900.00"));
        assertThat(tramos.get(9).tieneTope()).isFalse();
    }

    @Test
    @DisplayName("solo el ultimo tramo puede ir sin tope: un hueco en el medio es un cuadro roto")
    void unHuecoEnElMedioSeRechaza() {
        LectorDeParametros conHueco =
                lector(
                        ParametrosSellados.de(EJERCICIO, 1)
                                .numero("UIT", null, ValorNormativo.de("5500.00"))
                                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("0.6"))
                                .numero("TRAMO_PREDIAL_LIMITE", "2", ValorNormativo.de("60"))
                                .numero("TRAMO_PREDIAL", "3", ValorNormativo.de("1.0"))
                                .construir());

        assertThatThrownBy(
                        () ->
                                new CuadroPredialParametrizado(conHueco)
                                        .vigenteEn(EJERCICIO)
                                        .tramos())
                .isInstanceOf(CuadroPredialParametrizado.ParametroDelPredialAusente.class)
                .hasMessageContaining("TRAMO_PREDIAL_LIMITE:1");
    }

    @Test
    @DisplayName("sin la UIT no hay cuadro, y el mensaje dice que falta la UIT")
    void sinUitNoHayCuadro() {
        LectorDeParametros sinUit =
                lector(
                        ParametrosSellados.de(EJERCICIO, 1)
                                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                                .construir());

        assertThatThrownBy(
                        () -> new CuadroPredialParametrizado(sinUit).vigenteEn(EJERCICIO).tramos())
                .isInstanceOf(ParametrosSellados.ParametroAusente.class)
                .hasMessageContaining("UIT");
    }

    @Test
    @DisplayName("sin ningun tramo se falla nombrando la llave, no se calcula con cero")
    void sinTramosSeNombraLaLlave() {
        LectorDeParametros vacio =
                lector(
                        ParametrosSellados.de(EJERCICIO, 1)
                                .numero("UIT", null, ValorNormativo.de("5500.00"))
                                .construir());

        assertThatThrownBy(
                        () -> new CuadroPredialParametrizado(vacio).vigenteEn(EJERCICIO).tramos())
                .isInstanceOf(CuadroPredialParametrizado.ParametroDelPredialAusente.class)
                .hasMessageContaining("TRAMO_PREDIAL:1");
    }

    @Test
    @DisplayName("el derecho de emision no tiene valor por omision: falta la ordenanza, falla")
    void elDerechoDeEmisionNoSeInventa() {
        LectorDeParametros sinDerecho =
                lector(
                        ParametrosSellados.de(EJERCICIO, 1)
                                .numero("UIT", null, ValorNormativo.de("5500.00"))
                                .construir());

        assertThatThrownBy(
                        () ->
                                new CuadroPredialParametrizado(sinDerecho)
                                        .vigenteEn(EJERCICIO)
                                        .derechoDeEmision())
                .isInstanceOf(ParametrosSellados.ParametroAusente.class)
                .hasMessageContaining("DERECHO_EMISION_PREDIAL");
    }

    @Test
    @DisplayName("los vencimientos salen del conjunto: cuatro fraccionados y uno al contado")
    void losVencimientosSalenDelConjunto() {
        CuadroPredialParametrizado.Vigente vigente = cuadroCompleto().vigenteEn(EJERCICIO);

        assertThat(vigente.vencimientos("TRIMESTRAL"))
                .containsExactly(
                        LocalDate.parse("2026-02-27"),
                        LocalDate.parse("2026-05-29"),
                        LocalDate.parse("2026-08-31"),
                        LocalDate.parse("2026-11-30"));
        assertThat(vigente.vencimientos(CuadroPredialParametrizado.MODALIDAD_CONTADO))
                .as("el articulo 15 a) es una sola cuota, no las cuatro del inciso b)")
                .containsExactly(LocalDate.parse("2026-02-27"));
    }

    @Test
    @DisplayName("sin cronograma publicado no hay cuotas: no hay cuatro fechas «de siempre»")
    void sinCronogramaNoHayCuotas() {
        // El articulo 15 da una regla —«ultimo dia habil» de cuatro meses— y no una fecha; que dia
        // es eso en un ejercicio concreto depende del calendario de feriados que publica la
        // municipalidad (predial-plazos-y-reajuste.md §3).
        LectorDeParametros sinCronograma =
                lector(
                        ParametrosSellados.de(EJERCICIO, 1)
                                .numero("UIT", null, ValorNormativo.de("5500.00"))
                                .construir());

        assertThatThrownBy(
                        () ->
                                new CuadroPredialParametrizado(sinCronograma)
                                        .vigenteEn(EJERCICIO)
                                        .vencimientos("TRIMESTRAL"))
                .isInstanceOf(CuadroPredialParametrizado.ParametroDelPredialAusente.class)
                .hasMessageContaining("PREDIAL_VENCIMIENTO:1");
    }

    @Test
    @DisplayName("el conjunto se nombra como lo lee una persona, y el identificador viaja aparte")
    void elConjuntoSeNombra() {
        CuadroPredialParametrizado.Vigente vigente = cuadroCompleto().vigenteEn(EJERCICIO);

        assertThat(vigente.nombreDelConjunto()).isEqualTo("2026 v1");
        assertThat(vigente.conjuntoId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("recalcular pide el conjunto por su identificador, no «el vigente»")
    void recalcularPideElConjuntoQueUso() {
        // ARQ-09 §3: si entre la emision y el recalculo se sello otra version, resolver por
        // ejercicio devuelve otros parametros y la cifra cambia sin que nada falle.
        CuadroPredialParametrizado.Vigente delConjunto =
                cuadroCompleto().delConjunto(EJERCICIO, 77L);

        assertThat(delConjunto.conjuntoId()).isEqualTo(77L);
        assertThat(delConjunto.uit()).isEqualTo(Dinero.de("5500.00"));
    }

    private static CuadroPredialParametrizado cuadroCompleto() {
        return new CuadroPredialParametrizado(
                lector(
                        ParametrosSellados.de(EJERCICIO, 1)
                                .numero("UIT", null, ValorNormativo.de("5500.00"))
                                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                                .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15"))
                                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("0.6"))
                                .numero("TRAMO_PREDIAL_LIMITE", "2", ValorNormativo.de("60"))
                                .numero("TRAMO_PREDIAL", "3", ValorNormativo.de("1.0"))
                                .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6"))
                                .numero("DERECHO_EMISION_PREDIAL", null, ValorNormativo.de("4.50"))
                                .texto("PREDIAL_VENCIMIENTO", "1", "2026-02-27")
                                .texto("PREDIAL_VENCIMIENTO", "2", "2026-05-29")
                                .texto("PREDIAL_VENCIMIENTO", "3", "2026-08-31")
                                .texto("PREDIAL_VENCIMIENTO", "4", "2026-11-30")
                                .texto("PREDIAL_VENCIMIENTO", "CONTADO", "2026-02-27")
                                .construir()));
    }

    /** Un lector que devuelve siempre el mismo conjunto, sellado con el identificador 77. */
    private static LectorDeParametros lector(ParametrosSellados sellados) {
        return new LectorDeParametros() {
            @Override
            public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
                return sellados;
            }

            @Override
            public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
                return sellados;
            }

            @Override
            public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
                return IdentificadorDeConjunto.de(77L);
            }
        };
    }
}
