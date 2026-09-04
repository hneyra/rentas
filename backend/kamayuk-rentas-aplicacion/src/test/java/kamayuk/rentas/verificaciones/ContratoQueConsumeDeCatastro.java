package kamayuk.rentas.verificaciones;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kamayuk.comun.verificaciones.contrato.ContratoDelConsumidor;
import kamayuk.comun.verificaciones.contrato.ContratoQueSePublicaTestBase;
import org.junit.jupiter.api.DisplayName;

/**
 * Lo que {@code rentas} le pide a {@code catastro}, publicado para que su CI lo comprueba.
 *
 * <p>Son las <b>nueve</b> operaciones que los adaptadores de {@code kamayuk-rentas-catastro} piden
 * hoy. Tres venian de antes —la grilla de fichas, el cuadro de valores unitarios y las huellas del
 * padron— y <b>cinco las estreno C-5</b>, que publico las rutas que le faltaban a esta frontera: si
 * el predio esta, lo que tiene inscrito a una fecha, el area de una version de ficha, de quien son
 * unos predios y que predios son de alguien.
 *
 * <p>Lo que sigue sin declararse son las <b>dos escrituras</b>, y no porque falte la ruta: {@code
 * GestorDeTitularidad.transferir} y {@code TransferenciaDeFiscalizacion.inscribirLoHallado} ocurren
 * dentro de una transaccion de {@code rentas} que confirma otras escrituras despues de ellas, y dos
 * bases no comparten transaccion. Lanzan {@code EscrituraSinTransaccionCompartida}, que dice
 * exactamente eso; el motivo esta en {@code TitularidadHttp} y en {@code SinRutaTodavia}. Un
 * contrato que las declarara pondria rojo el CI del proveedor por una ruta que este backend no
 * llama.
 *
 * <h2>La ida y vuelta, que es lo que hace que esto no sea otra copia a mano</h2>
 *
 * <p>Que el archivo publicado sea el que produce {@link #contrato()} no dice nada sobre si {@link
 * #contrato()} describe lo que el adaptador lee: los dos salen de este repositorio. Lo que lo
 * sostiene es {@code LecturaDeCatastroTest}, que vive en el paquete del adaptador porque ahi es
 * donde {@code ficha(...)} es visible: fabrica una respuesta <b>a partir de {@link
 * #FILA_DE_FICHA}</b>, la pasa por {@code ClienteHttpDeCatastro.ficha} —el codigo de produccion, no
 * una copia— y exige que la ficha salga completa. Cambiar el adaptador para leer {@code id} donde
 * el contrato dice {@code fichaId} deja la respuesta fabricada sin ese campo y la ficha sale con un
 * cero, que es exactamente el defecto que hay que impedir.
 */
@DisplayName("Contrato que rentas consume de catastro")
public class ContratoQueConsumeDeCatastro extends ContratoQueSePublicaTestBase {

    /**
     * La grilla de fichas del padron, tal como la lee {@code ClienteHttpDeCatastro.ficha}.
     *
     * <p>Los nombres son los del JSON, no los del {@code record} {@code FichaDelPadron}: el
     * adaptador lee con {@code path("…")}, asi que el contrato es lo que teclea ahi y no como se
     * llame el componente que rellena. Es la distincion que hace util a esta prueba —{@code
     * fichaId} contra {@code id} son el mismo componente y dos contratos distintos—.
     */
    public static final Map<String, Object> FILA_DE_FICHA =
            ordenados(
                    Map.entry("fichaId", "entero"),
                    Map.entry("predioId", "entero"),
                    Map.entry("codRefCatastral", "texto"),
                    Map.entry("direccion", "texto"),
                    Map.entry("manzana", "texto"),
                    Map.entry("lote", "texto"),
                    Map.entry("tipo", "texto"),
                    Map.entry("version", "entero"),
                    // `AreaM2` viaja como cadena: `ConfiguracionDeJson` lo serializa con
                    // `writeString` (RNF-055), y el adaptador la lee con `asText`.
                    Map.entry("areaTerreno", "texto"),
                    Map.entry("areaConstruida", "texto"),
                    Map.entry("uso", "texto"),
                    // «texto» y no «fecha», y esto es una DECISION medida (C-1, desajuste 2).
                    // `FichaEncontradaResource` lo publica como `String` —igual que `FichaResource`
                    // y `PredioDelResumenResource`, tres de las cuatro fichas de catastro—, y el
                    // adaptador lo lee con `asText()` y lo parsea. El contrato describe el JSON que
                    // viaja, no el objeto que quien lee construye con el: `LocalDate` y `String`
                    // salen los DOS como la misma cadena ISO, asi que declarar «fecha» describia lo
                    // que hace este lado. El contrato con `normativa` ya declara «texto» para el
                    // mismo campo.
                    Map.entry("vigenciaDesde", "texto"),
                    Map.entry("titular", "texto"));

