package kamayuk.rentas.fiscalizacion.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kamayuk.rentas.fiscalizacion.aplicacion.InsumosNormativosDeLaLiquidacion.LlaveNormativa;
import kamayuk.rentas.parametros.CorpusDeNormativa;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Las llaves de la liquidacion de fiscalizacion, contra el derivado que {@code normativa} despliega
 * (#376, revision).
 *
 * <h2>Por que hace falta</h2>
 *
 * <p>{@link InsumosNormativosDeLaLiquidacion} pasa a {@code exigirNumero} las llaves de {@link
 * InsumosNormativosDeLaLiquidacion#LLAVES_QUE_ESPERAN_A_D02A} recorriendo esa lista, y por eso el
 * escaner {@code ExigirNumeroSoloConLlavesDeclaradasTest} la deja exenta. Pero exenta del escaner
 * no puede querer decir exenta de la comparacion: son justo las llaves de D-02a, las proximas en
 * publicarse, y el defecto de #376 —un valor publicado con un nombre que el consumidor no pide—
 * aparece el dia que se publican, no antes.
 *
 * <p>Cada llave de la lista, entonces: o el derivado la publica <b>con ese tipo y esa clave</b>, o
 * esta en {@link #SIN_PUBLICAR} con su motivo; y la declarada sin publicar se pone roja en cuanto
 * el derivado publique <b>cualquier</b> fila de su tipo, con cualquier clave. Mirar solo la clave
 * exacta no bastaria: {@code normativa} planea la multa del art. 176 como {@code MULTA_TRIBUTARIA}
 * con la clave {@code 176.1} mas la tabla ({@code multa-tributaria.md} §2), y la liquidacion la
 * pide con la clave {@code ART_176_NUM_1}; publicada asi, la clave exacta seguiria sin estar y la
 * prueba seguiria verde con la liquidacion en 422.
 */
@DisplayName("#376 — Las llaves de la liquidacion de fiscalizacion, contra el derivado")
class LlavesDeLaLiquidacionContraElDerivadoTest {

    private static final int EJERCICIO = 2026;

    /** Las que el derivado no publica hoy, por {@code tipo[:clave]}, y por que. */
    private static final Map<String, String> SIN_PUBLICAR = sinPublicar();

    private static Map<String, String> sinPublicar() {
        Map<String, String> motivos = new LinkedHashMap<>();
        motivos.put(
                "VALOR_UNITARIO",
                "normativa no lo publica como parametro sino como la tabla"
                        + " valor_unitario_edificacion, desde cuadros-2026.csv"
                        + " (valores-unitarios-2026.md §2; ADR-0017): con este nombre no va a"
                        + " estar nunca en el conjunto. Es de #198, bloqueado por D-02a");
        motivos.put(
                "DEPRECIACION",
                "normativa la publica como tabla (depreciacion.md §2), no como parametro; mismo"
                        + " caso que VALOR_UNITARIO");
        motivos.put(
                "MULTA_TRIBUTARIA:ART_176_NUM_1",
                "no transcrita todavia, y normativa planea la clave 176.1 mas la tabla (I, II o"
                        + " III), no ART_176_NUM_1 (multa-tributaria.md §2): el dia que se"
                        + " publique hay que decidir la clave");
        return Map.copyOf(motivos);
    }

    static Stream<LlaveNormativa> llavesDeLaLiquidacion() {
        return InsumosNormativosDeLaLiquidacion.LLAVES_QUE_ESPERAN_A_D02A.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("llavesDeLaLiquidacion")
    @DisplayName("la llave que se pide es la que el derivado publica, o declara por que no esta")
    void cadaLlaveCasaConElDerivado(LlaveNormativa llave) {
        Map<String, String> publicados = CorpusDeNormativa.numerosVigentesEn(EJERCICIO);

        if (SIN_PUBLICAR.containsKey(llave.toString())) {
            assertThat(filasDelTipo(publicados, llave.tipo()))
                    .as(
                            "%s estaba declarada sin publicar (%s), y el derivado ya publica filas"
                                    + " de %s: comprueba que la clave casa con la que la"
                                    + " liquidacion pide y quitala de SIN_PUBLICAR. Si no casa, la"
                                    + " liquidacion contesta 422 «falta publicar» sobre una cifra"
                                    + " publicada",
                            llave, SIN_PUBLICAR.get(llave.toString()), llave.tipo())
                    .isEmpty();
            return;
        }
        String clave = llave.clave() == null ? "" : llave.clave();
        assertThat(publicados)
                .as(
                        "la liquidacion pide %s, y el derivado de %s no la publica con ese tipo y"
                                + " esa clave: con el conjunto real contesta 422 «falta"
                                + " publicar». Del tipo %s el derivado publica: %s",
                        llave, EJERCICIO, llave.tipo(), filasDelTipo(publicados, llave.tipo()))
                .containsKey(llave.tipo() + "|" + clave);
    }

    @Test
    @DisplayName("hay llaves que mirar, y ninguna excepcion de aqui sobra")
    void ningunaExcepcionSobra() {
        Set<String> pedidas =
                llavesDeLaLiquidacion().map(LlaveNormativa::toString).collect(Collectors.toSet());

        assertThat(pedidas)
                .as("una prueba parametrizada sobre cero llaves sale verde sin mirar nada")
                .hasSizeGreaterThanOrEqualTo(4)
                .as("lo declarado sin publicar es de la lista: si no, no exime a nadie")
                .containsAll(SIN_PUBLICAR.keySet());
    }

    /** Las claves con que el derivado publica ese tipo; vacia si no publica ninguna fila de el. */
    private static SortedSet<String> filasDelTipo(Map<String, String> publicados, String tipo) {
        String prefijo = tipo + "|";
        return publicados.keySet().stream()
                .filter(llave -> llave.startsWith(prefijo))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
