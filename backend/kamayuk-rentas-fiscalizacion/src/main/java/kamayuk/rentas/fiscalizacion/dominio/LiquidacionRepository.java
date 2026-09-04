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
 * sgtm_app}, y el escáner del código fuente lo vigila además en {@code TABLAS_INMUTABLES}: la
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

    /** Las liquidaciones que se le hicieron a un contribuyente, para su estado de cuenta. */
    List<Liquidacion> deContribuyente(long contribuyenteId);
}
