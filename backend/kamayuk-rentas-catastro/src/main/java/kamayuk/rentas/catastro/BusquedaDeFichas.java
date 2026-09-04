package kamayuk.rentas.catastro;

import org.jspecify.annotations.Nullable;

/**
 * Los filtros con que otro contexto puede pedir una pagina de fichas (ADR-0015 §2, #344).
 *
 * <p>Son exactamente los cinco que declara la pantalla {@code consulta_fichas}, y ni uno mas: no
 * hay aqui un «buscar cualquier cosa», por el mismo motivo que no lo hay en {@code FiltroDeFichas}
 * —una consulta que acepta texto libre acaba haciendo {@code LIKE '%…%'} sobre todo el padron—.
 *
 * <p>{@code tipo} viaja como texto y no como enumerado porque {@code TipoFicha} es interno de
 * {@code catastro}: quien pide la pagina no tiene por que conocer el modelo del vecino, y {@link
 * FichasDelPadron} rechaza con un mensaje util un tipo que no existe.
 *
 * @param codRefCatastral prefijo del codigo de referencia catastral
 * @param contribuyente nombre del titular, resuelto contra el padron por aproximacion
 * @param manzana codigo de manzana
 * @param lote lote dentro de la manzana
 * @param tipo {@code UNICA}, {@code ECONOMICA}, {@code BIENES_COMUNES} o {@code RURAL}
 */
public record BusquedaDeFichas(
        @Nullable String codRefCatastral,
        @Nullable String contribuyente,
        @Nullable String manzana,
        @Nullable String lote,
        @Nullable String tipo,
        AcotacionPorPredio acotacion) {

    /** Los cinco filtros de la pantalla, sin acotar por predio. */
    public BusquedaDeFichas(
            @Nullable String codRefCatastral,
            @Nullable String contribuyente,
            @Nullable String manzana,
            @Nullable String lote,
            @Nullable String tipo) {
        this(codRefCatastral, contribuyente, manzana, lote, tipo, AcotacionPorPredio.ninguna());
    }

    public static BusquedaDeFichas ninguna() {
        return new BusquedaDeFichas(null, null, null, null, null);
    }

    /**
     * El mismo criterio, acotado a un conjunto de predios (#631).
     *
     * <p>Es como {@code rentas} pide «solo los que declararon» o «solo los que no» sin que catastro
     * tenga que saber que es una declaracion jurada: la acotacion es un conjunto de
     * identificadores, y quien lo compone es quien conoce el predicado.
     */
    public BusquedaDeFichas acotadaA(AcotacionPorPredio otra) {
        return new BusquedaDeFichas(codRefCatastral, contribuyente, manzana, lote, tipo, otra);
    }
}