    /**
     * Una fila del cuadro sellado, tal como la lee {@code ValoresUnitariosHttp}.
     *
     * <p>Publica por lo mismo que {@link #FILA_DE_FICHA}: la usa la ida y vuelta que fabrica una
     * respuesta con estos campos y la pasa por el adaptador de produccion (C-1).
     */
    public static final Map<String, Object> FILA_DE_VALOR_UNITARIO =
            ordenados(
                    Map.entry("partida", "texto"),
                    Map.entry("categoria", "texto"),
                    Map.entry("anioConstruccionDesde", "entero"),
                    Map.entry("anioConstruccionHasta", "entero"),
                    Map.entry("valorM2", "texto"));

    /** «Esta este predio en el padron», tal como lo lee {@code TitularesDelPredioHttp} (C-5). */
    public static final Map<String, Object> PREDIO_EN_EL_PADRON =
            ordenados(Map.entry("predioId", "entero"), Map.entry("enElPadron", "booleano"));

    /**
     * Lo inscrito de un predio a una fecha, tal como lo lee {@code CaracteristicasDelPredioHttp}
     * (C-5).
     */
    public static final Map<String, Object> CARACTERISTICAS_DEL_PREDIO =
            ordenados(
                    Map.entry("predioId", "entero"),
                    Map.entry("enElPadron", "booleano"),
                    Map.entry("fichaId", "entero"),
                    Map.entry("fichaEconomicaId", "entero"),
                    Map.entry("uso", "texto"),
                    Map.entry("sectorCodigo", "texto"),
                    // `AreaM2` viaja como cadena: `ConfiguracionDeJson` la serializa con
                    // `writeString` (RNF-055), y el adaptador la lee con `asText`.
                    Map.entry("areaTerreno", "texto"),
                    // La fecha con la que catastro resolvio, no la que se pidio. El adaptador las
                    // compara: es lo unico que caza desde este lado el defecto de C-1.
                    Map.entry("aLaFecha", "fecha"));

    /** El area de UNA version de ficha, tal como la lee {@code CaracteristicasDelPredioHttp}. */
    public static final Map<String, Object> AREA_DE_LA_VERSION =
            ordenados(
                    Map.entry("fichaId", "entero"),
                    Map.entry("existe", "booleano"),
                    Map.entry("areaTerreno", "texto"));

    /** La cuota de UN titular, con su identificador, tal como la lee {@code TitularidadHttp}. */
    public static final Map<String, Object> CUOTA_DEL_TITULAR =
            ordenados(
                    Map.entry("predioId", "entero"),
                    Map.entry("contribuyenteId", "entero"),
                    Map.entry("aLaFecha", "fecha"),
                    Map.entry("tieneCuota", "booleano"),
                    Map.entry("titularidadId", "entero"),
                    Map.entry("porcentaje", "texto"));

