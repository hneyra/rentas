package kamayuk.rentas.fiscalizacion.dominio;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Las liquidaciones de fiscalización y su detalle. Ningún método recibe la municipalidad (regla 2).
 *
 * <p><b>No hay {@code actualizar} ni {@code eliminar}, y no es una omisión.</b> {@link #insertar}
 * es el único punto de escritura: una liquidación se notifica al contribuyente, que se lleva el
 * papel, así que corregirla en el sitio dejaría al papel y al sistema diciendo cosas distintas. Se
 * reliquida —otra versión— o se anula con un movimiento. V39 no le concede {@code UPDATE} a {@code
 * kamayuk_app}, y el escáner del código fuente lo vigila además en {@code TABLAS_INMUTABLES}: la
 * barrera de la base falla en ejecución, la del escáner rompe el build, que es donde cuesta barato.
 */
public interface LiquidacionRepository {

    /**
     * Inserta la liquidación con su detalle, en un solo acto.
     *
     * <p>Las dos escrituras van juntas porque una liquidación sin líneas no es una liquidación
     * incompleta: es una afirmación sin sustento. La transacción la abre el caso de uso.
     */
    Liquidacion insertar(Liquidacion liquidacion, List<LineaDeLiquidacion> lineas);

    Optional<Liquidacion> porNumero(String numero);

    /**
     * Serializa, hasta el final de la transaccion, los actos que deciden sobre el estado de esta
     * liquidacion (#484).
     *
     * <p>Anular lee que no hay RDF y escribe ANULADA; transferir lee LIQUIDADA y escribe la RDF.
     * Sin candado las dos leen el estado anterior y las dos escriben, y queda una RDF vigente sobre
     * una liquidacion anulada: el estado que #338 declaro imposible. Se toma <b>antes</b> de leer
     * el estado. Es un candado consultivo y no un {@code FOR UPDATE}: la tabla no admite {@code
     * UPDATE} (V39 §3), y bloquear la fila exigiria el privilegio que se le retiro.
     */
    void bloquear(long liquidacionId);

    Optional<Liquidacion> findById(long id);

    /** Las líneas de una liquidación, ordenadas por ejercicio y unidad. */
    List<LineaDeLiquidacion> lineasDe(long liquidacionId);

    /**
     * Todas las versiones de un acta, de la primera a la última. Es lo que el histórico recorre
     * para reconstruir el proceso completo (AC 5).
     */
    List<Liquidacion> versionesDeActa(long actaId);

    /** La última versión emitida de un acta, si tiene alguna. */
    Optional<Liquidacion> ultimaVersionDeActa(long actaId);

    /** La búsqueda de las dos grillas, paginada. */
    Pagina<Liquidacion> consultar(CriterioDeLiquidaciones criterio, Paginacion paginacion);

    /**
     * El siguiente correlativo del ejercicio, en una sola sentencia.
     *
     * <p>Nunca {@code SELECT} + {@code UPDATE}: dos liquidaciones simultáneas leerían el mismo
     * último y saldrían con el mismo «Nº Liquidación». Mismo patrón que {@code
     * ExpedienteRepository#siguienteCorrelativo} (V33) y por el mismo motivo.
     */
    long siguienteCorrelativo(Ejercicio ejercicio);

    /**
     * Las liquidaciones que se le hicieron a un contribuyente, para su estado de cuenta: de la
     * <b>más reciente a la más antigua</b>, por acta y, dentro de un acta, por versión.
     *
     * <p>El orden es contrato y no presentación: el estado de cuenta conserva una línea por
     * ejercicio y unidad y se queda con la primera que le llega. Hasta #342 la consulta ordenaba
     * por {@code acta_id} ascendente, y con dos actas sobre la misma unidad —una segunda visita—
     * ganaba la liquidación del acta más antigua, con su condición.
     */
    List<Liquidacion> deContribuyente(long contribuyenteId);

    /**
     * Cuántas <b>unidades</b> del programa sostienen una determinación: la cuarta etapa del embudo
     * (#196).
     *
     * <p>«Sostiene una determinación» no es una opinión: es {@link
     * CondicionFiscalizada#hayDiferencia}, que ya decide qué condiciones justifican determinar de
     * oficio. Quien llama pasa ese conjunto para que la lista no se escriba dos veces — la lección
     * de #397: dos copias de la misma regla divergen, y la que se lee en pantalla acaba no siendo
     * la que filtró.
     *
     * <p>Cuenta unidades y sólo sobre la <b>última</b> versión de cada liquidación. Las dos cosas
     * por el mismo motivo: reliquidar emite otra versión que <b>sustituye</b> a la anterior, y
     * contar las dos diría que un predio corregido a {@code CONFORME} sigue con diferencia. Es el
     * mismo {@code soloUltimaVersion} que pide la grilla de resultados.
     *
     * <p>Un acta anulada no cuenta, igual que en la etapa anterior.
     */
    int unidadesConDiferencia(long programaId, java.util.Set<CondicionFiscalizada> condiciones);
}
