package kamayuk.rentas.compartido;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Una pagina de resultados y el total del que sale.
 *
 * <p>El total va incluido a proposito. Sin el, la interfaz no puede decir «1 de 47» y acaba
 * pidiendo la pagina siguiente para saber si existe; con padrones de decenas de miles de
 * contribuyentes, eso es una consulta de mas por cada pagina que alguien mira.
 *
 * @param contenido las filas de esta pagina
 * @param pagina cual es, contada desde 0
 * @param tamano cuantas filas se pidieron, no cuantas vinieron
 * @param totalElementos filas que devolveria la consulta sin paginar
 */
public record Pagina<T>(List<T> contenido, int pagina, int tamano, long totalElementos) {

    public Pagina {
        Objects.requireNonNull(contenido, "Una pagina sin contenido es una lista vacia, no null");
        contenido = List.copyOf(contenido);
        if (pagina < 0) {
            throw new IllegalArgumentException("La pagina se cuenta desde 0: " + pagina);
        }
        if (tamano < 1) {
            throw new IllegalArgumentException("El tamano de pagina es al menos 1: " + tamano);
        }
        if (totalElementos < 0) {
            throw new IllegalArgumentException("El total no puede ser negativo: " + totalElementos);
        }
    }

    public static <T> Pagina<T> de(List<T> contenido, Paginacion paginacion, long totalElementos) {
        return new Pagina<>(contenido, paginacion.pagina(), paginacion.tamano(), totalElementos);
    }

    public static <T> Pagina<T> vacia(Paginacion paginacion) {
        return new Pagina<>(List.of(), paginacion.pagina(), paginacion.tamano(), 0);
    }

    public int totalPaginas() {
        return totalElementos == 0 ? 0 : (int) ((totalElementos - 1) / tamano + 1);
    }

    public boolean hayMas() {
        return pagina + 1 < totalPaginas();
    }

    public boolean estaVacia() {
        return contenido.isEmpty();
    }

    /** La misma pagina con el contenido traducido. Es lo que hace la capa web con sus DTO. */
    public <R> Pagina<R> mapear(Function<? super T, ? extends R> traduccion) {
        List<R> traducido = contenido.stream().<R>map(traduccion).toList();
        return new Pagina<>(traducido, pagina, tamano, totalElementos);
    }
}
