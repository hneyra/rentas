package kamayuk.rentas.coactiva.aplicacion;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Una pagina cuyas filas se descartaron <b>despues</b> de paginar, y el recuento del criterio sobre
 * el que se reparte (#307, #425).
 *
 * <h2>Por que no es una {@code Pagina}</h2>
 *
 * <p>{@code Pagina.totalElementos()} promete «las filas que devolveria la consulta sin paginar», y
 * aqui esa promesa es <b>falsa</b>: la base pagina y cuenta el criterio, y la consulta descarta
 * despues las filas que no cumplen algo que solo sabe otro contexto —la deuda viva del expediente,
 * que es de {@code valores} y {@code cuentacorriente}; el estado de la liquidacion de costas, que
 * sale de lo pendiente en el libro—. El numero que la base conto es el del criterio, y las filas
 * que salen son menos. Publicarlo como «total de elementos» deja a la grilla diciendo «2 de 45»
 * sobre dos filas y repartiendo paginas cortas sin motivo visible — el defecto que #25 midio en
 * {@code consulta_valores}, que #307 corrigio en las deudas coactivas y que #425 encontro en las
 * costas.
 *
 * <p>El tipo es la correccion, y no el javadoc: {@code RespuestaPaginada.de(...)} <b>solo acepta
 * una {@code Pagina}</b>, asi que mientras una consulta devuelva esto no hay forma de volver a
 * publicar el numero bajo el nombre que no le toca. Cada respuesta web lo publica con el nombre de
 * lo que cuenta —{@code expedientesDelCriterio}, {@code liquidacionesDelCriterio}—. Por que ese
 * recuento no se puede ajustar a las filas esta medido en {@link ConsultaDeDeudasCoactivas#deudas}.
 *
 * <p>{@link #totalPaginas()} y {@link #hayMas()} salen de este recuento, y es lo correcto: lo que
 * se reparte en paginas es el criterio. Contar las filas devueltas diria «no hay pagina siguiente»
 * justo cuando la hay.
 *
 * @param contenido las filas de esta pagina, ya descartadas las que no cumplian
 * @param pagina cual es, contada desde 0
 * @param tamano cuantas filas se pidieron, no cuantas vinieron
 * @param delCriterio cuantas cumplen el criterio de la base —que es lo que se reparte en paginas—,
 *     y <b>no</b> cuantas filas devuelve la consulta
 */
public record PaginaDescartadaTrasPaginar<T>(
        List<T> contenido, int pagina, int tamano, long delCriterio) {

    public PaginaDescartadaTrasPaginar {
        Objects.requireNonNull(contenido, "Una pagina sin filas es una lista vacia, no null");
        contenido = List.copyOf(contenido);
        if (pagina < 0) {
            throw new IllegalArgumentException("La pagina se cuenta desde 0: " + pagina);
        }
        if (tamano < 1) {
            throw new IllegalArgumentException("El tamano de pagina es al menos 1: " + tamano);
        }
        if (delCriterio < 0) {
            throw new IllegalArgumentException("El recuento no puede ser negativo: " + delCriterio);
        }
    }

    /** Sobre el recuento del criterio, que es lo que se reparte. */
    public int totalPaginas() {
        return delCriterio == 0 ? 0 : (int) ((delCriterio - 1) / tamano + 1);
    }

    public boolean hayMas() {
        return pagina + 1 < totalPaginas();
    }

    /** La misma pagina con el contenido traducido. Es lo que hace la capa web con sus DTO. */
    public <R> PaginaDescartadaTrasPaginar<R> mapear(Function<? super T, ? extends R> traduccion) {
        return new PaginaDescartadaTrasPaginar<>(
                contenido.stream().<R>map(traduccion).toList(), pagina, tamano, delCriterio);
    }
}
