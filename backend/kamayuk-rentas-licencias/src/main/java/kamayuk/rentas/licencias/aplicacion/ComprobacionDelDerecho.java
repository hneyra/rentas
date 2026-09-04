package kamayuk.rentas.licencias.aplicacion;

import java.util.Optional;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import kamayuk.rentas.tesoreria.RecibosDeTramite;

/**
 * «Sin el pago del derecho no se emite» (RF-110), escrito una sola vez (#44).
 *
 * <p>Es la mitad del primer criterio de aceptacion de #44 que la base no puede expresar. La otra
 * mitad —que haya un recibo— la expresa V37 poniendo {@code recibo_id NOT NULL} en {@code
 * licencia_funcionamiento} y en {@code licencia_duplicado}. Lo que exige un {@code JOIN} y por
 * tanto vive aqui son las cuatro condiciones restantes:
 *
 * <ol>
 *   <li>que el recibo <b>exista</b> en esta municipalidad —lo filtra RLS, no un {@code WHERE}—;
 *   <li>que sea de <b>caja de tasas</b>: un derecho de tramite no es deuda tributaria y no se cobra
 *       en caja tributaria (ver {@code CobrarTasa});
 *   <li>que <b>no este anulado</b> (#34): un recibo anulado devolvio la plata y no paga nada;
 *   <li>que sea del <b>mismo titular</b> y cubra el <b>concepto del TUPA</b> que el conjunto
 *       sellado nombra como derecho de este tramite.
 * </ol>
 *
 * <p>Las cuatro se comprueban por separado y con mensajes distintos porque las cuatro se arreglan
 * de maneras distintas: teclear otro numero, cobrar en la caja que toca, emitir un recibo nuevo, o
 * cobrarle al titular el concepto correcto. Un unico «recibo no valido» dejaria a quien opera
 * adivinando cual de las cuatro le falta.
 *
 * <p>Todo pasa por {@link RecibosDeTramite}, la API publica de {@code tesoreria}. Nunca por su
 * tabla: es lo que el AC pide, y lo que Spring Modulith verifica.
 */
public final class ComprobacionDelDerecho {

    private ComprobacionDelDerecho() {}

    /**
     * Comprueba el recibo y lo devuelve.
     *
     * @param recibos la API publica de tesoreria
     * @param numeroImpreso el numero tal como esta en el papel que trajo el administrado
     * @param contribuyenteId el titular al que se le emite
     * @param conceptoExigido el codigo del TUPA que el conjunto sellado nombra para este tramite
     * @param tramite como se llama el tramite en el mensaje de error
     * @throws DerechoNoPagado si alguna de las cuatro condiciones falla
     */
    static ReciboDeTramite exigir(
            RecibosDeTramite recibos,
            String numeroImpreso,
            long contribuyenteId,
            String conceptoExigido,
            String tramite) {

        String numero = numeroImpreso == null ? "" : numeroImpreso.strip();
        if (numero.isEmpty()) {
            throw new DerechoNoPagado(
                    "El "
                            + tramite
                            + " no se emite sin el recibo del derecho de tramite: hay que indicar"
                            + " el numero del recibo de caja de tasas (RF-110)");
        }

        Optional<ReciboDeTramite> encontrado = recibos.porNumeroImpreso(numero);
        ReciboDeTramite recibo =
                encontrado.orElseThrow(
                        () ->
                                new DerechoNoPagado(
                                        "No hay ningun recibo "
                                                + numero
                                                + " en esta municipalidad, asi que no respalda el"
                                                + " pago del derecho del "
                                                + tramite));

        if (!recibo.esDeTasas()) {
            throw new DerechoNoPagado(
                    "El recibo "
                            + numero
                            + " no es de caja de tasas. Un derecho de tramite no es deuda"
                            + " tributaria: se cobra en la caja de tasas, y un recibo de cobranza"
                            + " no lo documenta");
        }
        if (recibo.anulado()) {
            throw new DerechoNoPagado(
                    "El recibo "
                            + numero
                            + " esta anulado: la plata se devolvio, asi que no paga el derecho del "
                            + tramite);
        }
        if (recibo.contribuyenteId() != contribuyenteId) {
            throw new DerechoNoPagado(
                    "El recibo "
                            + numero
                            + " se le cobro a otro contribuyente. El derecho del "
                            + tramite
                            + " lo paga su titular");
        }
        if (!recibo.cubre(conceptoExigido)) {
            throw new DerechoNoPagado(
                    "El recibo "
                            + numero
                            + " no cobra el concepto "
                            + conceptoExigido
                            + ", que es el derecho de tramite del "
                            + tramite
                            + " segun el TUPA vigente. Cobra: "
                            + String.join(", ", recibo.conceptos()));
        }
        return recibo;
    }

    /**
     * El recibo presentado no respalda el pago del derecho de tramite (RF-110).
     *
     * <p>Publica, aunque la comprobacion no lo sea: quien la traduce a un 422 es la capa web, que
     * esta en otro paquete.
     */
    public static final class DerechoNoPagado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        DerechoNoPagado(String mensaje) {
            super(mensaje);
        }
    }
}
