package kamayuk.rentas.web;

import kamayuk.rentas.compartido.Paginacion;
import org.jspecify.annotations.Nullable;

/**
 * Los parametros de consulta que gobiernan un listado, con sus valores por omision.
 *
 * <p>Un solo dialecto para las 134 pantallas: {@code ?pagina=0&tamano=20&ordenarPor=codigo&
 * sentido=ASCENDENTE}. La pagina se cuenta desde 0, como en SQL.
 *
 * <p>El campo de ordenacion no se valida aqui sino en el repositorio, contra la lista blanca de esa
 * consulta: cual sea admisible depende de la tabla, no del transporte. Lo que si hace este tipo es
 * garantizar que <b>siempre</b> hay un orden, porque sin {@code ORDER BY} el motor no promete
 * ninguno y dos paginas consecutivas pueden repetir una fila y omitir otra.
 *
 * <h2>Se llama {@code sentido} y no {@code direccion}, y eso costo dos issues (#226, #236)</h2>
 *
 * <p>Hasta #236 este componente se llamaba {@code direccion}, y esa palabra <b>ya estaba ocupada
 * por el dominio</b>: el domicilio de un contribuyente, de un establecimiento, de un anuncio, de un
 * predio. Como {@link GuardiaDeParametros#DIALECTO_DE_LA_PAGINACION} admite estos nombres en
 * <b>toda</b> operacion, cualquier pantalla con un filtro «Dirección» chocaba con el, y Spring
 * ataba el mismo parametro de consulta a los dos argumentos del mismo metodo: {@code
 * ?ordenarPor=numero&direccion=DESCENDENTE} acotaba el padron a las licencias cuya direccion
 * contiene «DESCENDENTE» —o sea a ninguna— y ademas ordenaba al reves; y mandar una direccion de
 * verdad era un 400 de enlace, porque el mismo texto tenia que convertirse a {@link
 * Paginacion.Direccion}. <b>El filtro no se podia usar.</b>
 *
 * <p>#226 cedio por el lado del filtro, en las dos operaciones donde el choque existia: {@code
 * direccionDelEstablecimiento} y {@code direccionDelAnuncio}. #236 midio que ceder por ese lado no
 * escala —{@code direccion} es la palabra del dominio en decenas de columnas y criterios, y cada
 * pantalla nueva con un domicilio vuelve a chocar— mientras que el sentido del orden vive en
 * <b>un</b> sitio: este componente y esa constante. Asi que el que se aparta es el sentido.
 *
 * <p>Y el nombre no se invento: {@code OrdenDeLaTabla.sentidoEnLaRuta}, de {@code @kamayuk/ui}, ya
 * lo llamaba asi. Desde #236 el frontend y el backend dicen la misma palabra, y una guarda lo cruza
 * —{@code _dialectoDeLaPaginacion} de {@code docs/50-api/parametros-de-la-api.json}, que sale de la
 * constante del guardia, contra {@code EN_LA_RUTA} de {@code frontend/src/pantallas/tablas.ts}—.
 *
 * <p><b>Lo que NO se renombro es el tipo</b>, {@link Paginacion.Direccion}: no viaja por la URL, lo
 * sujeta el compilador en los 28 archivos que lo nombran, y mezclarlo aqui habria enterrado el
 * cambio del contrato bajo 85 renombres mecanicos. Esta abierto aparte.
 *
 * <p><b>Y no se publican los dos nombres.</b> Medido al decidirlo: el unico cliente de esta API es
 * {@code rentas-web} —{@code caja} consume {@code POST /pagos}, que no pagina; {@code catastro},
 * {@code normativa} e {@code identidad} no consumen ninguna—, y se mueve en el mismo PR. Admitir
 * ademas {@code ?direccion=} dejaria para siempre el nombre que este issue existe para liberar; sin
 * admitirlo, {@link GuardiaDeParametros} contesta <b>422 nombrandolo</b>, que es un rojo que se ve.
 *
 * @param pagina contada desde 0
 * @param tamano filas por pagina
 * @param ordenarPor campo, en {@code camelCase}; si falta, el que indique la operacion
 * @param sentido sentido del orden
 */
public record ParametrosDePaginacion(
        @Nullable Integer pagina,
        @Nullable Integer tamano,
        @Nullable String ordenarPor,
        Paginacion.@Nullable Direccion sentido) {

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
                sentido == null ? Paginacion.Direccion.ASCENDENTE : sentido);
    }
}
