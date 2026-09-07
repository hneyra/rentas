package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;

/**
 * Asienta el abono de un pago en el libro de cuenta corriente (#33, RF-080).
 *
 * <p>Es la cuarta API publica de este modulo —despues de {@link ConsultaDeDeudaPublica}, {@link
 * GeneradorDeCargos} y {@link MovimientoDeFase}—, y la que {@code GeneradorDeCargos} anunciaba sin
 * cubrir: «reversar, abonar o mover de fase son actos posteriores de otros contextos (tesoreria,
 * coactiva) que ya tienen su propio caso de uso». Este es el de tesoreria.
 *
 * <p>Vive en el paquete raiz, no en {@code .aplicacion} ni en {@code .dominio}, mismo patron que
 * las otras tres: Spring Modulith trata como interno todo lo que esta en un subpaquete, asi que
 * esto es exactamente lo que {@code tesoreria} puede ver de {@code cuentacorriente}. Sus tablas,
 * no.
 *
 * <h2>Recibe lo cobrado, y eso NO es recibir un importe</h2>
 *
 * <p>ARQ-01 §3.8: «tesoreria asienta abonos; nunca determina. Si la caja calcula deuda, el sistema
 * tiene dos verdades». <b>Sigue siendo cierto</b>, y por eso hay que decir con precision para que
 * entra {@code cobrado} desde #39: el <i>cuanto</i> lo resuelve este contexto releyendo {@code
 * deudaActualizadaA(fechaDePago)} sobre su propio libro, dentro de la misma transaccion en la que
 * asienta. Lo cobrado no decide nada — no se abona, no se reparte y no se suma a nada—: se
 * <b>compara</b>, y si no cuadra al centimo no se asienta ninguna fila.
 *
 * <p>La diferencia es la que separa un dato de entrada de una comprobacion. Un importe que
 * decidiera cuanto se extingue seria la caja calculando deuda; un importe que solo puede impedir
 * que se asiente es lo contrario: es el libro negandose a extinguir una cifra que nadie pago.
 *
 * <p>Y es tambien lo que hace imposible cobrar dos veces la misma deuda: la segunda cobranza no
 * trabaja sobre una cifra que traiga en la mano, sino sobre el libro que ya tiene dentro el abono
 * de la primera.
 */
public interface RegistroDeAbonos {

    /**
     * Cobra <b>integramente</b> las obligaciones marcadas y asienta sus abonos.
     *
     * <p>Lo que hace, en este orden y en una sola transaccion:
     *
     * <ol>
     *   <li>bloquea en la base las filas de saldo de cada obligacion marcada, en orden estable
     *       —para que dos cobranzas concurrentes con selecciones que se solapan se serialicen en
     *       vez de bloquearse mutuamente—;
     *   <li>relee la deuda de cada cuota con {@code deudaActualizadaA(fechaDePago)}, ya con el
     *       libro que la cobranza anterior dejo;
     *   <li><b>compara lo releido con {@code cobrado}, ANTES de escribir una sola fila</b>, y si no
     *       coinciden al centimo lanza {@link ImporteCobradoNoCuadra} sin asentar nada (#39);
     *   <li>asienta el cargo del reajuste y del interes <b>devengados y no asentados</b> —al
     *       cobrarlos dejan de ser una proyeccion y pasan a ser un hecho— y, contra ellos, el abono
     *       de las cuatro partes.
     * </ol>
     *
     * <p><b>No aplica descuentos.</b> El efecto de una campana de beneficio sobre el importe esta
     * bloqueado por D-02b (#33): lo que se cobra es lo que se debe. Una condonacion es un asiento
     * de {@code CONDONACION} con su motivo, y la escribira quien tenga los valores de la ordenanza
     * firmados.
     *
     * @param contribuyenteId a quien se le cobra; lo resolvio quien llama
     * @param obligaciones las marcadas en ventanilla; sin repetidas
     * @param cobrado lo que la caja cobro de verdad, tal como lo publico con el pago. <b>No se
     *     abona: se compara</b> — ver la cabecera de esta interfaz
     * @param fechaDePago la fecha a la que se relee la deuda y se imputan los asientos (regla 9)
     * @param documentoOrigen el numero del recibo que origina el abono
     * @param observacion por que se abona (regla 10)
     * @return un {@link AbonoAsentado} por obligacion que tenia deuda, en el orden recibido
     * @throws SinDeudaQueAbonar si ninguna de las obligaciones marcadas tenia deuda a esa fecha
     * @throws ImporteCobradoNoCuadra si lo que el libro extinguiria no es, al centimo, {@code
     *     cobrado}
     */
    List<AbonoAsentado> abonarPagoIntegro(
            long contribuyenteId,
            List<SeleccionDeObligacion> obligaciones,
            Dinero cobrado,
            LocalDate fechaDePago,
            String documentoOrigen,
            Observacion observacion);

