package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.FichaDelPadron;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos;
import kamayuk.rentas.fiscalizacion.infraestructura.web.OmisoResource;
import kamayuk.rentas.rentas.aplicacion.ConsultaDeConciliacion;
import kamayuk.rentas.rentas.infraestructura.web.FichaConciliadaResource;
import kamayuk.rentas.rentas.infraestructura.web.PredioDeRentasResource;
import kamayuk.rentas.web.ConfiguracionDeJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * El mismo predio, serializado por los cuatro modulos que publican su area (#607).
 *
 * <h2>Que se mide, y por que hace falta medirlo aqui</h2>
 *
 * <p>{@code AreaM2} salia con <b>tres</b> formas vivas a la vez, y el mismo predio de Catacaos
 * —{@code 20010500000026010101001}— contestaba distinto segun a quien se le preguntara:
 *
 * <pre>
 * GET /fiscalizacion/omisos   → "areaCatastral": "360.00"
 * GET /catastro/fichas        → "areaTerreno":   "360.00 m2"
 * </pre>
 *
 * <p>Las tres producian la misma cifra y <b>ninguna fallaba</b>: la del campo tipado, que la
 * escribe el serializador que {@code ConfiguracionDeJson} registra; la de {@code
 * valor().toPlainString()} a mano, que da los mismos bytes por otro camino; y la de {@code
 * toString()} a mano, que es la unica que mete la unidad dentro del dato. Es el defecto que #546
 * cerro <b>dentro</b> de fiscalizacion, aplicado ahora entre modulos.
 *
 * <p>Con la unidad dentro, «360.00 m2» no se ordena, no se suma y no se compara con lo que
 * fiscalizacion publica del mismo predio sin partir la cadena —y partirla para volver a formatearla
 * es como se pierde un decimal, RNF-055—. La unidad la pone la cabecera de la columna.
 *
 * <p><b>Esta prueba vive en {@code kamayuk-rentas-aplicacion} porque es el unico modulo que ve
 * catastro, rentas, fiscalizacion y licencias a la vez.</b> Cada modulo puede comprobar su propia
 * coherencia —{@code AreaEnUnaSolaFormaTest} lo hace para fiscalizacion— y ninguno puede ver que el
 * de al lado publica otra cosa, que es exactamente donde estaba el defecto.
 *
 * <p>Se serializa con el <b>mismo modulo de Jackson</b> que registra la aplicacion. Comparar los
 * {@code record} en memoria no diria nada: lo que tiene que coincidir es el texto que sale por el
 * cable.
 */
@DisplayName("#607 — El area sale con la misma forma en los cuatro modulos que la publican")
class AreaEnLaMismaFormaEntreModulosTest {

    /**
     * Los campos que <b>se llaman</b> area y no son una superficie, nombrados uno a uno.
     *
     * <p>Lo destapo esta misma prueba la primera vez que corrio, y es el filo de cualquier regla
     * anclada al nombre: en castellano <b>«area» tambien es una oficina</b>. {@code
     * FilaDePartida.area} y {@code areaNombre} son el codigo y el nombre del <i>area generadora</i>
     * de la recaudacion —una unidad organica del municipio—, y {@code
     * CertificadoResource.areaLibreMinima} es un parametro urbanistico transcrito del certificado
     * de zonificacion, texto libre por ordenanza (#427). Ninguno es un {@code AreaM2} ni puede
     * serlo.
     *
     * <p>Los dos de {@code CajaEnListaResource} son <b>la misma oficina</b> que la de {@code
     * FilaDePartida}, vista desde el otro lado (#618): {@code caja.area_id} apunta al area a la que
     * se imputa lo que la ventanilla recauda, y el catalogo la publica legible —codigo y nombre— en
     * vez del numero, que fuera del servidor no lo puede leer nadie. Se conserva la palabra {@code
     * area} porque es como la nombran la tabla, la pantalla «Recaudacion por area» y el resto del
     * modulo; inventarle aqui un sinonimo para esquivar esta regla dejaria el mismo concepto con
     * dos nombres, que es el defecto que #607 cerro con las superficies.
     *
     * <p>Se nombran por componente y no por clase ni por paquete para que anadir uno sea una linea
     * visible en el diff: la salida comoda ante un rojo aqui es apuntar el campo en la lista, y hay
     * que verla al revisar.
     *
     * <p>El escaner de fuentes (#607, {@code RevisorDeCodigoFuente.revisarAreas}) tiene el mismo
     * limite y hoy no le hace falta lista: ninguna de las tres se convierte a texto a mano en
     * {@code src/main}. Si alguna llegara a hacerlo, el hallazgo seria un falso positivo y habria
     * que decidirlo alli con el mismo criterio.
     */
    private static final Set<String> NO_SON_SUPERFICIES =
            Set.of(
                    "FilaDePartida.area",
                    "FilaDePartida.areaNombre",
                    "CertificadoResource.areaLibreMinima",
                    "CajaEnListaResource.areaCodigo",
                    "CajaEnListaResource.areaNombre");

    /** El predio del issue, con la plantilla de 23 posiciones del manual. */
    private static final String CODIGO = "20010500000026010101001";

    /** La misma superficie para todos. */
    private static final AreaM2 AREA = AreaM2.de("360.00");

    /** Lo que el serializador de {@code ConfiguracionDeJson} escribe: la cifra, sin unidad. */
    private static final String ESPERADO = "\"360.00\"";

    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                    .build();

    @Test
    @DisplayName("la conciliacion y fiscalizacion dicen lo mismo del mismo predio")
    void laConciliacionYFiscalizacionDicenLoMismo() {
        // Hasta P5C el par que se comparaba aqui era `FichaEncontradaResource` —de `catastro`—
        // contra `OmisoResource`. Esa respuesta se fue con `V6`, y su area la sigue vigilando la
        // MISMA guarda en el repositorio donde ahora vive: `AreaEnLaMismaFormaEntreModulosTest`
        // no se copio, pero si la prueba estructural que recorre las respuestas publicadas y
        // exige que ningun componente `area…` sea `String`. Dejar aqui una referencia a una clase
        // que no esta seria una entrada rancia, que es justo lo que esta prueba existe para no
        // tener.
        //
        // El par que queda es el de las DOS puertas de `rentas`, que es donde #607 encontro las
        // dos convenciones a la vez.
        String conciliacion = campoDe(JSON.writeValueAsString(fichaConciliada()), "areaTerreno");
        String fiscalizacion = campoDe(JSON.writeValueAsString(omiso()), "areaCatastral");

        assertThat(conciliacion)
                .as(
                        "hasta #607 la misma superficie salia «360.00 m2» por una puerta y"
                                + " «360.00» por la otra")
                .isEqualTo(fiscalizacion)
                .isEqualTo(ESPERADO);
    }

    @Test
    @DisplayName("y la conciliacion y el padron predial de rentas, tambien")
    void rentasDiceLoMismoPorSusDosPuertas() {
        String conciliacion = campoDe(JSON.writeValueAsString(fichaConciliada()), "areaTerreno");
        String padron = campoDe(JSON.writeValueAsString(predioDeRentas()), "areaTerreno");

        assertThat(List.of(conciliacion, padron))
                .as(
                        "rentas tenia las DOS convenciones a mano: la conciliacion componia con"
                                + " toString() y el padron predial con valor().toPlainString()")
                .containsExactly(ESPERADO, ESPERADO);
    }

    @Test
    @DisplayName("ninguna de las tres respuestas lleva la unidad dentro")
    void ningunaLlevaLaUnidad() {
        assertThat(JSON.writeValueAsString(fichaConciliada())).doesNotContain("m2");
        assertThat(JSON.writeValueAsString(omiso())).doesNotContain("m2");
        assertThat(JSON.writeValueAsString(predioDeRentas())).doesNotContain("m2");
    }

    /**
     * Lo mismo que arriba, pero para las operaciones que este archivo no nombra.
     *
     * <p>Las cuatro de arriba son las del issue; esta recorre <b>todas</b> las publicadas y exige
     * que ningun campo de area viaje como {@code String}. Es la mitad estructural del arreglo, y no
     * sobra con el escaner de fuentes: un recurso nuevo que reciba el area ya hecha cadena desde su
     * repositorio no escribe ningun {@code toString()} que el escaner pueda ver, y aqui sale rojo
     * igual.
     *
     * <p>El criterio es el nombre del componente, por lo mismo que en el escaner: tiene que
     * <b>empezar</b> por {@code area}. Asi «hect<b>area</b>s» queda fuera, que es una {@link
     * kamayuk.rentas.dominio.Medida} y lleva su unidad dentro a proposito.
     */
    @Test
    @DisplayName("ninguna respuesta publicada declara un campo de area como String")
    void ningunaRespuestaDeclaraElAreaComoTexto() {
        List<String> comoTexto = new ArrayList<>();

        for (Map.Entry<String, Method> operacion : EndpointsPublicados.porOperacion().entrySet()) {
            for (Class<?> registro : registrosDe(operacion.getValue().getGenericReturnType())) {
                for (RecordComponent componente : registro.getRecordComponents()) {
                    if (esNombreDeArea(componente.getName())
                            && !NO_SON_SUPERFICIES.contains(
                                    registro.getSimpleName() + "." + componente.getName())
                            && componente.getType() == String.class) {
                        comoTexto.add(
                                operacion.getKey()
                                        + " — "
                                        + registro.getSimpleName()
                                        + "."
                                        + componente.getName());
                    }
                }
            }
        }

        assertThat(comoTexto)
                .as(
                        "un area declarada String vuelve a dejar al recurso componiendola, y de ahi"
                                + " salieron las tres convenciones de #607")
                .isEmpty();
    }

    /** Y la comprobacion de que ese recorrido mira algo: si no ve areas, no puede decir nada. */
    @Test
    @DisplayName("el recorrido de respuestas publicadas alcanza campos de area de verdad")
    void elRecorridoAlcanzaAreas() {
        Set<String> encontradas = new LinkedHashSet<>();

        for (Method metodo : EndpointsPublicados.porOperacion().values()) {
            for (Class<?> registro : registrosDe(metodo.getGenericReturnType())) {
                for (RecordComponent componente : registro.getRecordComponents()) {
                    if (esNombreDeArea(componente.getName())) {
                        encontradas.add(registro.getSimpleName() + "." + componente.getName());
                    }
                }
            }
        }

        assertThat(encontradas)
                .as("si el recorrido no llega a ningun area, la prueba de arriba pasa sin mirar")
                .hasSizeGreaterThan(10)
                .contains(
                        "OmisoResource.areaCatastral",
                        "FichaConciliadaResource.areaTerreno",
                        "PredioDeRentasResource.areaTerreno");

        assertThat(encontradas)
                .as("y las tres exentas siguen publicandose; una entrada rancia no exime nada")
                .containsAll(NO_SON_SUPERFICIES);
    }

    // ------------------------------------------------------------------

    /** El identificador empieza por {@code area}: {@code hectareas} lo contiene y no empieza. */
    private static boolean esNombreDeArea(String nombre) {
        return nombre.toLowerCase(Locale.ROOT).startsWith("area");
    }

    /** Los {@code record} alcanzables desde el tipo de retorno de un controlador. */
    private static Set<Class<?>> registrosDe(Type tipo) {
        Set<Class<?>> encontrados = new LinkedHashSet<>();
        recorrer(tipo, encontrados);
        return encontrados;
    }

    private static void recorrer(Type tipo, Set<Class<?>> encontrados) {
        if (tipo instanceof ParameterizedType parametrizado) {
            recorrer(parametrizado.getRawType(), encontrados);
            for (Type argumento : parametrizado.getActualTypeArguments()) {
                recorrer(argumento, encontrados);
            }
            return;
        }
        if (tipo instanceof WildcardType comodin) {
            for (Type superior : comodin.getUpperBounds()) {
                recorrer(superior, encontrados);
            }
            return;
        }
        if (tipo instanceof TypeVariable<?>) {
            return;
        }
        if (!(tipo instanceof Class<?> clase) || !clase.isRecord()) {
            return;
        }
        if (!encontrados.add(clase)) {
            return;
        }
        for (RecordComponent componente : clase.getRecordComponents()) {
            recorrer(componente.getGenericType(), encontrados);
        }
    }

    // ------------------------------------------------------------------

    private static OmisoResource omiso() {
        return OmisoResource.de(
                new FilaDeOmisos(
                        2L,
                        CODIGO,
                        "05",
                        List.of(),
                        new Ejercicio(2026),
                        CondicionFiscalizada.SUBVALUADOR,
                        false,
                        AREA,
                        AreaM2.de("120.00"),
                        null,
                        null,
                        null),
                Map.of());
    }

    private static FichaConciliadaResource fichaConciliada() {
        return FichaConciliadaResource.de(
                new ConsultaDeConciliacion.FichaConciliada(
                        new FichaDelPadron(
                                1L,
                                2L,
                                CODIGO,
                                "CAL. LIMA 100",
                                "026",
                                "01",
                                "UNICA",
                                1,
                                AREA,
                                AreaM2.de("120.00"),
                                "CASA HABITACION",
                                LocalDate.of(2026, 1, 1),
                                "TITULAR, PRUEBA"),
                        true,
                        new Ejercicio(2026)));
    }

    private static PredioDeRentasResource predioDeRentas() {
        return PredioDeRentasResource.de(
                new PredioDelContribuyente(
                        2L, CODIGO, "URBANO", "CAL. LIMA 100", Porcentaje.de("100")),
                new CaracteristicasDelPredio("CASA HABITACION", "05", AREA),
                "PROPIETARIO_UNICO");
    }

    /** El valor de un campo del JSON, tal cual sale: con sus comillas si es una cadena. */
    private static String campoDe(String json, String campo) {
        int desde = json.indexOf("\"" + campo + "\":");
        assertThat(desde).as("el campo %s tenia que estar en %s", campo, json).isNotNegative();
        int inicio = desde + campo.length() + 3;
        int fin = json.indexOf(',', inicio);
        if (fin < 0) {
            fin = json.indexOf('}', inicio);
        }
        return json.substring(inicio, fin);
    }
}
