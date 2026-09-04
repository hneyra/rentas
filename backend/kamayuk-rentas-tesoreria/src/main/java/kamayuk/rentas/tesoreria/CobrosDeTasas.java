package kamayuk.rentas.tesoreria;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Acredita, contra la caja, que un recibo cobro un concepto del TUPA y sigue vigente (#50, RF-064).
 *
 * <p>Es la segunda API publica de este modulo —tras {@link ConveniosDelContribuyente}— y vive en el
 * paquete raiz por el mismo motivo: Spring Modulith trata como interno todo lo que esta en un
 * subpaquete, asi que esto es exactamente lo que {@code sanciones} puede ver de {@code tesoreria}.
 * Sus tablas, no.
 *
 * <h2>Por que existe</h2>
 *
 * <p>Un vehiculo internado no sale del deposito sin pagar la custodia (AC de #50). Esa comprobacion
 * <b>no</b> se puede hacer en {@code sanciones}: el recibo vive en {@code tesoreria}, y la unica
 * forma de que la respuesta sea verdad es preguntarselo a quien lo emitio. La alternativa —que la
 * pantalla marque una casilla «Custodia cancelada», como hace el prototipo— convierte el requisito
 * en un adorno: quien libera el vehiculo es quien marca la casilla.
 *
 * <h2>«Sigue vigente» es la palabra que importa</h2>
 *
 * <p>Un recibo <b>anulado</b> conserva sus filas —no se borran, se reversan (#34)— y devolver que
 * cobro seria acreditar un pago que ya no vale. Ese conocimiento se queda aqui: quien pregunta no
 * tiene que saber que la anulacion se registra como un movimiento del recibo.
 */
public interface CobrosDeTasas {

    /**
     * Que cobro ese recibo por ese concepto, si lo cobro y sigue vigente.
     *
     * @param numeroDeRecibo como esta impreso en el papel, {@code 001-0000123}
     * @param codigoDeTasa el concepto del TUPA que se quiere acreditar
     * @return la constancia, o vacio si el recibo no existe, no cobro ese concepto o esta anulado
     */
    Optional<TasaCobrada> acreditar(String numeroDeRecibo, String codigoDeTasa);

    /**
     * Lo que la caja recaudo por ese concepto del TUPA entre esos dos dias (#54, RF-115).
     *
     * <h2>Por que es un agregado y no una lista de recibos</h2>
     *
     * <p>El resumen anual de licencias necesita, para cada año, la recaudacion por el derecho de
     * tramite. La alternativa —pedirle a {@code licencias} los identificadores de los recibos de
     * sus licencias del año y pasarlos aqui— haria viajar miles de identificadores por la frontera
     * del modulo para obtener una sola cifra, y ademas obligaria al consumidor a saber que un
     * recibo anulado no cuenta, que es exactamente lo que este puerto existe para no exigir.
     *
     * <p>Se reutiliza el agregado que #36 ya escribio para el avance de recaudacion: el rango se
     * aplica sobre la <b>fecha del turno</b>, y lo anulado se resta en lugar de excluirse.
     *
     * <h2>Que es y que no es esta cifra</h2>
     *
     * <p>Es lo que la <b>ventanilla cobro</b> por ese concepto en el rango, y no «lo que costaron
     * las licencias emitidas en el». Los dos numeros pueden diferir legitimamente: un derecho
     * pagado el 28 de diciembre para una licencia emitida en enero cuenta en el año del cobro.
     * Quien publica la cifra lo dice; inventar la otra —cruzando recibos con licencias— produciria
     * un total que no cuadra con ningun arqueo.
     *
     * @param codigoDeTasa el concepto del TUPA que se suma
     * @param desde primer dia del rango, inclusive; es fecha de turno de caja
     * @param hasta ultimo dia del rango, inclusive
     */
    RecaudacionDeTasa recaudado(String codigoDeTasa, LocalDate desde, LocalDate hasta);
}