    /**
     * Una cuota de titularidad vigente, tal como la lee {@code TitularesDelPredioHttp} (C-5).
     *
     * <p>Sin {@code titularidadId} a proposito: el listado no lo publica, porque es el
     * identificador con el que se transfiere una cuota. Quien lo necesita lo pide por {@code
     * /catastro/titularidad/cuota}, de un titular y un predio cada vez.
     */
    public static final Map<String, Object> CUOTA_DE_UN_TITULAR =
            ordenados(
                    Map.entry("contribuyenteId", "entero"),
                    // `Porcentaje` viaja como cadena, igual que `AreaM2`: `ConfiguracionDeJson`
                    // lo serializa con `writeString` (RNF-055, regla 1).
                    Map.entry("condicion", "texto"),
                    Map.entry("porcentaje", "texto"));

    /** Un predio de un contribuyente, tal como lo lee {@code PrediosDelContribuyenteHttp} (C-5). */
    public static final Map<String, Object> PREDIO_DEL_TITULAR =
            ordenados(
                    Map.entry("predioId", "entero"),
                    Map.entry("codRefCatastral", "texto"),
                    Map.entry("tipo", "texto"),
                    Map.entry("direccion", "texto"),
                    // Los DOS: uno pondera la base (#395) y el otro dice si el saneamiento de la
                    // titularidad esta completo (#690). No se deriva uno del otro.
                    Map.entry("porcentajeTitularidad", "texto"),
                    Map.entry("porcentajeRegistradoDelPredio", "texto"));

    /**
     * Publico y no protegido: lo lee {@code PeticionesACatastroTest}, que vive en el paquete del
     * adaptador y compara los parametros declarados aqui con los que la URL construida manda de
     * verdad (C-1). Ampliar la visibilidad de un metodo heredado es legitimo, y la alternativa
     * —copiar la lista de parametros en la otra prueba— seria una segunda copia del contrato.
     */
    @Override
    public ContratoDelConsumidor contrato() {
        Map<String, ContratoDelConsumidor.OperacionEsperada> operaciones = new LinkedHashMap<>();

        operaciones.put(
                "GET /catastro/fichas",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of(
                                // `fecha` y no `aLaFecha`: es como catastro nombra la fecha de
                                // corte en su capa web (C-1, desajuste 3). El puerto sigue
                                // llamandolo `aLaFecha`, que es la regla 9; traducir es lo que
                                // hace el adaptador.
                                "fecha",
                                "pagina",
                                "tamano",
                                "codRefCatastral",
                                "contribuyente",
                                "manzana",
                                "lote",
                                "tipo",
                                "soloPredio",
                                "exceptoPredio"),
                        ordenados(
                                Map.entry("contenido", List.of(FILA_DE_FICHA)),
                                Map.entry("totalElementos", "entero"))));

