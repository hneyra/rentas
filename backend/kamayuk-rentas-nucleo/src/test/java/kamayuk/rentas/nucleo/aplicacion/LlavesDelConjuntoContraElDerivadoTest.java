package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kamayuk.rentas.nucleo.dominio.arbitrios.Servicio;
import kamayuk.rentas.nucleo.dominio.espectaculos.ClaseDeEspectaculo;
import kamayuk.rentas.nucleo.parametros.DerivadoPublicado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * <b>Cada</b> llave que el nucleo pide al conjunto sellado, contra el derivado que {@code
 * normativa} despliega (#376; el patron de {@link CuadroDelDerivadoTest}, #395, y la leccion de
 * #192).
 *
 * <h2>Que defecto cierra</h2>
 *
 * <p>Uno que no se ve: un valor publicado bajo una llave que nadie lee. El derivado lo informa como
 * publicado, el conjunto se sella con el dentro, {@code verificar-publicacion.mjs} pasa en verde y
 * la operacion contesta 422 «falta publicar» sobre una cifra publicada. Asi estuvieron la alcabala
 * ({@code ALICUOTA_ALCABALA} contra {@code ALCABALA_ALICUOTA}) y los espectaculos ({@code
 * ALICUOTA_ESPECTACULO} contra {@code ESPECTACULO_ALICUOTA}) desde el monolito, con las pruebas en
 * verde porque cada una sembraba a mano la llave del codigo. La guarda que existia comparaba tres
 * llaves escritas a mano, y ninguna era de estos dos tributos.
 *
 * <h2>Como lo cierra</h2>
 *
 * <p>No hay lista que mantener: las llaves se leen <b>por reflexion</b> de {@link
 * LlavesDelConjunto} —la unica fuente del modulo, y el escaner {@code
 * ExigirNumeroSoloConLlavesDeclaradasTest} impide pedir una que no este alli—, y las cifras se leen
 * del archivo que se despliega. Una llave nueva entra sola en esta prueba. La unica lista escrita
 * aqui es la de las que el derivado <b>todavia no publica</b>, cada una con su motivo: y esa lista
 * se pone roja el dia que se publique, para que alguien compruebe que el nombre casa antes de
 * borrarla de aqui.
 */
@DisplayName("#376 — Cada llave del nucleo, contra el derivado que normativa despliega")
class LlavesDelConjuntoContraElDerivadoTest {

    private static final int EJERCICIO = 2026;

    /**
     * Las que el derivado no publica hoy, y por que. No son un olvido de esta prueba: son el 422
     * que la operacion contesta hoy, y es el correcto.
     */
    private static final Map<String, String> SIN_PUBLICAR = sinPublicar();

    /**
     * Las que se piden con la clave {@code null}: el derivado tiene que publicarlas con la clave
     * <b>vacia</b>. Pedir sin clave algo que se publica con clave es el mismo 422 que pedir otro
     * nombre.
     */
    private static final Set<String> SIN_CLAVE =
            Set.of(
                    LlavesDelConjunto.UIT,
                    LlavesDelConjunto.PREDIAL_MINIMO,
                    LlavesDelConjunto.ALCABALA_ALICUOTA,
                    LlavesDelConjunto.ALCABALA_TRAMO_INAFECTO_UIT);

    private static Map<String, String> sinPublicar() {
        Map<String, String> motivos = new LinkedHashMap<>();
        motivos.put(
                LlavesDelConjunto.DERECHO_EMISION_PREDIAL,
                "lo fija la ordenanza de cada municipalidad (D-02b), y el corpus solo transcribe"
                        + " norma nacional");
        motivos.put(
                LlavesDelConjunto.ALICUOTA_VEHICULAR,
                "el vehicular no esta transcrito todavia. OJO: normativa planea publicarlo como"
                        + " VEHICULAR_ALICUOTA (vehicular-valores-referenciales-2026.md), no como"
                        + " ALICUOTA_VEHICULAR; el dia que se publique, esta prueba se pone roja y"
                        + " hay que decidir el nombre antes de borrar esta linea");
        motivos.put(
                LlavesDelConjunto.VEHICULAR_MINIMO,
                "el vehicular no esta transcrito todavia. OJO: normativa planea publicarlo como"
                        + " VEHICULAR_MINIMO_UIT, no como VEHICULAR_MINIMO; mismo aviso que la"
                        + " alicuota");
        for (Servicio servicio : Servicio.values()) {
            motivos.put(
                    LlavesDelConjunto.tasaDeArbitrio(servicio),
                    "las tasas de arbitrios son de ordenanza local (D-02b)");
        }
        return Map.copyOf(motivos);
    }

    /** Cada constante de la clase, por reflexion, y cada tasa de arbitrio por servicio. */
    static Stream<Arguments> llavesDelNucleo() {
        Stream<Arguments> constantes =
                constantesDeLaClase().entrySet().stream()
                        .map(llave -> Arguments.of(llave.getKey(), llave.getValue()));
        Stream<Arguments> tasas =
                Arrays.stream(Servicio.values())
                        .map(
                                servicio ->
                                        Arguments.of(
                                                "tasaDeArbitrio(" + servicio + ")",
                                                LlavesDelConjunto.tasaDeArbitrio(servicio)));
        return Stream.concat(constantes, tasas);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("llavesDelNucleo")
    @DisplayName("la llave que se pide es la que el derivado publica, o declara por que no esta")
    void cadaLlaveCasaConElDerivado(String nombre, String tipo) {
        Map<String, String> publicados = DerivadoPublicado.numerosVigentesEn(EJERCICIO);
        SortedSet<String> tiposPublicados = tiposDe(publicados);

        if (SIN_PUBLICAR.containsKey(tipo)) {
            assertThat(tiposPublicados)
                    .as(
                            "%s = %s estaba declarada sin publicar (%s), y el derivado ya la"
                                    + " publica: comprueba que el nombre casa y quitala de"
                                    + " SIN_PUBLICAR",
                            nombre, tipo, SIN_PUBLICAR.get(tipo))
                    .doesNotContain(tipo);
            return;
        }
        assertThat(tiposPublicados)
                .as(
                        "LlavesDelConjunto.%s pide «%s», y el derivado de %s no publica ningun"
                                + " valor con ese nombre: con el conjunto real la operacion"
                                + " contesta 422 «falta publicar» sobre una cifra que puede estar"
                                + " publicada con otro. El derivado publica: %s",
                        nombre, tipo, EJERCICIO, tiposPublicados)
                .contains(tipo);
        if (SIN_CLAVE.contains(tipo)) {
            assertThat(publicados)
                    .as("%s se pide sin clave, y el derivado tiene que publicarla sin clave", tipo)
                    .containsKey(tipo + "|");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ClaseDeEspectaculo.class)
    @DisplayName("cada clase del art. 57 tiene su alicuota publicada con esa misma clave")
    void cadaClaseDelArticulo57SePublica(ClaseDeEspectaculo clase) {
        assertThat(DerivadoPublicado.numerosVigentesEn(EJERCICIO))
                .as(
                        "%s:%s es la llave que el registro del espectaculo pide para %s",
                        LlavesDelConjunto.ESPECTACULO_ALICUOTA, clase.clave(), clase)
                .containsKey(LlavesDelConjunto.ESPECTACULO_ALICUOTA + "|" + clase.clave());
    }

    @Test
    @DisplayName("y el derivado no publica ninguna clase del art. 57 que aqui falte")
    void elDerivadoNoPublicaUnaClaseQueAquiFalte() {
        String prefijo = LlavesDelConjunto.ESPECTACULO_ALICUOTA + "|";
        Set<String> publicadas =
                DerivadoPublicado.numerosVigentesEn(EJERCICIO).keySet().stream()
                        .filter(llave -> llave.startsWith(prefijo))
                        .map(llave -> llave.substring(prefijo.length()))
                        .collect(Collectors.toCollection(TreeSet::new));

        assertThat(publicadas)
                .as(
                        "una clase publicada que el enumerado no tiene no se puede declarar, y su"
                                + " alicuota no se aplica nunca")
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(ClaseDeEspectaculo.values())
                                .map(ClaseDeEspectaculo::clave)
                                .toList());
    }

    @Test
    @DisplayName("la reflexion ve las llaves, y ninguna excepcion de aqui sobra")
    void laReflexionVeLasLlaves() {
        Map<String, String> constantes = constantesDeLaClase();
        List<String> todas =
                llavesDelNucleo().map(argumentos -> (String) argumentos.get()[1]).toList();

        assertThat(constantes)
                .as(
                        "una prueba parametrizada sobre cero llaves sale verde sin mirar nada: hoy"
                                + " son diez constantes")
                .hasSizeGreaterThanOrEqualTo(10);
        assertThat(todas)
                .as("lo declarado sin publicar es de esta clase: si no, no exime a nadie")
                .containsAll(SIN_PUBLICAR.keySet())
                .containsAll(SIN_CLAVE);
    }

    private static Map<String, String> constantesDeLaClase() {
        Map<String, String> constantes = new LinkedHashMap<>();
        for (Field campo : LlavesDelConjunto.class.getDeclaredFields()) {
            int modificadores = campo.getModifiers();
            if (Modifier.isPublic(modificadores)
                    && Modifier.isStatic(modificadores)
                    && campo.getType() == String.class) {
                try {
                    constantes.put(campo.getName(), (String) campo.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return constantes;
    }

    private static SortedSet<String> tiposDe(Map<String, String> publicados) {
        return publicados.keySet().stream()
                .map(llave -> llave.substring(0, llave.indexOf('|')))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