    /**
     * Deshace los abonos que un documento origino, <b>asentando su reversion</b> (#34, RF-083).
     *
     * <p>No se borra ni se edita nada: «un asiento equivocado no se corrige, se reversa» (V2). Por
     * cada asiento que la cobranza escribio queda su opuesto, con {@code asiento_reversado_id}
     * apuntando al original, y el libro conserva las dos filas. Es la unica forma de que el estado
     * de cuenta siga contando lo que de verdad paso: que se cobro el 15 de marzo y que se anulo el
     * mismo dia.
     *
     * <p>Se reversan <b>todos</b> los asientos del documento, cargos incluidos. Al cobrar, {@link
     * #abonarPagoIntegro} cristaliza el reajuste y el interes devengados con un cargo antes de
     * abonarlos; deshacer solo los abonos dejaria ese cargo vivo y la obligacion debiendo un
     * interes que ya nadie va a cobrar por esa via.
     *
     * <p>Tras la reversion, {@code deudaActualizadaA(hoy)} vuelve a mostrar la deuda pendiente. No
     * porque se haya escrito esa cifra en ningun sitio, sino porque el neteo de cargos contra
     * abonos vuelve a dar lo que daba: es la consecuencia de que el libro sea la unica verdad
     * (ADR-0006).
     *
     * @param documentoOrigen el documento cuyos asientos se deshacen; en tesoreria, {@code "RECIBO
     *     001-0000123"}
     * @param documentoDeLaReversion el documento que sustenta los asientos nuevos. <b>Tiene que ser
     *     distinto del anterior</b>, y no es una formalidad: si la reversion se marcara con el
     *     mismo documento, una segunda llamada la encontraria y reversaria la reversion
     * @param fecha fecha valor de la reversion; decide ademas en que particion caen los asientos
     *     nuevos
     * @param observacion por que se reversa (regla 10); queda como {@code motivo} de cada asiento
     * @return cuantos asientos se escribieron y cuanto vuelve a deberse
     * @throws SinAbonosQueReversar si ese documento no origino ningun asiento reversable
     */
    ReversionDeAbonos reversarAbonos(
            String documentoOrigen,
            String documentoDeLaReversion,
            LocalDate fecha,
            Observacion observacion);

