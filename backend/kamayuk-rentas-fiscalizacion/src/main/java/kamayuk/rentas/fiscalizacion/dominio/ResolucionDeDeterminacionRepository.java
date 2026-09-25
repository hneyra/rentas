package kamayuk.rentas.fiscalizacion.dominio;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Las transferencias a rentas con su resolucion de determinacion. Ningun metodo recibe la
 * municipalidad (regla 2).
 *
 * <p><b>No hay {@code actualizar} ni {@code eliminar}, y no es una omision.</b> {@link #registrar}
 * es el unico punto de escritura: la resolucion se notifica al contribuyente, que se lleva el
 * papel, y su cargo ya esta en el libro. Corregirla en el sitio dejaria al papel, al libro y a la
 * base diciendo tres cosas distintas. V49 no le concede {@code UPDATE} a {@code kamayuk_app}, y el
 * escaner del codigo fuente lo vigila ademas en {@code TABLAS_INMUTABLES}.
 */
public interface ResolucionDeDeterminacionRepository {

    /**
     * Registra la transferencia y su resolucion.
     *
     * @throws LiquidacionYaTransferida si esa liquidacion ya se transfirio. La detecta la base con
     *     {@code resolucion_determinacion_liquidacion_uq} (V49), no una comprobacion en Java: dos
     *     peticiones simultaneas pasan las dos por cualquier {@code if} (AC 6)
     */
    ResolucionDeDeterminacion registrar(ResolucionDeDeterminacion resolucion);

    /** La resolucion por su numero, que es el del papel. Vacio si no existe o es de otra muni. */
    Optional<ResolucionDeDeterminacion> porNumero(String numero);

    /** La transferencia de una liquidacion, si ya se transfirio. */
    Optional<ResolucionDeDeterminacion> deLiquidacion(long liquidacionId);

    /**
     * Las resoluciones vivas sobre una unidad, con el periodo de la liquidacion que las sostiene,
     * de la primera a la ultima (#462).
     *
     * <p>Devuelve la fila de la relacion y no la resolucion desnuda porque lo que {@link
     * UnidadYaDeterminada} necesita saber —que ejercicios determina cada una— es de la liquidacion,
     * y se lee en la misma consulta. Filtra por la unidad y no por el periodo: el solape lo decide
     * la regla, que es pura, y una unidad tiene un punado de resoluciones.
     *
     * <p>«Vivas» son hoy <b>todas</b>: no existe el acto que deja sin efecto una resolucion de
     * determinacion. El dia que exista, esta lectura es la que tiene que dejar fuera las que se
     * dejaron sin efecto, y es la unica.
     *
     * @param predioId la unidad, si es predial
     * @param vehiculoId la unidad, si es vehicular
     */
    List<ResolucionEnLaRelacion> vigentesSobreLaUnidad(
            @Nullable Long predioId, @Nullable Long vehiculoId);

    /**
     * Serializa, hasta que la transaccion termine, las transferencias sobre esa unidad (#462).
     *
     * <p>Es la garantia del motor que {@link UnidadYaDeterminada} necesita: dos transferencias
     * simultaneas de dos liquidaciones distintas sobre la misma unidad no chocan en ningun indice
     * —{@code resolucion_determinacion_liquidacion_uq} es por liquidacion— y pasan las dos por
     * cualquier comprobacion en Java. Con el candado tomado antes de leer, la segunda espera a que
     * la primera confirme y ya ve su resolucion.
     *
     * @param predioId la unidad, si es predial
     * @param vehiculoId la unidad, si es vehicular
     */
    void bloquearLaUnidad(@Nullable Long predioId, @Nullable Long vehiculoId);

    /**
     * Las transferencias que se le hicieron a un contribuyente, de la mas reciente a la primera.
     */
    List<ResolucionDeDeterminacion> deContribuyente(long contribuyenteId);

    /**
     * La <b>relacion</b> de resoluciones, paginada (#192).
     *
     * <p>Hasta #192 no habia ninguna: {@code ResolucionController} publicaba la resolucion por su
     * numero exacto y la transferencia que la emite, y nada mas. La pantalla que la dibuja solo se
     * podia abrir con el numero ya en la mano —no hay «la primera de la relacion» cuando no hay
     * relacion—, asi que abrirla desde el menu no ensenaba ninguna nunca.
     *
     * <p>Devuelve {@link ResolucionEnLaRelacion} y no la fila desnuda: el periodo fiscalizado y el
     * numero de la liquidacion son de la liquidacion, y una relacion que no los diga obliga a abrir
     * cada fila para saber de que es. Se leen en una consulta y no una por fila.
     *
     * <p>{@code totalElementos} cuenta <b>todas</b> las que el criterio deja pasar y no las de la
     * pagina, por lo mismo que el listado de actas: contarlo sobre la pagina daria «veinte» en toda
     * municipalidad que pase de veinte resoluciones.
     */
    kamayuk.rentas.compartido.Pagina<ResolucionEnLaRelacion> consultar(
            CriterioDeResoluciones criterio, kamayuk.rentas.compartido.Paginacion paginacion);

    /** Esa liquidacion ya se transfirio: transferirla otra vez duplicaria versiones y cargos. */
    final class LiquidacionYaTransferida extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public LiquidacionYaTransferida(long liquidacionId) {
            super(
                    "La liquidacion "
                            + liquidacionId
                            + " ya se transfirio al padron: transferirla otra vez abriria una"
                            + " segunda version de la ficha y asentaria los cargos por duplicado");
        }
    }
}
