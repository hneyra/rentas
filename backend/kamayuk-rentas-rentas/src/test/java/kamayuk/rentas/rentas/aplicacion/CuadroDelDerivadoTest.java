package kamayuk.rentas.rentas.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.rentas.dominio.predial.Tramo;
import kamayuk.rentas.rentas.parametros.DerivadoPublicado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Que las llaves con las que el derivado publica el cuadro del predial son <b>exactamente</b> las
 * que {@link CuadroPredialParametrizado} lee (#395, misma leccion que #192).
 *
 * <h2>Que defecto cierra</h2>
 *
 * <p>Uno que no se ve. Un tramo publicado bajo una llave que nadie lee: el proceso de publicacion
 * lo informa como publicado, el conjunto se sella con el dentro, {@code verificar-publicacion.mjs}
 * pasa en verde —la cifra esta en la norma y las firmas son las del corpus— y la determinacion
 * sigue fallando con «no tiene el parametro», que es el sintoma de «no esta cargado» y no el de
 * «esta cargado con otro nombre». Basta con escribir {@code TRAMO_LIMITE_PREDIAL} en vez de {@code
 * TRAMO_PREDIAL_LIMITE}.
 *
 * <p>Aqui no se escribe ninguna cifra ni ninguna llave: las dos se <b>leen del archivo</b> que el
 * repositorio despliega, y lo que se comprueba es que el cuadro se pueda armar con ellas. La ida y
 * la vuelta contra PostgreSQL —publicar, componer, sellar y releer— la hace {@code
 * PublicarParametrosTest}; lo que falta aqui es el ultimo tramo, el del consumidor real.
 */
@DisplayName("El cuadro del predial, leido del derivado que se publica (#395)")
class CuadroDelDerivadoTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    @Test
    @DisplayName("el cuadro del articulo 13 se arma con las llaves que el derivado publica")
    void elCuadroSeArmaConLoPublicado() {
        Map<String, String> publicados = numerosDelDerivado();

        CuadroPredialParametrizado.Vigente vigente =
                new CuadroPredialParametrizado(DerivadoPublicado.conjuntoDelEjercicio(EJERCICIO))
                        .vigenteEn(EJERCICIO);

        List<Tramo> tramos = vigente.tramos();

        assertThat(tramos)
                .as(
                        "el derivado publica %s tramos; si el cuadro no los encuentra, estan"
                                + " cargados con otro nombre y la determinacion falla igual",
                        contar(publicados, CuadroPredialParametrizado.TIPO_TRAMO))
                .hasSize(contar(publicados, CuadroPredialParametrizado.TIPO_TRAMO));
        assertThat(tramos.get(tramos.size() - 1).tieneTope())
                .as("el ultimo tramo del articulo 13 es «mas de 60 UIT», sin tope")
                .isFalse();
        for (int i = 0; i < tramos.size() - 1; i++) {
            assertThat(tramos.get(i).tieneTope())
                    .as("el tramo %s tiene tope, y sale de TRAMO_PREDIAL_LIMITE", i + 1)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("la UIT y el minimo salen del derivado, y el minimo se convierte con esa UIT")
    void laUitYElMinimoSalenDelDerivado() {
        Map<String, String> publicados = numerosDelDerivado();

        CuadroPredialParametrizado.Vigente vigente =
                new CuadroPredialParametrizado(DerivadoPublicado.conjuntoDelEjercicio(EJERCICIO))
                        .vigenteEn(EJERCICIO);

        Dinero uit = vigente.uit();
        assertThat(uit)
                .as("la UIT del cuadro es la que el derivado publica, no una escrita aqui")
                .isEqualTo(Dinero.de(publicados.get(CuadroPredialParametrizado.TIPO_UIT + "|")));
        assertThat(vigente.minimoImponible())
                .isEqualTo(
                        uit.por(
                                new BigDecimal(
                                                publicados.get(
                                                        CuadroPredialParametrizado.TIPO_MINIMO
                                                                + "|"))
                                        .movePointLeft(2)));
    }

    @Test
    @DisplayName("el derivado no publica el derecho de emision ni el cronograma: son D-02b")
    void loQueElDerivadoNoPublica() {
        Map<String, String> publicados = numerosDelDerivado();

        // No es un olvido: el derecho de emision mecanizada y el dia concreto en que vence cada
        // cuota los fija la ordenanza de cada municipalidad, y el corpus solo transcribe norma
        // nacional. Que falten es la razon por la que la determinacion responde 422 nombrando la
        // llave en vez de emitir con una cifra inventada, y esta prueba lo deja escrito para que el
        // dia que se publiquen alguien venga aqui a borrarla.
        assertThat(publicados)
                .doesNotContainKey(CuadroPredialParametrizado.TIPO_DERECHO_EMISION + "|");
        assertThat(publicados.keySet())
                .noneMatch(
                        llave ->
                                llave.startsWith(
                                        CuadroPredialParametrizado.TIPO_VENCIMIENTO + "|"));
    }

    private static int contar(Map<String, String> publicados, String tipo) {
        return (int)
                publicados.keySet().stream().filter(llave -> llave.startsWith(tipo + "|")).count();
    }

    /**
     * Las filas numericas del derivado que rigen 2026, leidas como las lee el proceso que publica.
     *
     * <p>Se leen con {@link DerivadoPublicado}, que es el mismo lector que usa el corpus de casos
     * de NEG-05: con una copia en cada prueba, el dia que el formato del archivo cambie una de las
     * dos seguiria verde leyendo mal.
     */
    private static Map<String, String> numerosDelDerivado() {
        Map<String, String> publicados = DerivadoPublicado.numerosVigentesEn(EJERCICIO.valor());
        assertThat(publicados)
                .as("si el derivado se queda sin filas numericas, esta prueba no prueba nada")
                .isNotEmpty();
        return publicados;
    }
}
