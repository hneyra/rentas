package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kamayuk.rentas.nucleo.dominio.arbitrios.Servicio;
import kamayuk.rentas.nucleo.dominio.espectaculos.ClaseDeEspectaculo;
import kamayuk.rentas.nucleo.dominio.predial.RT001ValorDeTerreno;
import kamayuk.rentas.nucleo.parametros.DerivadoPublicado;
import kamayuk.rentas.nucleo.parametros.ElVehicularQuePlaneaNormativa;
import org.jspecify.annotations.Nullable;
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
 * <h2>Como lo cierra: en las dos direcciones</h2>
 *
 * <ul>
 *   <li><b>De la llave al derivado.</b> Las llaves se leen <b>por reflexion</b> de {@link
 *       LlavesDelConjunto} —la unica fuente del modulo, y el escaner {@code
 *       ExigirNumeroSoloConLlavesDeclaradasTest} impide pedir una que no este alli—, y las cifras
 *       del archivo que se despliega. Una llave nueva entra sola. La que el derivado todavia no
 *       publica se declara en {@link #SIN_PUBLICAR} con su motivo.
 *   <li><b>Del derivado a la llave.</b> Todo tipo que el derivado publica lo pide una llave del
 *       nucleo o esta declarado en {@link #PUBLICADOS_QUE_EL_NUCLEO_NO_PIDE}, con quien lo lee.
 * </ul>
 *
 * <p>La primera direccion sola <b>no basta</b>, y la revision de #376 lo midio: una llave de {@link
 * #SIN_PUBLICAR} solo comprobaba que el derivado no publicara <b>su</b> nombre. El vehicular pedia
 * {@code ALICUOTA_VEHICULAR} y {@code VEHICULAR_MINIMO}, y {@code normativa} planea publicar {@code
 * VEHICULAR_ALICUOTA} y {@code VEHICULAR_MINIMO_UIT} ({@code
 * vehicular-valores-referenciales-2026.md} §2): el dia que lo hiciera, la prueba seguia verde y el
 * vehicular daba el mismo 422 que la alcabala. Con la segunda direccion ese dia salia rojo.
 *
 * <p><b>Desde #499 el vehicular pide ya los nombres que planea {@code normativa}</b>, y {@link
 * #siNormativaPublicaraElVehicular()} lo fija sobre una muestra, sin tocar {@code normativa}: si
 * publicara las dos filas, ninguna quedaria sin quien la pida, y lo unico rojo seria la declaracion
 * de {@link #SIN_PUBLICAR}, que ese dia sobra. Los nombres de la muestra no se escriben aqui: salen
 * de {@link ElVehicularQuePlaneaNormativa}, que los compara con el archivo del corpus.
 */
@DisplayName("#376 — Cada llave del nucleo, contra el derivado que normativa despliega")
class LlavesDelConjuntoContraElDerivadoTest {

    private static final int EJERCICIO = 2026;

    /**
     * Las que el derivado no publica hoy, y por que. No son un olvido de esta prueba: son el 422
     * que la operacion contesta hoy, y es el correcto.
     */
    private static final Map<String, SinPublicar> SIN_PUBLICAR = sinPublicar();

    /**
     * Los tipos que el derivado publica y ninguna llave del nucleo pide por numero, con quien los
     * lee. Es la lista que hace roja la llegada de un nombre que nadie espera: un tipo nuevo del
     * derivado que no esta aqui ni en {@link LlavesDelConjunto} es, casi siempre, un valor que
     * alguna llave pide con otro nombre.
     */
    private static final Map<String, String> PUBLICADOS_QUE_EL_NUCLEO_NO_PIDE =
            Map.of(
                    "PLAZO",
                    "se lee como TEXTO (ParametrosSellados.texto), no con exigirNumero: lo piden"
                            + " RegistrarDeclaracionJurada y los plazos de valores, sanciones y"
                            + " coactiva, y sus claves las comparan PlazosDelDerivadoTest y"
                            + " PlazoDeLaRec1DelDerivadoTest (#192)",
                    "DEDUCCION_PENSIONISTA",
                    "RT-012 no esta implementada: DeterminarPredial no emite a quien tiene la"
                            + " deduccion. El dia que se aplique, su llave entra en"
                            + " LlavesDelConjunto y sale de aqui",
                    "DEDUCCION_ADULTO_MAYOR",
                    "RT-012 no esta implementada, igual que la del pensionista",
                    "FACTOR_OFICIALIZACION",
                    "es de la valorizacion de las obras complementarias, que hace catastro"
                            + " (ADR-0024)",
                    "PORCENTAJE_DE_ACTUALIZACION",
                    "lo aplica catastro al valorizar (ValorizacionDelPredio, ADR-0024); aqui llega"
                            + " el autovaluo ya actualizado");

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

    /**
     * Una llave que el derivado todavia no publica.
     *
     * @param motivo por que no esta
     * @param nombreQuePlaneaNormativa el tipo con que {@code normativa} dice que la va a publicar,
     *     si lo dice y no coincide con el de la llave; {@code null} si no lo dice
     */
    private record SinPublicar(String motivo, @Nullable String nombreQuePlaneaNormativa) {

        /** El nombre con que ya se publica, si se publica con el de la llave o con el planeado. */
        Optional<String> publicadaComo(String tipo, Set<String> tiposPublicados) {
            if (tiposPublicados.contains(tipo)) {
                return Optional.of(tipo);
            }
            return Optional.ofNullable(nombreQuePlaneaNormativa).filter(tiposPublicados::contains);
        }
    }

    private static Map<String, SinPublicar> sinPublicar() {
        Map<String, SinPublicar> motivos = new LinkedHashMap<>();
        motivos.put(
                LlavesDelConjunto.DERECHO_EMISION_PREDIAL,
                new SinPublicar(
                        "lo fija la ordenanza de cada municipalidad (D-02b), y el corpus solo"
                                + " transcribe norma nacional",
                        null));
        motivos.put(
                LlavesDelConjunto.VEHICULAR_ALICUOTA,
                new SinPublicar(
                        "el corpus la transcribe y la planea con este nombre"
                                + " (vehicular-valores-referenciales-2026.md §2, #499), pero el"
                                + " derivado todavia no trae su fila",
                        null));
        motivos.put(
                LlavesDelConjunto.VEHICULAR_MINIMO_UIT,
                new SinPublicar(
                        "el corpus lo transcribe y lo planea con este nombre"
                                + " (vehicular-valores-referenciales-2026.md §2, #499), pero el"
                                + " derivado todavia no trae su fila",
                        null));
        motivos.put(
                RT001ValorDeTerreno.ARANCEL,
                new SinPublicar(
                        "la valuacion del terreno es de catastro (ADR-0024), normativa publica el"
                                + " arancel urbano en la tabla `arancel` y no como parametro"
                                + " (aranceles-2026.md §2), y RT-001 no la registra ningun motor"
                                + " de produccion: solo la ejercen sus pruebas",
                        null));
        for (Servicio servicio : Servicio.values()) {
            motivos.put(
                    LlavesDelConjunto.tasaDeArbitrio(servicio),
                    new SinPublicar("las tasas de arbitrios son de ordenanza local (D-02b)", null));
        }
        return Map.copyOf(motivos);
    }

    /**
     * Cada constante de la clase, por reflexion; cada tasa de arbitrio por servicio; y la llave que
     * pide la unica regla del motor que lee un parametro por su puerto ({@code InsumosDeLaRegla},
     * exenta del escaner porque la llave la escribe la regla y no el puerto).
     */
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
        Stream<Arguments> delMotor =
                Stream.of(Arguments.of("RT001ValorDeTerreno.ARANCEL", RT001ValorDeTerreno.ARANCEL));
        return Stream.of(constantes, tasas, delMotor).flatMap(s -> s);
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("llavesDelNucleo")
    @DisplayName("la llave que se pide es la que el derivado publica, o declara por que no esta")
    void cadaLlaveCasaConElDerivado(String nombre, String tipo) {
        Map<String, String> publicados = DerivadoPublicado.numerosVigentesEn(EJERCICIO);
        SortedSet<String> tiposPublicados = tiposDe(publicados);

        if (SIN_PUBLICAR.containsKey(tipo)) {
            SinPublicar declarada = SIN_PUBLICAR.get(tipo);
            assertThat(declarada.publicadaComo(tipo, tiposPublicados))
                    .as(
                            "%s = %s estaba declarada sin publicar (%s), y el derivado ya publica"
                                    + " ese valor: si lo publica con otro nombre, la operacion"
                                    + " contesta 422 «falta publicar» sobre una cifra publicada."
                                    + " Pide la llave con el nombre que publica normativa y quitala"
                                    + " de SIN_PUBLICAR",
                            nombre, tipo, declarada.motivo())
                    .isEmpty();
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

    @Test
    @DisplayName("y al reves: todo tipo que el derivado publica lo pide una llave o esta declarado")
    void todoTipoPublicadoTieneQuienLoPida() {
        SortedSet<String> tiposPublicados = tiposDe(DerivadoPublicado.numerosVigentesEn(EJERCICIO));

        assertThat(tiposSinQuienLosPida(tiposPublicados))
                .as(
                        "el derivado de %s publica estos tipos y ninguna llave del nucleo los pide"
                                + " ni estan en PUBLICADOS_QUE_EL_NUCLEO_NO_PIDE. Casi siempre es"
                                + " un valor que una llave pide con OTRO nombre —de SIN_PUBLICAR:"
                                + " %s—, y esa operacion contesta 422 «falta publicar»: renombra"
                                + " la llave. Si de verdad no lo lee el nucleo, declaralo con"
                                + " quien lo lee",
                        EJERCICIO, SIN_PUBLICAR.keySet())
                .isEmpty();
        assertThat(
                        PUBLICADOS_QUE_EL_NUCLEO_NO_PIDE.keySet().stream()
                                .filter(
                                        tipo ->
                                                !tiposPublicados.contains(tipo)
                                                        || tiposPedidos().contains(tipo))
                                .toList())
                .as(
                        "declarados como publicados que el nucleo no pide, y o ya no se publican o"
                                + " ya los pide una llave: la excepcion no exime a nadie")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "#499 — si normativa publicara el vehicular con el nombre que planea, el nucleo ya lo"
                    + " pide: solo sobraria su declaracion sin publicar")
    void siNormativaPublicaraElVehicular() {
        Set<String> planeados = ElVehicularQuePlaneaNormativa.FILAS.keySet();
        Set<String> conElVehicular =
                new TreeSet<>(tiposDe(DerivadoPublicado.numerosVigentesEn(EJERCICIO)));
        conElVehicular.addAll(planeados);

        assertThat(tiposSinQuienLosPida(conElVehicular))
                .as(
                        "del derivado a la llave: los nombres que normativa planea para el"
                                + " vehicular los pide una llave del nucleo. Hasta #499 salian los"
                                + " dos aqui, y el vehicular contestaba 422 «falta publicar» sobre"
                                + " cifras publicadas")
                .isEmpty();
        assertThat(
                        SIN_PUBLICAR.entrySet().stream()
                                .filter(
                                        declarada ->
                                                declarada
                                                        .getValue()
                                                        .publicadaComo(
                                                                declarada.getKey(), conElVehicular)
                                                        .isPresent())
                                .map(Map.Entry::getKey)
                                .toList())
                .as(
                        "de la llave al derivado: lo que ese dia se pone rojo es la declaracion sin"
                                + " publicar de las dos, que ya no exime a nadie")
                .containsExactlyInAnyOrderElementsOf(planeados);
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

        assertThat(constantes)
                .as(
                        "una prueba parametrizada sobre cero llaves sale verde sin mirar nada: hoy"
                                + " son diez constantes")
                .hasSizeGreaterThanOrEqualTo(10);
        assertThat(tiposPedidos())
                .as("lo declarado sin publicar es de esta clase: si no, no exime a nadie")
                .containsAll(SIN_PUBLICAR.keySet())
                .containsAll(SIN_CLAVE);
    }

    /** Los tipos del derivado que ninguna llave pide y que no estan declarados. */
    private static SortedSet<String> tiposSinQuienLosPida(Set<String> tiposPublicados) {
        Set<String> pedidos = tiposPedidos();
        return tiposPublicados.stream()
                .filter(tipo -> !pedidos.contains(tipo))
                .filter(tipo -> !PUBLICADOS_QUE_EL_NUCLEO_NO_PIDE.containsKey(tipo))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> tiposPedidos() {
        List<String> todas =
                llavesDelNucleo().map(argumentos -> (String) argumentos.get()[1]).toList();
        return Set.copyOf(todas);
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
