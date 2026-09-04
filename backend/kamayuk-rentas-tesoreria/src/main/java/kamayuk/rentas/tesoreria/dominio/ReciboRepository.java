package kamayuk.rentas.tesoreria.dominio;

import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import org.jspecify.annotations.Nullable;

/**
 * Los recibos. <b>Solo se agregan</b>: no hay {@code actualizar} ni {@code borrar}, y no es un
 * olvido —V29 le retira a {@code sgtm_app} el privilegio de {@code UPDATE} y {@code DELETE} nunca
 * lo tuvo (V7)—.
 */
public interface ReciboRepository {

    /**
     * El siguiente correlativo de una serie, reservado.
     *
     * <p>Un {@code INSERT ... ON CONFLICT DO UPDATE SET ultimo = ultimo + 1} sobre {@code
     * recibo_correlativo}: una sola sentencia, que bloquea la fila del contador mientras la
     * actualiza. Nunca un {@code SELECT} seguido de un {@code UPDATE} —entre los dos cabe otra
     * cobranza, y las dos leerian el mismo numero—.
     */
    NumeroDeRecibo siguienteNumero(Caja caja);

    /** Guarda el recibo con su detalle. Devuelve el recibo con su identificador. */
    Recibo emitir(Recibo recibo, @Nullable String claveDeIdempotencia);

    /**
     * El recibo que se emitio con esa clave de idempotencia, si ya existe.
     *
     * <p>Se consulta con el turno de la caja ya bloqueado: por si sola una lectura no garantiza
     * nada —dos peticiones simultaneas no verian nada las dos—, y por eso la garantia final sigue
     * siendo {@code recibo_idempotencia_uq}. Esta consulta es lo que convierte un reenvio en una
     * respuesta correcta en vez de en un error.
     */
    Optional<Recibo> porClaveDeIdempotencia(String clave);

    /** El recibo con ese numero impreso, con su detalle. */
    Optional<Recibo> porNumero(NumeroDeRecibo numero);

    /**
     * Los recibos que pide el criterio, paginados y <b>sin su detalle</b> (#548).
     *
     * <p>Es lo que le faltaba a la ventanilla: hasta #548 un recibo solo se podia pedir por su
     * numero impreso, asi que quien perdia el papel no tenia forma de encontrarlo. La fila que
     * devuelve es {@link ReciboEnConsulta}, con el estado y los duplicados ya derivados de {@code
     * recibo_movimiento}.
     *
     * <p>Un criterio que no encuentra nada devuelve una <b>pagina vacia</b>, nunca una excepcion:
     * un contribuyente sin recibos no es un error, es una busqueda sin resultados.
     */
    Pagina<ReciboEnConsulta> buscar(CriterioDeRecibos criterio, Paginacion paginacion);
}
