package kamayuk.rentas.tesoreria;

import java.util.Objects;

/**
 * Anota que un acto <b>gasto</b> un recibo de caja de tasas, y rechaza el que ya no tiene saldo
 * (#383, RF-110).
 *
 * <h2>Por que existe, y por que no es una sexta comprobacion</h2>
 *
 * <p>Hasta #383 un recibo servia de prueba de pago <b>sin quedar consumido</b>: con un solo pago
 * salian N licencias, N certificados, N duplicados, N revalidaciones y N liberaciones del deposito.
 * Quien lo comprobaba preguntaba cinco cosas y ninguna era «¿ya se uso?».
 *
 * <p>Son dos preguntas con dos duenos distintos, y por eso son dos colaboradores. «¿Este recibo
 * vale para este tramite?» es verdad de {@code caja}, y se lee por {@link RecibosDeTramite} y
 * {@link CobrosDeTasas}, como siempre. «¿Ya se gasto?» <b>solo lo sabe este sistema</b>: {@code
 * caja} no sabe que acto uso que recibo, y el registro solo lo puede llevar quien emite los actos.
 * Meterla en {@code ComprobacionDelDerecho} mezclaria las dos, y la de la custodia —que no pasa por
 * ahi— se habria quedado fuera.
 *
 * <h2>Vive en {@code tesoreria} y es un puerto, a proposito</h2>
 *
 * <p>Es la primera escritura publicada de este paquete, y no abre un segundo camino a la caja: no
 * cobra, no anula y no toca un recibo; anota en <b>esta</b> base ({@code recibo_aplicado}, V28) que
 * un acto lo gasto. Es un puerto, y no una tabla que cada modulo escriba, porque <b>invierte la
 * dependencia</b>: si manana {@code caja} ofrece consumir el recibo, cambia el adaptador y no los
 * seis casos de uso que lo llaman.
 *
 * <h2>Cuenta unidades, no actos</h2>
 *
 * <p>Un recibo puede cobrar varias unidades del mismo concepto —{@link TasaCobrada#cantidad}; en la
 * custodia, los dias— y un recibo que cobro dos certificados respalda dos. Por eso el contrato es
 * «lo ya aplicado mas lo que este acto consume no pasa de lo cobrado», y no «un recibo, un acto».
 *
 * <h2>Lo llama la misma transaccion que escribe el acto</h2>
 *
 * <p>Asi el rechazo deshace el acto entero —papel, correlativo, fila y auditoria—, y un acto que
 * falla despues no deja el recibo gastado. Solo se inserta (regla 4): anular un acto <b>no</b>
 * libera el recibo, porque tampoco devuelve el dinero.
 */
public interface AplicacionDeRecibos {

    /**
     * Anota que {@code acto} gasto {@code unidadesQueConsume} unidades de lo que el recibo cobro
     * por ese concepto.
     *
     * @param numeroDeRecibo como la caja lo devolvio al acreditarlo, {@code 001-0000123}
     * @param concepto el concepto del TUPA que el acto consume
     * @param unidadesQueConsume las que este acto gasta; al menos una
     * @param unidadesCobradas las que el recibo cobro por ese concepto; al menos una
     * @param acto lo que el recibo pago
     * @throws ReciboYaAplicado si lo ya aplicado mas lo que este acto consume pasa de lo cobrado, o
     *     si otra transaccion gasto el mismo saldo a la vez
     */
    void aplicar(
            String numeroDeRecibo,
            String concepto,
            int unidadesQueConsume,
            int unidadesCobradas,
            Acto acto);

    /**
     * El acto que un recibo pago: su tabla y su identificador.
     *
     * <p>Es lo que permite contestar, el dia que alguien lo pregunte, «que licencia pago este
     * recibo». La tabla se nombra con su nombre en la base porque es lo que un auditor busca; un
     * enumerado aqui obligaria a {@code tesoreria} a conocer los actos de cada modulo.
     *
     * @param tabla la tabla del acto, {@code licencia_funcionamiento}
     * @param id su identificador
     */
    record Acto(String tabla, long id) {

        public Acto {
            Objects.requireNonNull(tabla, "El acto dice en que tabla esta");
            if (tabla.isBlank()) {
                throw new IllegalArgumentException("El acto dice en que tabla esta");
            }
            if (id <= 0) {
                throw new IllegalArgumentException(
                        "Se aplica un recibo a un acto ya escrito, con su identificador; llego "
                                + id);
            }
        }
    }
}