        // La anti-entropia (P6, punto 4). Una sola ruta con dos formas: sin `detalle`, una
        // cifra por sector; con `detalle=true` y un `sector`, los lotes de ese sector. El
        // contrato declara las DOS listas porque el proveedor devuelve las dos siempre —la que
        // no se pidio viaja vacia—, y un campo que a veces no esta obliga a quien lo lee a
        // distinguir «no lo pedi» de «no hay ninguno», que se leen igual.
        operaciones.put(
                "GET /catastro/predios/huellas",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("sector", "detalle"),
                        ordenados(
                                Map.entry(
                                        "sectores",
                                        List.of(
                                                ordenados(
                                                        Map.entry("sector", "texto"),
                                                        Map.entry("lotes", "entero"),
                                                        Map.entry("huella", "texto")))),
                                Map.entry(
                                        "lotes",
                                        List.of(
                                                ordenados(
                                                        Map.entry("predioId", "entero"),
                                                        Map.entry("huella", "texto")))))));

        // El cuadro sellado se lee ENTERO y la respuesta es un ARRAY, no un sobre paginado
        // (C-1, desajuste 6): no hay pagina que pedir ni `totalElementos` que significara nada.
        // Y el parametro es `ejercicio`, que es lo que catastro lee desde C-1 (desajuste 7):
        // lo que acota es el ejercicio del conjunto sellado, no un ano cualquiera —y en esta
        // misma respuesta viaja un `anioConstruccionDesde`, que si lo es—.
        operaciones.put(
                "GET /catastro/tablas/valores-unitarios",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("ejercicio"), List.of(FILA_DE_VALOR_UNITARIO)));

        // ------------------------------------------------------------------
        // C-5 — las cinco lecturas que P5C dejo sin ruta. Ninguna estaba aqui porque ningun
        // adaptador las pedia: los puertos lanzaban nombrando la operacion que los serviria.

        // «Esta este predio en el padron». Sin parametros y sin fecha: el predio no tiene
        // vigencia, y darle una sugeriria que se resuelve. La ausencia viaja como campo y no
        // como 404, para que el 404 siga queriendo decir «esa ruta no existe».
        operaciones.put(
                "GET /catastro/predios/{predioId}",
                ContratoDelConsumidor.OperacionEsperada.lectura(Set.of(), PREDIO_EN_EL_PADRON));

        // Lo inscrito a una fecha, en UNA peticion: la ficha unica, la economica, el uso, el
        // sector y el area. Tres rutas habrian sido tres transacciones del otro lado, con una
        // version nueva cabiendo entre la primera y la tercera (#486).
        operaciones.put(
                "GET /catastro/predios/{predioId}/caracteristicas",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("fecha"), CARACTERISTICAS_DEL_PREDIO));

        // El area de UNA version de ficha, por su identificador (#49, RF-055). Sin fecha: la
        // version ya lleva su vigencia dentro, y resolverla es justo lo que no se quiere.
        operaciones.put(
                "GET /catastro/fichas/{fichaId}/area",
                ContratoDelConsumidor.OperacionEsperada.lectura(Set.of(), AREA_DE_LA_VERSION));

        // De quien son estos predios. `predio` se repite: una pagina de veinte omisos cuesta UNA
        // peticion, que es la forma que el puerto conservo desde P5C.
        operaciones.put(
                "GET /catastro/titularidad",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("predio", "fecha"),
                        ordenados(
                                Map.entry("aLaFecha", "fecha"),
                                Map.entry(
                                        "predios",
                                        List.of(
                                                ordenados(
                                                        Map.entry("predioId", "entero"),
                                                        Map.entry(
                                                                "cuotas",
                                                                List.of(CUOTA_DE_UN_TITULAR))))))));

        // La cuota de UN titular, con el identificador con el que se transfiere. «No es titular»
        // viaja como `tieneCuota:false` y no como 404, por lo mismo que arriba.
        operaciones.put(
                "GET /catastro/titularidad/cuota",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("predio", "contribuyente", "fecha"), CUOTA_DEL_TITULAR));

        // Los predios de un contribuyente: la lectura de la que sale la base del predial. El
        // contribuyente y la fecha vuelven en el cuerpo, y el adaptador comprueba los dos antes
        // de leer una fila (#298).
        operaciones.put(
                "GET /catastro/titularidad/predios",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("contribuyente", "fecha"),
                        ordenados(
                                Map.entry("contribuyenteId", "entero"),
                                Map.entry("aLaFecha", "fecha"),
                                Map.entry("predios", List.of(PREDIO_DEL_TITULAR)))));

        return new ContratoDelConsumidor("rentas", "catastro", "/catastro/api/v1", operaciones);
    }

    @SafeVarargs
    private static Map<String, Object> ordenados(Map.Entry<String, Object>... campos) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (Map.Entry<String, Object> campo : campos) {
            mapa.put(campo.getKey(), campo.getValue());
        }
        // `unmodifiableMap` sobre un `LinkedHashMap`, no `Map.copyOf`: este mapa se
        // serializa al archivo comprometido, y el orden de iteracion de `Map.copyOf` no
        // esta especificado — el archivo cambiaria de una corrida a otra sin que nadie
        // tocara nada, y entonces la comparacion byte a byte deja de significar algo.
        return Collections.unmodifiableMap(mapa);
    }
}
