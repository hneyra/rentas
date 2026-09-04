package kamayuk.rentas.fiscalizacion.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import kamayuk.rentas.fiscalizacion.aplicacion.GenerarMuestra;
import kamayuk.rentas.fiscalizacion.infraestructura.web.LiquidacionController;
import kamayuk.rentas.fiscalizacion.infraestructura.web.LiquidacionResource;
import kamayuk.rentas.fiscalizacion.infraestructura.web.MuestraController;
import kamayuk.rentas.fiscalizacion.infraestructura.web.MuestraResource;
import kamayuk.rentas.fiscalizacion.infraestructura.web.OmisoResource;
import kamayuk.rentas.fiscalizacion.infraestructura.web.OmisosController;
import kamayuk.rentas.fiscalizacion.infraestructura.web.ProgramaResource;
import kamayuk.rentas.fiscalizacion.infraestructura.web.ProgramasController;
import kamayuk.rentas.persistencia.OrdenSeguro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Por lo que se puede ordenar es lo que la fila publica, en los cuatro listados del módulo (#546,
 * AC 3).
 *
 * <h2>El defecto que esto cierra</h2>
 *
 * <p>{@code GET /fiscalizacion/omisos} publica {@code codRefCatastral} en cada fila y sólo aceptaba
 * {@code ?ordenarPor=codigoRefCatastral} —el {@code camelCase} de la columna—: pedir por el nombre
 * que la fila enseña daba {@code 422 ORDEN_NO_ADMITIDO}. Dos nombres para la misma columna en la
 * misma operación, y el único que funcionaba era el que ningún cliente puede ver.
 *
 * <p>La regla queda escrita de una vez para el módulo, y <b>computada del propio {@code record}</b>
 * y no de una lista escrita a mano: una lista a mano se copia del código que quiere comprobar y
 * envejece con él. Así, un campo renombrado en un recurso —o una columna añadida a una lista blanca
 * que la fila no publica— pone esto en rojo nombrando los dos.
 *
 * <h2>Qué queda fuera de la regla, y por qué</h2>
 *
 * <p>{@link OrdenSeguro#sobre} admite además <b>el nombre de columna crudo</b> —{@code
 * codigo_ref_catastral}— para un cliente que ya conozca la tabla. Ese no es un campo del recurso y
 * no lo va a ser nunca: se reconoce por el guion bajo y se exime. Lo que la regla mira es lo que
 * una pantalla puede pedir.
 *
 * <p>Y el <b>desempate</b> no entra: {@link OrdenSeguro#desempatandoPor} no lo publica como campo
 * —{@code camposAdmitidos()} no lo lleva—, así que es una columna que da orden total sin ofrecerse
 * (#543). Es donde acabaron {@code predio_id} y los dos {@code id} que estaban en las listas
 * blancas como si fueran campos.
 */
@DisplayName("#546 — Por lo que se ordena es lo que la fila publica")
class OrdenDeLosListadosTest {

    /**
     * Los cuatro listados del módulo: su lista blanca y el {@code record} que publica cada fila.
     *
     * <p>{@code /fiscalizacion/predial/historico} comparte la lista blanca de las liquidaciones y
     * publica {@code LiquidacionResource.VersionResource}, que lleva dentro un {@code
     * LiquidacionResource}: los campos por los que se ordena son los de la liquidación, y por eso
     * el mapa nombra al de dentro.
     */
    private static final Map<String, Listado> LISTADOS =
            new LinkedHashMap<>(
                    Map.of(
                            "GET /fiscalizacion/omisos",
                            new Listado(DeteccionRepositoryJdbc.ORDEN, OmisoResource.class),
                            "GET /fiscalizacion/programas/{id}/muestra",
                            new Listado(
                                    MuestraDelProgramaRepositoryJdbc.ORDEN, MuestraResource.class),
                            "GET /fiscalizacion/resultados",
                            new Listado(LiquidacionRepositoryJdbc.ORDEN, LiquidacionResource.class),
                            "GET /fiscalizacion/programas",
                            new Listado(
                                    ProgramaFiscalizacionRepositoryJdbc.ORDEN,
                                    ProgramaResource.class)));

    @Test
    @DisplayName("todo nombre admitido en ordenarPor es un campo del recurso que la fila publica")
    void todoCampoAdmitidoLoPublicaLaFila() {
        Map<String, Set<String>> sinPublicar = new TreeMap<>();

        LISTADOS.forEach(
                (operacion, listado) -> {
                    Set<String> publicados = camposDe(listado.recurso());
                    Set<String> huerfanos = new TreeSet<>();
                    for (String admitido : listado.orden().camposAdmitidos()) {
                        if (admitido.indexOf('_') >= 0) {
                            // El nombre de columna crudo, que `sobre(...)` admite a proposito.
                            continue;
                        }
                        if (!publicados.contains(admitido)) {
                            huerfanos.add(admitido);
                        }
                    }
                    if (!huerfanos.isEmpty()) {
                        sinPublicar.put(
                                operacion + " → " + listado.recurso().getSimpleName(), huerfanos);
                    }
                });

        assertThat(sinPublicar)
                .as(
                        "estos listados admiten ordenar por un nombre que NINGUNA de sus filas"
                                + " lleva: el cliente no puede saber que existe, y el que la fila si"
                                + " ensena responde 422. Se declara con OrdenSeguro.publicandoComo(…)"
                                + " o se retira de la lista blanca")
                .isEmpty();
    }

    @Test
    @DisplayName("y la lista blanca no se queda vacia: si no, la regla de arriba pasaria sola")
    void cadaListadoAdmiteAlgo() {
        LISTADOS.forEach(
                (operacion, listado) ->
                        assertThat(listado.orden().camposAdmitidos())
                                .as("%s no admite ningun campo", operacion)
                                .isNotEmpty());
    }

    @Test
    @DisplayName("el orden POR OMISION de cada controlador esta en la lista blanca de su consulta")
    void elOrdenPorOmisionEstaAdmitido() {
        // Es el defecto que este issue encontro, visto desde el otro lado: `OmisosController`
        // pedia por omision `codigoRefCatastral`, y una lista blanca que solo admitiera el nombre
        // publicado habria dejado la pantalla entera en 422 sin que ninguna prueba de dominio lo
        // viera. La comprobacion es barata y no puede envejecer: lee la constante privada.
        assertThat(DeteccionRepositoryJdbc.ORDEN.camposAdmitidos())
                .contains(ordenPorOmisionDe(OmisosController.class));
        assertThat(MuestraDelProgramaRepositoryJdbc.ORDEN.camposAdmitidos())
                .contains(ordenPorOmisionDe(MuestraController.class));
        assertThat(ProgramaFiscalizacionRepositoryJdbc.ORDEN.camposAdmitidos())
                .contains(ordenPorOmisionDe(ProgramasController.class));
        assertThat(LiquidacionRepositoryJdbc.ORDEN.camposAdmitidos())
                .contains(ordenPorOmisionDe(LiquidacionController.class));
    }

    @Test
    @DisplayName("y el orden con que el SORTEO recorre el padron tambien: no solo el del GET")
    void elOrdenDelRecorridoDelSorteoEstaAdmitido() {
        // El hueco que #586 encontro ejecutando. `GenerarMuestra` no es un controlador y por eso
        // no entraba en la prueba de arriba: recorre el padron por paginas llamando a la MISMA
        // consulta, y pedia `predio_id`, que #546 saco de la lista blanca —con razon— dejandolo
        // solo como desempate. Desde ese merge `POST /fiscalizacion/programas/{id}/muestra`
        // contestaba 422 ORDEN_NO_ADMITIDO para todo programa, y no lo veia nadie porque
        // `GenerarMuestraTest` habla con un doble que ignora la `Paginacion`.
        assertThat(DeteccionRepositoryJdbc.ORDEN.camposAdmitidos())
                .as("el sorteo recorre la deteccion, asi que su orden es de esta lista blanca")
                .contains(constanteDe(GenerarMuestra.class, "ORDEN_DEL_RECORRIDO"));
    }

    @Test
    @DisplayName("publicandoComo retira el camelCase automatico: no deja los dos nombres vivos")
    void publicandoComoRetiraElNombreInterno() {
        assertThat(DeteccionRepositoryJdbc.ORDEN.camposAdmitidos())
                .as("el nombre que la fila publica")
                .contains("codRefCatastral");
        assertThat(DeteccionRepositoryJdbc.ORDEN.camposAdmitidos())
                .as(
                        "y no el otro: dejar los dos vivos es el defecto de partida —dos nombres"
                                + " para la misma columna en la misma operacion—")
                .doesNotContain("codigoRefCatastral");
    }

    // ------------------------------------------------------------------

    private static Set<String> camposDe(Class<?> recurso) {
        assertThat(recurso.isRecord()).as("%s tiene que ser un record", recurso).isTrue();
        Set<String> nombres = new LinkedHashSet<>();
        for (RecordComponent componente : recurso.getRecordComponents()) {
            nombres.add(componente.getName());
        }
        return nombres;
    }

    /** La constante privada {@code ORDEN_POR_OMISION} del controlador. */
    private static String ordenPorOmisionDe(Class<?> controlador) {
        return constanteDe(controlador, "ORDEN_POR_OMISION");
    }

    /** Una constante de texto privada, leida del propio codigo: una copia a mano envejeceria. */
    private static String constanteDe(Class<?> clase, String nombre) {
        List<Field> campos =
                Arrays.stream(clase.getDeclaredFields())
                        .filter(campo -> nombre.equals(campo.getName()))
                        .toList();
        assertThat(campos).as("%s no declara %s", clase.getSimpleName(), nombre).hasSize(1);
        try {
            Field campo = campos.get(0);
            campo.setAccessible(true);
            return (String) campo.get(null);
        } catch (IllegalAccessException imposible) {
            throw new IllegalStateException(imposible);
        }
    }

    private record Listado(OrdenSeguro orden, Class<?> recurso) {}
}