    /**
     * Ese documento no origino ningun asiento que se pueda reversar.
     *
     * <p>O nunca los tuvo —un recibo de caja de tasas no toca el libro: un derecho de tramite no es
     * deuda tributaria— o ya se reversaron. En los dos casos quien llama sabe algo que este
     * contexto no: si eso es un error o lo esperado.
     */
    final class SinAbonosQueReversar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public SinAbonosQueReversar(String mensaje) {
            super(mensaje);
        }
    }

    /**
     * Ninguna de las obligaciones marcadas tenia deuda a la fecha de pago.
     *
     * <p>Es el error que ve el cajero cuando alguien cobra dos veces: la primera cobranza dejo el
     * saldo en cero y la segunda no encuentra nada que abonar. No es un fallo tecnico, es el
     * sistema diciendo que esa deuda ya se pago.
     */
    final class SinDeudaQueAbonar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public SinDeudaQueAbonar(String mensaje) {
            super(mensaje);
        }
    }

    /**
     * Lo que el libro extinguiria no es lo que la caja cobro (#39).
     *
     * <h2>Por que esto se para en vez de imputarse a medias, y quien lo decidio</h2>
     *
     * <p><b>La politica es: igualdad al centimo, o no se asienta nada.</b> Esta escrita aqui porque
     * es aqui donde se aplica, y no es una preferencia de quien la escribio — es lo unico que queda
     * en pie cuando se descartan las alternativas por las decisiones que siguen abiertas:
     *
     * <ul>
     *   <li><b>Asentar solo hasta lo cobrado</b> es un pago parcial, y que parte extingue
     *       —insoluto, reajuste, interes o gasto, y en que orden entre obligaciones— es el art. 31
     *       del TUO del Codigo Tributario. Eso es <b>D-14</b>, abierta, y el registro de decisiones
     *       la describe como normativa «que hay que transcribir y firmar, no elegir». Elegirla aqui
     *       seria exactamente lo que ADR-0026 existe para impedir.
     *   <li><b>Aceptarlo y asentar la diferencia como concepto propio</b> es, con {@code Δ > 0},
     *       una condonacion —«la escribira quien tenga los valores de la ordenanza firmados»,
     *       D-02b— y con {@code Δ < 0} un pago en exceso, cuyo regimen de devolucion o compensacion
     *       no existe en este sistema.
     *   <li><b>Extinguir el recalculo</b> es el defecto que #39 nombra: la municipalidad deja de
     *       percibir la diferencia, o el libro extingue menos de lo cobrado, y ninguna cifra lo
     *       dice.
     * </ul>
     *
     * <p>Queda no extinguir nada y decirlo. El pago no se pierde: {@code RecibirPago} lo deja
     * {@code RECHAZADO} con este mensaje como motivo, y la conciliacion del dia lo cuenta aparte.
     *
     * <p><b>Y lo que esto conserva es la premisa de D-14</b>, que hasta ahora era falsa de hecho:
     * el registro de decisiones sostiene que «mientras la caja rechace los dos tipos, ningun pago
     * parcial entra». Un pago que la caja considera integro y que al llegar aqui ya no cubre la
     * deuda <b>es</b> un pago parcial, y hasta #39 entraba y se imputaba entero.
     *
     * <p>El dia que D-14 cierre, lo que cambia es esta clase y no la firma: el importe cobrado ya
     * viaja, que es lo que ninguna de las cuatro politicas podia escribirse sin.
     */
    final class ImporteCobradoNoCuadra extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final Dinero cobrado;
        private final Dinero segunElLibro;
        private final LocalDate fecha;

        private ImporteCobradoNoCuadra(
                String mensaje, Dinero cobrado, Dinero segunElLibro, LocalDate fecha) {
            super(mensaje);
            this.cobrado = cobrado;
            this.segunElLibro = segunElLibro;
            this.fecha = fecha;
        }

        /**
         * Compone el rechazo, <b>con los dos signos separados</b> (AC-3 de #39).
         *
         * <p>No dicen lo mismo ni se arreglan igual: con {@code Δ > 0} falta cobrar y la deuda
         * sigue viva; con {@code Δ < 0} sobra dinero cobrado y lo que falta es a que imputarlo. Una
         * constancia de no adeudo emitida despues tiene que poder explicarse, y para eso hay que
         * saber cual de los dos fue.
         */
        public static ImporteCobradoNoCuadra de(
                Dinero cobrado, Dinero segunElLibro, LocalDate fecha) {
            Dinero diferencia = segunElLibro.menos(cobrado);
            String mensaje =
                    diferencia.esPositivo()
                            ? "Se cobro de menos: la caja cobro "
                                    + cobrado
                                    + " y el libro debe "
                                    + segunElLibro
                                    + " al "
                                    + fecha
                                    + ", o sea "
                                    + diferencia
                                    + " sin cubrir. Extinguir el recalculo regalaria esa"
                                    + " diferencia, y abonar solo lo cobrado es un pago parcial:"
                                    + " su reparto es D-14 y sigue abierta. No se asienta nada; es"
                                    + " dinero cobrado y alguien tiene que decidir"
                            : "Se cobro de mas: la caja cobro "
                                    + cobrado
                                    + " y el libro solo debe "
                                    + segunElLibro
                                    + " al "
                                    + fecha
                                    + ", o sea "
                                    + diferencia.absoluto()
                                    + " sin contrapartida. Abonar lo que el libro debe dejaria esa"
                                    + " diferencia cobrada y sin una sola fila que la explique; el"
                                    + " exceso es un pago indebido y su devolucion no existe en"
                                    + " este sistema. No se asienta nada";
            return new ImporteCobradoNoCuadra(mensaje, cobrado, segunElLibro, fecha);
        }

        /** Lo que la caja cobro. */
        public Dinero cobrado() {
            return cobrado;
        }

        /** Lo que el libro habria extinguido a {@link #fecha()}. */
        public Dinero segunElLibro() {
            return segunElLibro;
        }

        /** La fecha a la que se compararon las dos; viaja siempre (regla 9, RNF-075). */
        public LocalDate fecha() {
            return fecha;
        }
    }
}
