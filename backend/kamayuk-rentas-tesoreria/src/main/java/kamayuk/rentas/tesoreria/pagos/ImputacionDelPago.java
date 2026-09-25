package kamayuk.rentas.tesoreria.pagos;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kamayuk.rentas.cuentacorriente.AbonoAsentado;
import kamayuk.rentas.cuentacorriente.ObligacionDelDeudor;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.ReversionDeAbonos;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El alta en el buzon y la imputacion, <b>en una sola transaccion</b> (ADR-0026 §3, COMMIT 2).
 *
 * <h2>Por que las dos tienen que caer juntas</h2>
 *
 * <p>Es lo que impide el peor estado posible: el pago marcado como recibido y <b>sin asiento</b>.
 * Si estuvieran en dos transacciones, un fallo entre ellas dejaria al reintento viendo «ya
 * recibido» y no lo imputaria nunca — dinero cobrado, registrado como aplicado, y sin una sola fila
 * en el libro.
 *
 * <h2>Por que es una clase aparte de {@link RecibirPago}</h2>
 *
 * <p>Porque {@code @Transactional} sobre un metodo llamado desde la misma clase <b>no se
 * aplica</b>: la auto-invocacion no pasa por el proxy de Spring. Estuvo escrito en un solo objeto y
 * la prueba lo destapo en el acto: {@code unrecognized configuration parameter
 * "app.municipalidad_id"} — sin transaccion no hay {@code SET LOCAL}, y la politica RLS no devuelve
 * vacio sino que <b>revienta</b> (#486). Es la misma leccion que #536 midio con el bucle de la
 * carga cartografica y #430 con {@code ImportarCajas}, aqui aprendida por tercera vez.
 *
 * <h2>La imputacion es de este sistema, y esto es lo unico que hace falta para que lo sea</h2>
 *
 * <p>Se le pasa la lista de obligaciones a {@link RegistroDeAbonos}, <b>cada una con su deudor</b>
 * (#431), que es lo que hacia la ventanilla cuando el cobro era una sola transaccion. <b>Ni una
 * regla de calculo cambio de sitio con la separacion</b>: el orden del art. 31 del Codigo
 * Tributario sigue viviendo donde vivia.
 */
@Service
public class ImputacionDelPago {

    private final PagoRecibidoRepository buzon;
    private final RegistroDeAbonos abonos;
    private final Clock reloj;

    public ImputacionDelPago(PagoRecibidoRepository buzon, RegistroDeAbonos abonos, Clock reloj) {
        this.buzon = buzon;
        this.abonos = abonos;
        this.reloj = reloj;
    }

    /**
     * @throws RegistroDeAbonos.SinDeudaQueAbonar si no hay contra que imputar
     * @throws RegistroDeAbonos.SinAbonosQueReversar si la anulacion no encuentra que deshacer
     * @throws RegistroDeAbonos.ImporteCobradoNoCuadra si el libro extinguiria una cifra distinta de
     *     la que la caja cobro (#39)
     * @throws ElCobroFueRechazado si la anulacion nombra un cobro que nunca toco el libro (#428)
     * @throws AnuladoAntesDeImputarse si el cobro ya tiene una anulacion en el buzon (#428)
     * @throws RecibirPago.AnulacionAntesQueSuCobro si la anulacion nombra un cobro que todavia no
     *     llego, o que todavia no confirmo. <b>Es la unica de las seis que NO se atrapa</b>: es
     *     «todavia no», y no «nunca» (#428)
     */
    @Transactional
    public RecibirPago.Recibido recibirEImputar(PagoRecibido pago) {
        PagoRecibidoRepository.Recepcion recepcion = buzon.recibir(pago);
        if (!recepcion.nuevo()) {
            // Ya estaba. NO se imputa otra vez: es el criterio 3 de P5D, y la garantia es el
            // indice unico y no este `if` — dos entregas simultaneas se ordenan en el motor y
            // solo una de las dos ve `nuevo = true`.
            return new RecibirPago.Recibido(recepcion.pago(), false);
        }

        PagoRecibido guardado = recepcion.pago();
        int asientos =
                guardado.tipo() == TipoDePagoRecibido.PAGO_ANULADO
                        ? reversar(guardado)
                        : imputar(guardado);
        buzon.marcarAplicado(guardado.idGuardado(), asientos, reloj.instant());
        return new RecibirPago.Recibido(buzon.porPagoId(guardado.pagoId()).orElseThrow(), true);
    }

    /**
     * La observacion con la que se asienta.
     *
     * <p><b>La compone el sistema, y hay que decir por que no la pide.</b> La regla 10 exige que
     * toda modificacion lleve la observacion del usuario, y aqui no hay usuario: el pago llega por
     * el buzon de otra base. Lo que se escribe es la unica frase cierta —de que recibo viene— y
     * pedirsela a quien publica seria inventarla, que es la mutacion que #538 midio y rechazo.
     */
    private static Observacion porElPago(PagoRecibido pago) {
        return Observacion.de(
                "Imputacion del pago "
                        + pago.pagoId()
                        + " cobrado en caja con el recibo "
                        + pago.reciboNumero());
    }

    /**
     * La observacion de una reversion, <b>con el motivo que la caja mando</b> (C-1, desajuste 8).
     *
     * <p>Aqui si hay un usuario y sus palabras: quien anulo el recibo en ventanilla tuvo que
     * escribir el sustento —{@code AnularRecibo.Anulacion} lo exige (RNF-052)— y hasta C-1 se
     * perdia en el borde. Componer la frase sin el seria inventar la observacion, que es la
     * mutacion que #538 midio y rechazo; llevarlo dentro es lo contrario: es no tirarla.
     *
     * <p>Queda como {@code motivo} de cada asiento de reversion, que es donde se lee por que una
     * deuda volvio a estar viva.
     */
    private static Observacion porLaAnulacion(PagoRecibido pago) {
        return Observacion.de(
                "Reversion del pago "
                        + pago.pagoOriginalId()
                        + " por la anulacion del recibo "
                        + pago.reciboNumero()
                        + ". Motivo: "
                        + pago.motivoDeLaAnulacionExigido());
    }

    /**
     * Abona en el libro lo que el pago cobro, <b>si nadie lo anulo antes</b> (#428).
     *
     * <p>La comprobacion va antes de tocar nada, y es la defensa del otro lado de {@link
     * #reversar}: aquella impide que una anulacion adelantada se cierre en falso, y esta impide que
     * un cobro se impute sobre una anulacion que ya esta en el buzon —la que el codigo de antes de
     * #428 dejaba {@code RECHAZADA} con 201, y cualquier camino que un dia se salte a {@link
     * #reversar}—. Sin ella, el cobro de un recibo que la caja ya devolvio extinguia la deuda: el
     * contribuyente al dia con un dinero que tiene otra vez en el bolsillo.
     *
     * <p>Se busca por {@code pago_original_id}, que es la clave que {@code V8} puso para esto y que
     * {@code V24} indexa; no por el numero del papel.
     *
     * <h2>El deudor de cada linea es el de su referencia, no el pagador (#431)</h2>
     *
     * <p>La caja junta en un recibo ordenes de deudores distintos y publica <b>un</b> pagador —el
     * de la primera orden—. Hasta #431 ese pagador era el deudor de todas las lineas: el libro
     * buscaba la obligacion de B bajo A, no encontraba saldo, lo cobrado no cuadraba y el pago
     * entero quedaba {@code RECHAZADO}. Desde #431 la referencia lleva al deudor y es ese el que se
     * usa. El pagador solo decide en una referencia de cinco partes —la de una orden emitida antes
     * de #431 y cobrada despues—, que no trae otro.
     */
    private int imputar(PagoRecibido pago) {
        Optional<PagoRecibido> anulacion = buzon.anulacionDe(pago.pagoId());
        if (anulacion.isPresent()) {
            throw new AnuladoAntesDeImputarse(
                    "El pago "
                            + pago.pagoId()
                            + " del recibo "
                            + pago.reciboNumero()
                            + " fue anulado antes de imputarse: la anulacion "
                            + anulacion.get().pagoId()
                            + " ya esta en el buzon ("
                            + anulacion.get().estado()
                            + "). La caja devolvio ese dinero, asi que imputarlo extinguiria"
                            + " deuda sin cobro; no se toca el libro");
        }
        List<ObligacionDelDeudor> obligaciones = new ArrayList<>(pago.obligaciones().size());
        for (ReferenciaDeObligacion referencia : pago.obligaciones()) {
            obligaciones.add(
                    new ObligacionDelDeudor(
                            deudorDe(referencia, pago), referencia.comoSeleccion()));
        }
        if (obligaciones.isEmpty()) {
            throw new RegistroDeAbonos.SinDeudaQueAbonar(
                    "El pago "
                            + pago.pagoId()
                            + " no trae ninguna obligacion. Un recibo de caja de TASAS no produce"
                            + " evento, asi que si esto llego aqui es que se publico un cobro sin"
                            + " ordenes");
        }
        List<AbonoAsentado> abonado =
                abonos.abonarPagoIntegro(
                        obligaciones,
                        // Lo que la caja cobro DE VERDAD (#39). Hasta este issue no viajaba —
                        // `grep -n "total" ImputacionDelPago.java` no devolvia ni una linea— y el
                        // libro extinguia lo que el mismo recalculaba a `fechaDePago`, que con un
                        // pago hecho dias despues de emitirse su orden no es la misma cifra.
                        pago.total(),
                        pago.fechaDePago(),
                        pago.documentoDeOrigen(),
                        porElPago(pago));
        return abonado.size();
    }

    /**
     * De quien es la deuda de esa linea: el deudor que la referencia lleva (#431).
     *
     * <p>Solo una referencia de cinco partes —emitida antes de #431— no lo lleva, y entonces sale
     * del pagador, como salia para todas. Si tampoco hay pagador, no hay a nombre de quien asentar.
     */
    private static long deudorDe(ReferenciaDeObligacion referencia, PagoRecibido pago) {
        @Nullable Long deudor = referencia.contribuyenteId();
        if (deudor != null) {
            return deudor;
        }
        @Nullable Long pagador = pago.contribuyenteId();
        if (pagador == null) {
            throw new RegistroDeAbonos.SinDeudaQueAbonar(
                    "El pago "
                            + pago.pagoId()
                            + " cobra '"
                            + referencia.texto()
                            + "', que no lleva deudor —es de antes de #431—, y no dice a que"
                            + " contribuyente de este padron se le cobro. La caja admite un"
                            + " pagador anonimo —cobra tasas al contado, y manana un puesto de"
                            + " mercado— pero un abono del libro es de alguien");
        }
        return pagador;
    }

    /**
     * Deshace los abonos del pago que la anulacion nombra.
     *
     * <p><b>Reversa: escribe asientos contrarios.</b> No borra, y no puede: el libro es inmutable
     * (ADR-0006) y {@code cuenta_corriente_asiento} esta en {@code TABLAS_PROTEGIDAS}, asi que un
     * {@code DELETE} ahi rompe el build antes de llegar a ejecucion. Es el criterio 4 de P5D.
     *
     * <p><b>Y la fecha valor es la de la ANULACION, no la del recibo</b> (C-1, desajuste 9). Hasta
     * C-1 se reversaba con {@code fechaDePago} —la del papel original—, porque la fecha que la caja
     * manda se descartaba en el borde. Anular en julio un recibo de marzo escribia entonces la
     * reversion en marzo: un estado de cuenta al 30 de abril recalculado despues cambiaba de
     * respuesta, cuando lo cierto es que ese recibo estuvo vigente hasta julio. Es la regla 9 y
     * ADR-0006 —el libro no se reescribe—. Lo que esa fecha NO decide es el ejercicio: la reversion
     * cae en el de la obligacion que el recibo cobro, aunque se anule al ano siguiente (#424).
     *
     * <h2>Y antes de tocar el libro, el buzon decide (#428)</h2>
     *
     * <p>La anulacion nombra un pago —{@code pagoOriginalId}— y es ese pago, y no el numero del
     * papel, lo que se busca primero. Hasta #428 se iba derecho al libro por {@code "RECIBO " +
     * numero}, y «no encuentro asientos» tenia dos lecturas —o nunca toco el libro, o ya se
     * reversaron— cuando le faltaba una tercera: <b>todavia no llego</b>. La caja entrega el cobro
     * antes que su anulacion, pero no espera a que se confirme: si el cobro se queda esperando un
     * candado mas de 30 s, o cae en una replica que se apaga, la anulacion llega sola.
     *
     * <ul>
     *   <li><b>No esta, o esta {@code EN_TRANSITO}:</b> {@link
     *       RecibirPago.AnulacionAntesQueSuCobro}. No se atrapa: la transaccion se deshace entera
     *       —tampoco queda la fila de la anulacion, o el reintento recibiria 409 «ya lo tengo»— y
     *       el borde contesta 503, que la caja reintenta. Si agota los intentos da el evento por
     *       muerto con alerta: el caso queda a la vista en vez de cerrado en falso. En {@code READ
     *       COMMITTED} no hay carrera: si el cobro no confirmo, esta transaccion no lo ve; si lo ve
     *       {@code APLICADO}, sus asientos ya estan confirmados.
     *   <li><b>{@code RECHAZADO}:</b> el cobro nunca toco el libro y no hay nada que deshacer.
     *       Sigue siendo un rechazo, pero con ese motivo — esperar aqui seria esperar para siempre.
     *   <li><b>{@code APLICADO}:</b> se reversa, y se reversan <b>los asientos de ese pago</b>: su
     *       documento de origen, y no el que la anulacion dice.
     * </ul>
     *
     * <p>Que el cobro no este nunca no es un caso: la caja se niega a anular un recibo cuyo cobro
     * no esta en su buzon de salida ({@code AnularRecibo.publicarLaAnulacion}), asi que un recibo
     * de antes de P5D no produce anulacion que llegue aqui.
     */
    private int reversar(PagoRecibido pago) {
        UUID nombrado = Objects.requireNonNull(pago.pagoOriginalId());
        PagoRecibido original = buzon.porPagoId(nombrado).orElse(null);
        if (original == null || original.estado() == EstadoDelPagoRecibido.EN_TRANSITO) {
            throw new RecibirPago.AnulacionAntesQueSuCobro(
                    "La anulacion "
                            + pago.pagoId()
                            + " del recibo "
                            + pago.reciboNumero()
                            + " nombra el pago "
                            + nombrado
                            + ", que "
                            + (original == null
                                    ? "todavia no esta en el buzon"
                                    : "todavia no se imputo")
                            + ". Es «todavia no» y no «nunca»: no se registra nada y se espera el"
                            + " reintento de la caja, que entrega el cobro antes que su anulacion");
        }
        if (original.estado() == EstadoDelPagoRecibido.RECHAZADO) {
            throw new ElCobroFueRechazado(
                    "La anulacion "
                            + pago.pagoId()
                            + " nombra el pago "
                            + nombrado
                            + ", que quedo RECHAZADO y nunca toco el libro: no hay nada que"
                            + " reversar");
        }
        ReversionDeAbonos reversion =
                abonos.reversarAbonos(
                        original.documentoDeOrigen(),
                        pago.documentoDeLaAnulacion(),
                        pago.fechaDeAnulacionExigida(),
                        porLaAnulacion(pago));
        return reversion.asientos();
    }

    /**
     * La anulacion nombra un cobro que quedo {@code RECHAZADO}: nunca toco el libro (#428).
     *
     * <p>{@link RecibirPago} la atrapa y la anulacion queda {@code RECHAZADA} con este motivo. No
     * es «todavia no»: el cobro no se va a imputar nunca, asi que esperarlo dejaria a la caja
     * reintentando hasta dar el evento por muerto.
     */
    public static final class ElCobroFueRechazado extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ElCobroFueRechazado(String mensaje) {
            super(mensaje);
        }
    }

    /**
     * El cobro llega con una anulacion suya ya en el buzon (#428).
     *
     * <p>{@link RecibirPago} la atrapa y el cobro queda {@code RECHAZADO} con el motivo «anulado
     * antes de imputarse», sin un solo asiento. Es dinero que la caja ya devolvio.
     */
    public static final class AnuladoAntesDeImputarse extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AnuladoAntesDeImputarse(String mensaje) {
            super(mensaje);
        }
    }
}
