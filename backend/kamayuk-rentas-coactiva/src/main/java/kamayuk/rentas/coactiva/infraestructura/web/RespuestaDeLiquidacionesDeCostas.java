package kamayuk.rentas.coactiva.infraestructura.web;

import java.util.List;
import java.util.function.Function;
import kamayuk.rentas.coactiva.aplicacion.PaginaDescartadaTrasPaginar;

/**
 * La forma en que sale {@code GET /coactiva/liquidaciones-costas}, y no es {@code
 * RespuestaPaginada} (#425).
 *
 * <p>Es el defecto de #307 en la consulta que #307 no toco. Con {@code estado}, la consulta
 * descarta las liquidaciones que no lo tienen <b>despues</b> de paginar —el estado sale de lo
 * pendiente en el libro de {@code cuentacorriente}, que es otro contexto—, y publicaba como {@code
 * totalElementos} el recuento de antes del descarte: la grilla decia «2 de 45» sobre dos filas. El
 * recuento es correcto para repartir paginas; lo que estaba mal era el nombre. Aqui se llama {@code
 * liquidacionesDelCriterio}, que es lo que es, igual que {@link RespuestaDeDeudasCoactivas} publica
 * {@code expedientesDelCriterio}.
 *
 * @param contenido las liquidaciones de esta pagina, ya sin las que no tienen el estado pedido
 * @param pagina cual es, contada desde 0
 * @param tamano cuantas filas se pidieron, no cuantas vinieron
 * @param liquidacionesDelCriterio cuantas liquidaciones cumplen el criterio, sin mirar el estado;
 *     <b>no</b> cuantas filas devuelve la consulta, y por eso no se llama {@code totalElementos}
 * @param totalPaginas en cuantas paginas se reparte ese recuento
 * @param hayMas si existe una pagina siguiente
 */
public record RespuestaDeLiquidacionesDeCostas<T>(
        List<T> contenido,
        int pagina,
        int tamano,
        long liquidacionesDelCriterio,
        int totalPaginas,
        boolean hayMas) {

    /** Traduce el contenido del modelo a su DTO sin recalcular la paginacion. */
    public static <T, R> RespuestaDeLiquidacionesDeCostas<R> de(
            PaginaDescartadaTrasPaginar<T> pagina, Function<? super T, ? extends R> aDto) {
        PaginaDescartadaTrasPaginar<R> traducida = pagina.mapear(aDto);
        return new RespuestaDeLiquidacionesDeCostas<>(
                traducida.contenido(),
                traducida.pagina(),
                traducida.tamano(),
                traducida.delCriterio(),
                traducida.totalPaginas(),
                traducida.hayMas());
    }
}
