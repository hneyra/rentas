package kamayuk.rentas.tesoreria.pagos;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.AbonoAsentado;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.ReversionDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Observacion;
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
 * <p>Se le pasa la lista de obligaciones a {@link RegistroDeAbonos}, que es exactamente lo que
 * hacia la ventanilla cuando el cobro era una sola transaccion. <b>Ni una regla de calculo cambio
 * de sitio con la separacion</b>: el orden del art. 31 del Codigo Tributario sigue viviendo donde
 * vivia.
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

    /** Abona en el libro lo que el pago cobro. */
    private int imputar(PagoRecibido pago) {
        Long contribuyenteId = pago.contribuyenteId();
        if (contribuyenteId == null) {
            throw new RegistroDeAbonos.SinDeudaQueAbonar(
                    "El pago "
                            + pago.pagoId()
                            + " no dice a que contribuyente de este padron se le cobro. La caja"
                            + " admite un pagador anonimo —cobra tasas al contado, y manana un"
                            + " puesto de mercado— pero un abono del libro es de alguien");
        }
        List<SeleccionDeObligacion> obligaciones = new ArrayList<>(pago.obligaciones().size());
        for (ReferenciaDeObligacion referencia : pago.obligaciones()) {
            obligaciones.add(referencia.comoSeleccion());
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
                        contribuyenteId,
                        obligaciones,
                        pago.fechaDePago(),
                        pago.documentoDeOrigen(),
                        porElPago(pago));
        return abonado.size();
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
     * ADR-0006 —el libro no se reescribe—, y ademas decide en que particion caen los asientos.
     */
    private int reversar(PagoRecibido pago) {
        ReversionDeAbonos reversion =
                abonos.reversarAbonos(
                        pago.documentoDeOrigen(),
                        pago.documentoDeLaAnulacion(),
                        pago.fechaDeAnulacionExigida(),
                        porLaAnulacion(pago));
        return reversion.asientos();
    }
}
