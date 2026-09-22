package kamayuk.rentas.coactiva.infraestructura.web;

import java.util.List;
import java.util.function.Function;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeDeudasCoactivas.PaginaDeDeudas;

/**
 * La forma en que salen las <b>dos</b> consultas de deuda coactiva, y no es {@code
 * RespuestaPaginada} (#307).
 *
 * <h2>Que cambia, y por que se ve en el contrato</h2>
 *
 * <p>Donde toda relacion paginada de esta API publica {@code totalElementos} —«las filas que
 * devolveria la consulta sin paginar»—, estas dos publican {@code expedientesDelCriterio}, que es
 * otra cosa: los expedientes que la base conto <b>antes</b> de que la consulta descartara los que
 * no tienen nada que cobrar. Las dos cifras se separan, y cuanto: la pagina puede traer diecisiete
 * filas con el recuento diciendo 1 184.
 *
 * <p>Publicarlo con el nombre de siempre dejaba a la grilla escribiendo «20 de 1 184» sobre
 * diecisiete filas y repartiendo paginas cortas sin motivo visible —el defecto que #25 midio en
 * {@code consulta_valores}—, y con una particularidad que lo hace peor: <b>no produce ningun
 * sintoma</b>. Sale un numero, de la forma correcta, en el sitio correcto, y coincide con el bueno
 * siempre que ningun expediente de la pagina este pagado, que es justo el caso en que se prueba a
 * mano.
 *
 * <p>El campo cambia de nombre y no solo de javadoc porque <b>un cliente no lee javadoc</b>: lee
 * {@code docs/50-api/formas-de-la-api.json}. Ahi es donde tiene que verse, y ahi se ve —{@code
 * FormasDeLaApiTest} lo deriva de este record—. Del otro lado, {@code
 * el-total-es-el-que-publica-la-operacion.test.ts} obliga a que el total de una tabla salga del
 * envoltorio tal cual: con el nombre de siempre, la interfaz habria podido copiarlo sin que ninguna
 * guarda dijera nada.
 *
 * <h2>Lo que NO cambia: la paginacion</h2>
 *
 * <p>{@code totalPaginas} y {@code hayMas} siguen saliendo de este recuento, y es lo correcto: lo
 * que se reparte en paginas son los expedientes del criterio. Derivarlos de las filas devueltas
 * diria «no hay pagina siguiente» justo cuando la hay.
 *
 * <p>Por que no se ajusta el recuento a las filas —que habria sido mejor— esta medido en {@link
 * PaginaDeDeudas}: en SQL exigiria nombrar tablas de otros dos contextos y transcribir {@code
 * CalculoDeDeuda} a un {@code WHERE}; en Java, una lectura del libro por expediente de la cartera
 * entera y en cada pagina que alguien mire.
 *
 * @param contenido las filas de esta pagina, ya sin las que no tenian nada que cobrar
 * @param pagina cual es, contada desde 0
 * @param tamano cuantas filas se pidieron, no cuantas vinieron
 * @param expedientesDelCriterio cuantos expedientes cumplen el criterio; <b>no</b> cuantas filas
 *     devuelve la consulta, y por eso no se llama {@code totalElementos}
 * @param totalPaginas en cuantas paginas se reparte ese recuento
 * @param hayMas si existe una pagina siguiente
 */
public record RespuestaDeDeudasCoactivas<T>(
        List<T> contenido,
        int pagina,
        int tamano,
        long expedientesDelCriterio,
        int totalPaginas,
        boolean hayMas) {

    /** Traduce el contenido del modelo a su DTO sin recalcular la paginacion. */
    public static <T, R> RespuestaDeDeudasCoactivas<R> de(
            PaginaDeDeudas<T> pagina, Function<? super T, ? extends R> aDto) {
        PaginaDeDeudas<R> traducida = pagina.mapear(aDto);
        return new RespuestaDeDeudasCoactivas<>(
                traducida.contenido(),
                traducida.pagina(),
                traducida.tamano(),
                traducida.expedientesDelCriterio(),
                traducida.totalPaginas(),
                traducida.hayMas());
    }
}
