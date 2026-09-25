package kamayuk.rentas.licencias.aplicacion;

import kamayuk.rentas.tesoreria.AplicacionDeRecibos;
import kamayuk.rentas.tesoreria.ReciboDeTramite;

/**
 * El recibo del derecho de tramite se <b>gasta</b> al emitir el acto que paga (#383, RF-110).
 *
 * <p>Es la segunda mitad de {@link ComprobacionDelDerecho}, y a proposito no esta dentro de ella.
 * Aquella contesta «¿este recibo vale para este tramite?», que es verdad de {@code caja}; esta
 * anota «este acto lo gasto», que solo lo sabe quien emite el acto. Hasta #383 solo existia la
 * primera, y un mismo recibo respaldaba licencias, duplicados, edificaciones y revalidaciones sin
 * limite: las cinco comprobaciones pasaban todas las veces.
 *
 * <h2>Una unidad de una, y por que</h2>
 *
 * <p>{@link ReciboDeTramite} no publica cuantas unidades cobro el recibo por el concepto —solo la
 * lista de conceptos—, asi que la licencia, el duplicado, la edificacion y la revalidacion consumen
 * <b>1 de 1</b>: un recibo, un acto. Es la lectura conservadora, y el dia que {@code caja} publique
 * la cantidad cambia aqui y en ningun caso de uso. El certificado no pasa por aqui: su cantidad si
 * la da {@code CobrosDeTasas.acreditar}, y la usa.
 *
 * <p>Se llama <b>despues</b> de escribir el acto y en su misma transaccion: el acto necesita su
 * identificador para quedar anotado, y un rechazo deshace el acto entero.
 */
final class GastoDelDerecho {

    private GastoDelDerecho() {}

    /**
     * Anota que el acto gasto el recibo.
     *
     * @throws kamayuk.rentas.tesoreria.ReciboYaAplicado si el recibo ya respaldo otro acto
     */
    static void gastar(
            AplicacionDeRecibos aplicaciones,
            ReciboDeTramite recibo,
            String concepto,
            String tabla,
            long actoId) {
        aplicaciones.aplicar(
                recibo.numero(), concepto, 1, 1, new AplicacionDeRecibos.Acto(tabla, actoId));
    }
}
