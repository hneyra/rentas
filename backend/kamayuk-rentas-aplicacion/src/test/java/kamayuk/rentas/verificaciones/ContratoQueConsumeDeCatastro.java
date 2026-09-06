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
 * <p>Son las <b>catorce</b> operaciones que los adaptadores de {@code kamayuk-rentas-catastro}
 * piden hoy. Tres venian de antes —la grilla de fichas, el cuadro de valores unitarios y las
 * huellas del padron—, <b>cinco las estreno C-5</b>, que publico las rutas que le faltaban a esta
 * frontera —si el predio esta, lo que tiene inscrito a una fecha, el area de una version de ficha,
 * de quien son unos predios y que predios son de alguien—, y <b>cinco mas las estrena #9</b>: la
 * zona urbanistica, el riesgo del suelo, el ITSE, los frentes y los hallazgos de la fiscalizacion
 * catastral.
 *
 * <p><b>Cinco operaciones para cuatro puertos, y no es un descuadre</b>: {@code
 * RiesgoYItseDelPredio} pide por dos rutas porque {@code catastro} publica dos —{@code /grd/riesgo}
 * y {@code /grd/itse}—, con dos transacciones y dos respuestas del otro lado. Declarar una sola
 * dejaria a la otra sin nadie que comprobara su forma en el CI del proveedor, que es exactamente lo
 * que este archivo existe para conseguir.
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

    // ------------------------------------------------------------------
    // #9 — las CINCO operaciones de los cuatro puertos nuevos (`catastro`#4, #5, #6 y #7).
    //
    // Cuatro puertos y cinco operaciones, y no es un descuadre: `RiesgoYItseDelPredio` pregunta
    // por dos rutas —`/grd/riesgo` y `/grd/itse`— porque `catastro` publica dos, con dos
    // transacciones y dos respuestas. Declarar una sola dejaria a la otra sin nadie que
    // comprobara su forma en el CI del proveedor, que es justo lo que este archivo existe para
    // conseguir.

    /** Un parametro urbanistico de la zona, tal como lo lee {@code ZonificacionDelPredioHttp}. */
    public static final Map<String, Object> PARAMETRO_URBANISTICO =
            ordenados(
                    Map.entry("clave", "texto"),
                    Map.entry("valor", "texto"),
                    Map.entry("unidad", "texto"));

    /** La zona de un predio a una fecha (`catastro`#4). */
    public static final Map<String, Object> ZONA_DEL_PREDIO =
            ordenados(
                    // La fecha con la que catastro resolvio, no la que se pidio. El adaptador las
                    // compara, por lo mismo que en las caracteristicas del predio (C-1).
                    Map.entry("aLaFecha", "fecha"),
                    Map.entry("codigo", "texto"),
                    Map.entry("nombre", "texto"),
                    Map.entry("plan", "texto"),
                    // La norma que la aprobo: sin ella, quien niegue un giro por la zona no puede
                    // citar lo que lo sustenta, y una denegacion sin norma no se notifica.
                    Map.entry("ordenanza", "texto"),
                    Map.entry("vigenciaDesde", "fecha"),
                    Map.entry("vigenciaHasta", "fecha"),
                    Map.entry("parametros", List.of(PARAMETRO_URBANISTICO)));

    /** Una zona de riesgo que cruza el lote (`catastro`#5). */
    public static final Map<String, Object> ZONA_DE_RIESGO =
            ordenados(
                    Map.entry("id", "entero"),
                    Map.entry("codigo", "texto"),
                    Map.entry("fenomeno", "texto"),
                    Map.entry("nivel", "texto"),
                    // Al lado del nivel y no en su lugar: es el que decide. Una zona MUY_ALTO
                    // mitigable se construye con su obra de mitigacion.
                    Map.entry("mitigable", "booleano"),
                    Map.entry("fuente", "texto"),
                    Map.entry("documentoOrigen", "texto"),
                    Map.entry("vigenciaDesde", "fecha"),
                    Map.entry("vigenciaHasta", "fecha"));

    /** Una faja marginal que cruza el lote (`catastro`#5). */
    public static final Map<String, Object> FAJA_MARGINAL =
            ordenados(
                    Map.entry("id", "entero"),
                    Map.entry("codigo", "texto"),
                    Map.entry("cuerpoDeAgua", "texto"),
                    // Cadena y con la unidad en el NOMBRE: es la magnitud que fija la resolucion
                    // de la ANA, y un decimal de mas o de menos mueve un lindero (ADR-0021).
                    Map.entry("anchoM", "texto"),
                    Map.entry("fuente", "texto"),
                    Map.entry("documentoOrigen", "texto"),
                    Map.entry("vigenciaDesde", "fecha"),
                    Map.entry("vigenciaHasta", "fecha"));

    /** El riesgo del suelo de un predio (`catastro`#5). */
    public static final Map<String, Object> RIESGO_DEL_PREDIO =
            ordenados(
                    Map.entry("predioId", "entero"),
                    // Sale aunque esta operacion NO admita fecha: catastro resuelve con su reloj y
                    // dice cual uso. Sin ella la respuesta es una que dentro de un mes es otra.
                    Map.entry("aLaFecha", "fecha"),
                    // Derivado y arriba: es el dato que decide, y recalcularlo aqui recorriendo
                    // las zonas seria repetir la unica linea que importa.
                    Map.entry("hayRiesgoNoMitigable", "booleano"),
                    Map.entry("zonas", List.of(ZONA_DE_RIESGO)),
                    Map.entry("fajasMarginales", List.of(FAJA_MARGINAL)));

    /** Un certificado ITSE (`catastro`#5). */
    public static final Map<String, Object> CERTIFICADO_ITSE =
            ordenados(
                    Map.entry("id", "entero"),
                    Map.entry("numero", "texto"),
                    // El que el certificado ACREDITA. El que un giro exige es de este sistema
                    // (`ciiu.riesgo_itse`), y se escribe con el mismo vocabulario.
                    Map.entry("nivelRiesgo", "texto"),
                    Map.entry("modalidad", "texto"),
                    Map.entry("vigenciaDesde", "fecha"),
                    Map.entry("vigenciaHasta", "fecha"),
                    Map.entry("fechaAnulacion", "fecha"));

    /** El ITSE de un predio a una fecha (`catastro`#5). */
    public static final Map<String, Object> ITSE_DEL_PREDIO =
            ordenados(
                    Map.entry("predioId", "entero"),
                    Map.entry("aLaFecha", "fecha"),
                    Map.entry("vigentes", List.of(CERTIFICADO_ITSE)));

    /**
     * Un frente del predio (`catastro`#7).
     *
     * <p><b>Sin {@code geometria}</b>, que el recurso si publica: {@code rentas} no tiene visor de
     * plano, y un campo declarado aqui es un campo que el proveedor no puede retirar sin poner rojo
     * su build. Se declara lo que se usa.
     */
    public static final Map<String, Object> FRENTE_DEL_PREDIO =
            ordenados(
                    Map.entry("id", "entero"),
                    Map.entry("viaId", "entero"),
                    Map.entry("viaCodigo", "texto"),
                    Map.entry("viaNombre", "texto"),
                    // «18.50 ML»: la unidad va DENTRO del dato. El barrido se determina sobre
                    // metros lineales y el recojo sobre metros cuadrados, y leer unos por otros
                    // no falla: cobra otra cosa.
                    Map.entry("longitud", "texto"),
                    // PROPUESTA la corto una maquina; CONFIRMADA la firmo una persona (ADR-0021).
                    // Sin este campo las dos llegan iguales.
                    Map.entry("longitudEstado", "texto"),
                    Map.entry("esPrincipal", "booleano"),
                    Map.entry("numeracion", "texto"),
                    Map.entry("retiro", "texto"),
                    Map.entry("confirmadoPor", "texto"),
                    Map.entry("confirmadoEn", "texto"));

    /** Los frentes de un predio, con la constancia de cuando se derivaron (`catastro`#7). */
    public static final Map<String, Object> FRENTES_DEL_PREDIO =
            ordenados(
                    Map.entry("predioId", "entero"),
                    Map.entry("frentes", List.of(FRENTE_DEL_PREDIO)),
                    // Los tres que impiden confundir «no da a ninguna calle» con «nadie lo ha
                    // derivado»: hoy no hay ni un poligono cargado, asi que la respuesta va a ser
                    // siempre la segunda, y determinar sobre cero metros cobraria de menos.
                    Map.entry("derivadoEn", "texto"),
                    Map.entry("frentesDerivados", "entero"),
                    Map.entry("motivoDeLaDerivacion", "texto"));

    /** Un hallazgo de la fiscalizacion catastral (`catastro`#6, ADR-0035). */
    public static final Map<String, Object> FILA_DE_HALLAZGO =
            ordenados(
                    Map.entry("id", "entero"),
                    Map.entry("candidatoId", "entero"),
                    Map.entry("clase", "texto"),
                    Map.entry("predioId", "entero"),
                    // Contra que version se comparo, y cuando: una diferencia sin su version es
                    // una diferencia que manana es otra (regla 9).
                    Map.entry("fichaId", "entero"),
                    // Las tres areas viajan como cadena: `ConfiguracionDeJson` las serializa con
                    // `writeString` (RNF-055), igual que en la grilla de fichas.
                    Map.entry("areaDeLaFicha", "texto"),
                    Map.entry("areaVerificada", "texto"),
                    Map.entry("excesoVerificado", "texto"),
                    Map.entry("inspector", "texto"),
                    Map.entry("verificadoEn", "fecha"),
                    Map.entry("estado", "texto"));

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

        // ------------------------------------------------------------------
        // #9 — las cinco de la etapa 1. Cierran por el lado del consumidor lo que `catastro`
        // publico en sus #4, #5, #6 y #7.

        // La zona a la que cae un predio. La fecha viaja como `aLaFecha` —y no como `fecha`,
        // que es lo que leen las siete rutas de C-1—: es como ESTA operacion la nombra, y lo que
        // el adaptador manda es lo que el otro lado lee, no lo que el puerto llama.
        operaciones.put(
                "GET /urbano/zonificacion",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("predioId", "aLaFecha"), ZONA_DEL_PREDIO));

        // El riesgo del suelo. UN solo parametro, y es una decision medida: esta operacion no
        // admite fecha —catastro resuelve con su reloj y devuelve la que uso—, asi que mandarle
        // un `aLaFecha` seria el defecto de C-1 al reves: viajaria en la URL y se descartaria en
        // silencio. Lo que se pierde queda dicho en el puerto: desde aqui no se puede preguntar
        // por el riesgo de un dia pasado.
        operaciones.put(
                "GET /grd/riesgo",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("predioId"), RIESGO_DEL_PREDIO));

        // El ITSE vigente a una fecha. Esta SI la lee, asi que se manda y ademas se compara con
        // la que vuelve: un certificado vencido leido como vigente es una licencia emitida
        // contra un papel caducado.
        operaciones.put(
                "GET /grd/itse",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("predioId", "aLaFecha"), ITSE_DEL_PREDIO));

        // Los frentes de un predio: el insumo de los arbitrios de barrido. Sin parametros —un
        // frente no se resuelve a una fecha; lo que tiene fecha es su confirmacion, y viaja
        // dentro de cada frente—.
        operaciones.put(
                "GET /catastro/predios/{predioId}/frentes",
                ContratoDelConsumidor.OperacionEsperada.lectura(Set.of(), FRENTES_DEL_PREDIO));

        // Los hallazgos de una campania. Es la UNICA lectura de hallazgos que catastro publica;
        // sus otras seis operaciones abren la campania, detectan, verifican, adjuntan evidencia
        // y levantan acta, y ninguna es cosa de `rentas`.
        //
        // No se manda `ordenarPor`: su lista blanca es de catastro —cual campo es admisible
        // depende de la tabla— y pedir uno que no admita seria un 422. Sin el ordena por
        // `verificadoEn`, que es su valor por omision; lo que hace falta es que HAYA un orden,
        // porque sin `ORDER BY` dos paginas consecutivas repiten una fila y omiten otra.
        operaciones.put(
                "GET /fiscalizacion/campanias/{campaniaId}/hallazgos",
                ContratoDelConsumidor.OperacionEsperada.lectura(
                        Set.of("pagina", "tamano"),
                        ordenados(
                                Map.entry("contenido", List.of(FILA_DE_HALLAZGO)),
                                Map.entry("totalElementos", "entero"))));

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
