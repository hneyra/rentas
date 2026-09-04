package kamayuk.rentas.parametros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametroQueFalta;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #691 — El unico sitio donde «falta publicar» se convierte en un 422.
 *
 * <p>Lo que se mide aqui es lo que las pruebas de capa web no pueden medir sin levantar un
 * controlador: que el discriminador salga con el ejercicio y la llave <b>que la excepcion
 * publica</b> y no con una compuesta a mano, y que la sobrecarga del dominio puro componga {@code
 * REDONDEO:‹punto›} con las dos mitades que existen —el punto lo pone la excepcion, el ejercicio
 * quien la caza— en vez de nombrar el {@code TIPO} solo, que significa otra cosa.
 */
@DisplayName("#691 — FaltaPublicar: el 422 que lleva su discriminador")
class FaltaPublicarTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    @Test
    @DisplayName("una excepcion con llave la publica en el miembro")
    void conLlave() {
        ProblemaDeNegocio problema = FaltaPublicar.problema(faltaLaFila(PuntoDeRedondeo.CUOTA));

        assertThat(problema.codigo()).isEqualTo(CodigoDeError.VALIDACION);
        assertThat(problema.parametroQueFalta())
                .contains(ParametroQueFalta.llave(2026, "REDONDEO:CUOTA"));
        assertThat(problema.getMessage())
                .as("el mensaje sigue siendo el de la excepcion, ya redactado en el dominio")
                .contains("REDONDEO:CUOTA");
    }

    @Test
    @DisplayName("sin conjunto sellado no se inventa ninguna llave para rellenar el hueco")
    void sinLlave() {
        ProblemaDeNegocio problema =
                FaltaPublicar.problema(new LectorDeParametros.EjercicioSinSellar(EJERCICIO));

        assertThat(problema.parametroQueFalta())
                .contains(ParametroQueFalta.conjuntoDelEjercicio(2026));
    }

    @Test
    @DisplayName("la del dominio puro compone su llave con el punto y el ejercicio de fuera")
    void laDelDominioPuro() {
        PoliticasDeRedondeo.PuntoSinPolitica sinPolitica = elPuntoQueNadieObservo();

        ProblemaDeNegocio problema = FaltaPublicar.problema(EJERCICIO, sinPolitica);

        assertThat(problema.codigo()).isEqualTo(CodigoDeError.VALIDACION);
        assertThat(problema.parametroQueFalta())
                .as(
                        "aqui SI se sabe cual falta —lo pidio el calculo—, asi que la llave es la"
                                + " fila y no el TIPO solo, que significa «falta el bloque entero»")
                .contains(ParametroQueFalta.llave(2026, "REDONDEO:CUOTA"));
    }

    @Test
    @DisplayName("el punto sale de la excepcion, no de leerle el mensaje")
    void elPuntoLoPublicaLaExcepcion() {
        assertThat(elPuntoQueNadieObservo().punto())
                .as(
                        "leerlo del texto seria reaccionar al mensaje, que es lo que el"
                                + " discriminador existe para no tener que hacer")
                .isEqualTo(PuntoDeRedondeo.CUOTA);
    }

    // ------------------------------------------------------------------

    private static PoliticasDeRedondeoSelladas.PuntoSinObservar faltaLaFila(PuntoDeRedondeo punto) {
        return catchOf(
                PoliticasDeRedondeoSelladas.PuntoSinObservar.class,
                () ->
                        PoliticasDeRedondeoSelladas.en(
                                ParametrosSellados.de(EJERCICIO, 1)
                                        .numero(
                                                PoliticasDeRedondeoSelladas.TIPO,
                                                PuntoDeRedondeo.IMPUESTO_POR_TRAMO.name(),
                                                kamayuk.rentas.dominio.ValorNormativo.de("2"))
                                        .texto(
                                                PoliticasDeRedondeoSelladas.TIPO,
                                                PuntoDeRedondeo.IMPUESTO_POR_TRAMO.name(),
                                                "HALF_UP")
                                        .construir(),
                                punto));
    }

    private static PoliticasDeRedondeo.PuntoSinPolitica elPuntoQueNadieObservo() {
        return catchOf(
                PoliticasDeRedondeo.PuntoSinPolitica.class,
                () ->
                        PoliticasDeRedondeo.construir()
                                .en(
                                        PuntoDeRedondeo.IMPUESTO_POR_TRAMO,
                                        new kamayuk.rentas.dominio.PoliticaDeRedondeo(
                                                2, java.math.RoundingMode.HALF_UP))
                                .construir()
                                .en(PuntoDeRedondeo.CUOTA));
    }

    private static <E extends RuntimeException> E catchOf(Class<E> tipo, Runnable accion) {
        return tipo.cast(assertThatThrownBy(accion::run).isInstanceOf(tipo).actual());
    }
}
