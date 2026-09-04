package kamayuk.rentas.valores.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Los movimientos de un valor hacia coactiva.
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2), y no hay ninguno que actualice ni que borre:
 * un movimiento equivocado se corrige con otro movimiento.
 */
public interface MovimientoDeValorRepository {

    /**
     * Registra el movimiento, o devuelve el que ya existia.
     *
     * <p><b>Es la idempotencia del AC de #39, y por eso devuelve el existente en vez de fallar:</b>
     * pasar dos veces el mismo valor a coactiva no puede crear dos expedientes. La garantia la da
     * el indice unico parcial de V28 —{@code ON CONFLICT} sobre el, no un {@code SELECT} previo—,
     * porque dos peticiones simultaneas pasan las dos por cualquier comprobacion escrita en Java.
     *
     * @return el movimiento guardado, o el que ya estaba si este valor ya se habia pasado
     */
    MovimientoDeValor registrarPase(MovimientoDeValor movimiento);

    /**
     * Registra la respuesta de coactiva al pase: {@link TipoDeMovimiento#ACO} o {@link
     * TipoDeMovimiento#RCO} (#40).
     *
     * <p><b>No es idempotente, y no puede serlo:</b> el indice unico de V28 es <b>parcial</b> sobre
     * {@code tipo = 'PCO'} a proposito. Un valor tiene un solo pase, pero puede ser rechazado,
     * vuelto a pasar y aceptado despues, y cada uno de esos actos es una fila. Quien llama decide
     * si repetirlo tiene sentido; lo que no puede es editar el anterior.
     *
     * @throws IllegalArgumentException si el movimiento es un {@code PCO}: ese va por {@link
     *     #registrarPase}, que es el que la base serializa
     */
    MovimientoDeValor registrarRespuesta(MovimientoDeValor movimiento);

    /** El pase a coactiva de este valor, si ya se dio. */
    Optional<MovimientoDeValor> paseDe(long valorId);

    /**
     * De entre esos valores, cuales tienen su pase a coactiva ({@code PCO}), en una sola consulta
     * (#53).
     *
     * <p>Existe para el padron de papeletas enviadas a coactiva. Con {@link #paseDe} en un bucle,
     * una pagina de doscientas filas serian doscientas consultas, y ese es el camino por el que un
     * padron acaba tardando segundos por pagina.
     *
     * @return los identificadores con pase, de entre los preguntados; vacio si la coleccion lo esta
     */
    java.util.Set<Long> conPaseACoactiva(java.util.Collection<Long> valorIds);

    /** Todos los movimientos del valor, del primero al ultimo. */
    List<MovimientoDeValor> deValor(long valorId);
}
