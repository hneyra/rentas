package kamayuk.rentas.web;

import java.util.List;
import java.util.function.Function;
import kamayuk.rentas.compartido.Pagina;

/**
 * La forma en que un listado sale por HTTP. Una sola, para las 134 pantallas.
 *
 * <p>Lleva el total y el numero de paginas aunque se puedan deducir: sin ellos la interfaz no puede
 * dibujar «1 de 47» y acaba pidiendo la pagina siguiente para saber si existe, que con padrones de
 * decenas de miles de contribuyentes es una consulta de mas por cada pagina que alguien mira.
 */
public record RespuestaPaginada<T>(
        List<T> contenido,
        int pagina,
        int tamano,
        long totalElementos,
        int totalPaginas,
        boolean hayMas) {

    public static <T> RespuestaPaginada<T> de(Pagina<T> pagina) {
        return new RespuestaPaginada<>(
                pagina.contenido(),
                pagina.pagina(),
                pagina.tamano(),
                pagina.totalElementos(),
                pagina.totalPaginas(),
                pagina.hayMas());
    }

    /** Traduce el contenido del modelo a su DTO sin recalcular la paginacion. */
    public static <T, R> RespuestaPaginada<R> de(
            Pagina<T> pagina, Function<? super T, ? extends R> aDto) {
        return de(pagina.mapear(aDto));
    }
}
