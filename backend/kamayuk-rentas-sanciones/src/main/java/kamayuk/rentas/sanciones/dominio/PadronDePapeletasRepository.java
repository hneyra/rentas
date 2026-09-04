package kamayuk.rentas.sanciones.dominio;

import java.util.List;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * Los padrones, los records y los resúmenes de papeletas contra PostgreSQL (#53, RF-068, RF-073,
 * RF-074). Ningún método recibe la municipalidad (regla 2).
 *
 * <h2>Solo lee</h2>
 *
 * <p>No hay ni un método que escriba. Las papeletas las registra {@code PapeletaRepository} y los
 * valores los emite {@code valores}; esto es la mitad de lectura de {@code sanciones}, y separarla
 * es lo que permite que sus consultas crucen tres tablas sin que el repositorio del agregado
 * aprenda a hacerlo.
 *
 * <h2>Ningún método devuelve el padrón entero</h2>
 *
 * <p>Es el quinto criterio de aceptación de #53 escrito en la firma: no existe un {@code
 * List&lt;PapeletaDelPadron&gt; todas(criterio)}. Se lee por página —{@link #buscar}, para la
 * pantalla— o por cursor acotado —{@link #siguientes}, para el documento—, y las dos formas llevan
 * un tope de filas. Un método que devolviera la lista completa haría que el primer padrón de
 * cuarenta mil papeletas se llevara por delante la memoria del proceso, y no habría manera de
 * saberlo antes de que pasara.
 *
 * <p>El resumen no necesita cursor: {@link #resumir} devuelve una línea por grupo y la agregación
 * la hace el motor. Traerse las papeletas para contarlas en Java sería exactamente lo que ese
 * criterio prohíbe.
 */
public interface PadronDePapeletasRepository {

    /** Una página del padrón, para la grilla. */
    Pagina<PapeletaDelPadron> buscar(CriterioDePadron criterio, Paginacion paginacion);

    /**
     * El siguiente lote del padrón, por identificador de papeleta.
     *
     * <p>Con cursor y no con {@code OFFSET}: un {@code OFFSET} creciente vuelve a recorrer lo ya
     * leído en cada lote, así que emitir un padrón de cuarenta mil filas de doscientas en
     * doscientas le costaría al motor cuatro millones de filas recorridas.
     *
     * @param despuesDe el último identificador ya leído; 0 para empezar
     * @param cuantos tope de filas del lote
     */
    List<PapeletaDelPadron> siguientes(CriterioDePadron criterio, long despuesDe, int cuantos);

    /**
     * Las líneas del resumen, agregadas por el motor.
     *
     * @return una línea por grupo con papeletas, en el orden de la clave; vacía si no hay ninguna
     */
    List<LineaDelResumen> resumir(CriterioDePadron criterio, AgrupacionDelResumen agrupacion);

    /**
     * Cuántas papeletas cumplen el criterio y cuánto suman, <b>sin traerse ninguna</b> (#549).
     *
     * <p>Corre el <b>mismo</b> {@code FROM} y el <b>mismo</b> {@code WHERE} que {@link #buscar}: es
     * el {@code count(*)} que esa página ya ejecuta, con un {@code sum(...)} al lado y sin la
     * consulta de filas. Que sea la misma expresión y no otra es lo que impide que el recuento de
     * la pantalla de aterrizaje y el pie del padrón digan cosas distintas de la misma población (AC
     * 2.4 de #549); si alguien cambia {@code donde(...)}, las dos cifras cambian juntas.
     */
    RecuentoDelPadron contar(CriterioDePadron criterio);
}
