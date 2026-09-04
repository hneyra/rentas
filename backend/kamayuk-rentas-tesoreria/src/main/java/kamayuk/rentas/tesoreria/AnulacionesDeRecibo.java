package kamayuk.rentas.tesoreria;

/**
 * Si un recibo esta anulado, preguntado a la caja (P5D, ADR-0026).
 *
 * <h2>Por que existe, y por que aparece justo ahora</h2>
 *
 * <p>Hasta P5D esto no era un puerto: {@code CerrarConvenio} inyectaba {@code
 * MovimientoDeReciboRepository} y leia {@code recibo_movimiento} de esta misma base. `V7` retiro
 * esa tabla —el recibo vive en {@code caja}— y el convenio se quedo (ADR-0026 §5), asi que la
 * pregunta sigue haciendo falta y ya no se puede contestar leyendo. Un puerto es lo unico que
 * queda.
 *
 * <p>Vive en el paquete raiz, con {@link RecibosDeTramite}, {@link CobrosDeTasas} y {@link
 * AvanceDeCaja}, aunque hoy <b>solo lo pregunte una clase de este modulo</b>: los cuatro son la
 * misma cosa —lo que {@code rentas} le pregunta a {@code caja}— y tenerlos juntos es lo que hace
 * que la frontera se vea de un vistazo. Repartirlos por quien pregunta la escondería.
 *
 * <h2>Por que NO se resuelve con {@link RecibosDeTramite}</h2>
 *
 * <p>Seria lo natural —{@code ReciboDeTramite} ya publica {@code anulado}— y no se puede: ese
 * puerto pregunta <b>por el numero impreso</b> y {@code convenio_movimiento} guarda el {@code
 * recibo_id} interno, no el numero. Resolver uno desde el otro exigiria una ruta que {@code caja}
 * no publica, que es exactamente lo que el adaptador de este puerto declara.
 *
 * <p>Ensanchar {@link RecibosDeTramite} con un {@code porId} tampoco: ese puerto es el contrato de
 * {@code licencias} desde #44 y sus diez sitios de llamada no tienen por que enterarse de un
 * problema del convenio.
 */
public interface AnulacionesDeRecibo {

    /**
     * Si ese recibo tiene su anulacion registrada.
     *
     * <p><b>No devuelve {@code false} cuando no se puede preguntar</b>, y ahi esta todo: un {@code
     * false} significa «ese recibo sigue vigente», que es la respuesta que <b>impide</b> anular el
     * convenio, y un {@code true} inventado dejaria anular un convenio cuya cuota inicial se cobro
     * y sigue cobrada — dinero recibido por un acto que ya no existe, que es literalmente lo que la
     * guarda de {@code CerrarConvenio} existe para impedir y lo que ningun arqueo detecta. Cuando
     * no hay como preguntarlo, se lanza.
     *
     * @param reciboId el identificador interno del recibo <b>en {@code caja}</b>, tal como lo
     *     guardo {@code convenio_movimiento.recibo_id} al formalizar. Desde `V7` no hay clave
     *     foranea que garantice que exista (ADR-0026 §3)
     */
    boolean estaAnulado(long reciboId);
}
