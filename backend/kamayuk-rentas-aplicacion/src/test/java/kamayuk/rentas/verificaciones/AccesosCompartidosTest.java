package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.seguridad.dominio.CatalogoDeOpciones;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * El censo de las operaciones que <b>dos opciones del catalogo</b> autorizan (#548).
 *
 * <p>{@code RequiereAcceso.oTambien} existe porque hay lecturas que dos pantallas necesitan por
 * igual: la grilla de deuda es la operacion de {@code consulta_deuda} y es tambien lo que la caja
 * tributaria tiene que ver para saber que cobra. Sin ese mecanismo, la unica salida es otorgar la
 * opcion ajena entera <b>en cada implantacion</b>, y lo que se olvida no avisa: la pantalla de
 * cobro se abre y su grilla contesta 403.
 *
 * <p><b>Y es tambien el mecanismo con el que se puede abrir una puerta sin querer.</b> Una linea de
 * mas en una anotacion amplia el publico de una lectura sin tocar el catalogo de permisos, sin
 * migracion y sin que ninguna pantalla cambie. Por eso esta censado: la lista de abajo es la
 * enumeracion completa de lo que el sistema comparte, cada entrada con su motivo escrito, y la
 * prueba falla en <b>las dos direcciones</b> —un endpoint que declare alternativas sin estar aqui,
 * y una entrada de aqui cuyo endpoint ya no las declare—.
 *
 * <p>Lo que la prueba <b>no</b> mira es el privilegio: no hace falta, porque el guardia exige el
 * mismo sobre la alternativa que sobre la opcion propia y {@code GuardiaDeAccesoTest} lo mide. Lo
 * que si comprueba es que la opcion alternativa <b>exista en el catalogo de las 134</b>: un acceso
 * inventado no lo tiene nadie, asi que la alternativa no autorizaria a nadie y el endpoint
 * pareceria compartido sin estarlo —la trampa que #366 documento al nombrar
 * «consulta_contribuyentes», una opcion que no existe—.
 */
@DisplayName("Los accesos que dos opciones comparten (#548)")
class AccesosCompartidosTest {

    /**
     * Las operaciones que una segunda opcion del catalogo tambien autoriza, con su motivo.
     *
     * <p><b>Se acorta, no se alarga.</b> Anadir una entrada es ampliar el publico de una lectura, y
     * cuesta esta linea a proposito: el diff dice que dos opciones cubren lo mismo y por que.
     */
    private static final Map<String, Set<String>> LO_QUE_DOS_OPCIONES_CUBREN =
            Map.of(
                    // #618 — el catalogo de ventanillas. Es la lectura de `caja_tributaria` y las
                    // otras cuatro opciones son, exactamente, las del catalogo cuya operacion
                    // EXIGE el codigo de una caja y que sin esta lectura no lo pueden ofrecer:
                    // `caja_tasas` y `cierre_caja` lo llevan en el cuerpo, `avance_recaudacion` y
                    // `duplicado_recibo` como parametro de consulta. La alternativa a compartirlo
                    // es otorgar `caja_tributaria` entera —o sea la ventanilla de cobro— a quien
                    // solo tiene que cerrar su turno o buscar un recibo, en cada implantacion y a
                    // mano; y lo que se olvida no avisa: el desplegable contesta 403 y la pantalla
                    // parece rota por otro motivo. Lo que se comparte no es dinero ni datos de
                    // nadie: son cuatro filas con el codigo y el rotulo de las ventanillas.
                    "GET /tesoreria/cajas",
                    Set.of("caja_tasas", "cierre_caja", "avance_recaudacion", "duplicado_recibo"),
                    // #599 — la grilla de actas de inspeccion. `acta_fiscalizacion` es UNA tabla y
                    // el acta predial y la vehicular son el mismo tipo de dominio y el mismo
                    // recurso, asi que la lectura es una: dos listados serian dos copias de la
                    // misma consulta. Las dos opciones que ESCRIBEN actas la necesitan por igual, y
                    // exigir solo `fisc_predial` dejaria a un perfil de fiscalizacion vehicular
                    // registrando actas que no puede volver a ver — el mismo sintoma que #548
                    // encontro en la caja, una pantalla que escribe y no ve lo que escribio.
                    "GET /fiscalizacion/actas",
                    Set.of("fisc_vehicular"),
                    // #548 — la grilla de deuda de la caja tributaria. `POST
                    // /tesoreria/caja/cobranza` exige `obligaciones[]` con tributo, ejercicio y
                    // unidad una a una, y esta es la UNICA lectura que las publica asi: sin ella,
                    // un perfil de cajero puro cobra y no ve que cobrar. No se resuelve otorgando
                    // `consulta_deuda` en la implantacion porque no hay ningun grupo de cajero que
                    // otorgar —`ImplantarMunicipalidad` deja dos grupos, y ninguno es ese— y
                    // porque es estructural: quien puede cobrar tiene que poder ver la deuda.
                    "GET /consultas/deuda",
                    Set.of("caja_tributaria"));

    @Test
    @DisplayName("todo endpoint que comparte su acceso esta censado, y el censo no miente")
    void elCensoEnumeraExactamenteLoQueSeComparte() {
        Map<String, Set<String>> compartidos = alternativasPublicadas();

        assertThat(compartidos)
                .as(
                        "un endpoint que declare `oTambien` sin figurar en el censo amplia el"
                                + " publico de una lectura sin que el diff lo diga. Se anota en"
                                + " LO_QUE_DOS_OPCIONES_CUBREN con su motivo, o se retira la"
                                + " anotacion")
                .containsExactlyInAnyOrderEntriesOf(LO_QUE_DOS_OPCIONES_CUBREN);
    }

    @Test
    @DisplayName("y la opcion alternativa existe en el catalogo de las 134")
    void laOpcionAlternativaExisteEnElCatalogo() {
        Set<String> delCatalogo = new TreeSet<>();
        for (CatalogoDeOpciones.Opcion opcion : CatalogoDeOpciones.leer()) {
            delCatalogo.add(opcion.codigo());
        }

        Set<String> inventadas = new TreeSet<>();
        for (Set<String> alternativas : alternativasPublicadas().values()) {
            for (String alternativa : alternativas) {
                if (!delCatalogo.contains(alternativa)) {
                    inventadas.add(alternativa);
                }
            }
        }

        assertThat(delCatalogo).as("el catalogo se lee y trae las opciones").hasSize(134);
        assertThat(inventadas)
                .as(
                        "un acceso que no esta en el catalogo no lo tiene nadie: la alternativa no"
                                + " autorizaria a nadie y el endpoint pareceria compartido sin estarlo"
                                + " (#366)")
                .isEmpty();
    }

    @Test
    @DisplayName("una opcion no se declara alternativa de si misma: seria una linea sin efecto")
    void ningunaOpcionEsAlternativaDeSiMisma() {
        Map<String, Set<String>> redundantes = new TreeMap<>();
        for (Map.Entry<String, Method> publicada : EndpointsPublicados.porOperacion().entrySet()) {
            RequiereAcceso requisito = requisitoDe(publicada.getValue());
            if (requisito == null) {
                continue;
            }
            Set<String> repetidas = new TreeSet<>(Arrays.asList(requisito.oTambien()));
            repetidas.retainAll(Set.of(requisito.acceso()));
            if (!repetidas.isEmpty()) {
                redundantes.put(publicada.getKey(), repetidas);
            }
        }

        assertThat(redundantes)
                .as("`oTambien` repitiendo el acceso propio no comparte nada y disimula el censo")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    /** Cada operacion publicada que declara alternativas, con las que declara. */
    private static Map<String, Set<String>> alternativasPublicadas() {
        Map<String, Set<String>> compartidos = new TreeMap<>();
        for (Map.Entry<String, Method> publicada : EndpointsPublicados.porOperacion().entrySet()) {
            RequiereAcceso requisito = requisitoDe(publicada.getValue());
            if (requisito == null || requisito.oTambien().length == 0) {
                continue;
            }
            Set<String> alternativas = new LinkedHashSet<>(List.of(requisito.oTambien()));
            compartidos
                    .computeIfAbsent(publicada.getKey(), clave -> new TreeSet<>())
                    .addAll(alternativas);
        }
        return compartidos;
    }

    /** La anotacion del metodo, y si no la del controlador: el mismo orden que el guardia. */
    private static RequiereAcceso requisitoDe(Method metodo) {
        RequiereAcceso delMetodo =
                AnnotatedElementUtils.findMergedAnnotation(metodo, RequiereAcceso.class);
        return delMetodo != null
                ? delMetodo
                : AnnotatedElementUtils.findMergedAnnotation(
                        metodo.getDeclaringClass(), RequiereAcceso.class);
    }
}
