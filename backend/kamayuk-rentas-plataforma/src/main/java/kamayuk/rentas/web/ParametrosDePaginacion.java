package kamayuk.rentas.web;

import kamayuk.rentas.compartido.Paginacion;
import org.jspecify.annotations.Nullable;

/**
 * Los parametros de consulta que gobiernan un listado, con sus valores por omision.
 *
 * <p>Un solo dialecto para las 134 pantallas: {@code ?pagina=0&tamano=20&ordenarPor=codigo&
 * direccion=ASCENDENTE}. La pagina se cuenta desde 0, como en SQL.
 *
 * <p>El campo de ordenacion no se valida aqui sino en el repositorio, contra la lista blanca de esa
 * consulta: cual sea admisible depende de la tabla, no del transporte. Lo que si hace este tipo es
 * garantizar que <b>siempre</b> hay un orden, porque sin {@code ORDER BY} el motor no promete
 * ninguno y dos paginas consecutivas pueden repetir una fila y omitir otra.
 *
 * @param pagina contada desde 0
 * @param tamano filas por pagina
 * @param ordenarPor campo, en {@code camelCase}; si falta, el que indique la operacion
 * @param direccion sentido del orden
 */
public record ParametrosDePaginacion(
        @Nullable Integer pagina,
        @Nullable Integer tamano,
        @Nullable String ordenarPor,
        Paginacion.@Nullable Direccion direccion) {

    private static final int PAGINA_POR_OMISION = 0;
    private static final int TAMANO_POR_OMISION = 20;

    /**
     * Convierte a {@link Paginacion}, con el orden por omision que decide la operacion.
     *
     * <p>El orden por omision lo pone quien conoce la tabla, no este tipo: el listado de vias se
     * ordena por codigo y el de contribuyentes por nombre, y una constante aqui obligaria a que
     * todos se ordenaran por lo mismo.
     */
    public Paginacion aPaginacion(String ordenPorOmision) {
        return new Paginacion(
                pagina == null ? PAGINA_POR_OMISION : pagina,
                tamano == null ? TAMANO_POR_OMISION : tamano,
                ordenarPor == null || ordenarPor.isBlank() ? ordenPorOmision : ordenarPor,
                direccion == null ? Paginacion.Direccion.ASCENDENTE : direccion);
    }
}
