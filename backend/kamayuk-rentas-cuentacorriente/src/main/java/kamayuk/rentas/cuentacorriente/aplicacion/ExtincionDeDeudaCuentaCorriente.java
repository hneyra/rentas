package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.cuentacorriente.ExtincionDeDeuda;
import kamayuk.rentas.cuentacorriente.MovimientoAsentado;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.ActoDelLibro;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.CristalizacionDelDevengo;
import kamayuk.rentas.cuentacorriente.dominio.DeudaActualizada;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.SaldoProyectado;
import kamayuk.rentas.cuentacorriente.dominio.SaldoRepository;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link ExtincionDeDeuda} (#50, RF-064).
 *
 * <h2>Una baja son abonos, uno por parte del desglose</h2>
 *
 * <p>Exactamente lo que {@code MovimientoDeDeuda} de sentido {@code BAJA} produce, y con los mismos
 * conceptos: {@code INSOLUTO}, {@code REAJUSTE}, {@code INTERES} y {@code GASTO}. El concepto dice
 * <b>contra que</b> se imputa y el motivo por que; quien lea el estado de cuenta tiene que poder
 * ver que la baja quito S/ 80 de insoluto y S/ 20 de interes, no una sola linea de «anulacion».
 *
 * <p>Se asienta en la <b>fase en la que la obligacion esta</b>, no en {@code ORDINARIA}: una
 * papeleta cuyo descargo se resuelve cuando ya paso a valor o a coactiva tiene su deuda ahi, y
 * abonar en otra fase dejaria la fase real intacta y crearia un saldo a favor en una que no debia
 * nada.
 *
 * <h2>Cada asiento nace estampado como {@code BAJA_DEUDA} (#662)</h2>
 *
 * <p>Hasta #662 no estampaba ninguno, y la consecuencia era triple y silenciosa: la extincion no
 * salia en la relacion de altas y bajas —la pantalla que existe para auditar como se extingue deuda
 * del municipio (RF-045)—, la deuda extinguida <b>seguia contando</b> como emision del ejercicio
 * —el denominador de todas las barras del panel— y, lo peor, se publicaba como <b>recaudacion</b>:
 * el abono de una extincion es un {@code ABONO} de concepto {@code INSOLUTO}, columna a columna el
 * mismo asiento que el de una cobranza.
 *
 * <h2>Y con su causal, declarada por quien llama (#684)</h2>
 *
 * <p>La causal —el sustento juridico de la baja— viaja en la firma del puerto y <b>no se deduce
 * aqui</b>. Hoy el unico camino es la resolucion de gerencia que deja la multa sin efecto, y quien
 * llama acaba de comprobarlo ({@code dejaLaMultaSinEfecto()}), asi que deducirla saldria bien hoy y
 * afirmaria manana lo que ya no es cierto — el defecto de adivinar, con la agravante de que lo
 * adivinado es lo que defiende el acto ante una auditoria.
 *
 * <p>La causal <b>no</b> sustituye a la observacion, que sigue siendo obligatoria y sigue siendo
 * del usuario (regla 10): una es el sustento del acto y la otra el relato de quien firma.
 *
 * <p>El acto es {@code BAJA_DEUDA} y <b>no uno propio</b>. Lo que se escribe aqui es una baja de
 * deuda: los mismos asientos, por las mismas causales que el desplegable de RF-044 ofrece
 * —«PRESCRIPCIÓN DECLARADA», «RESOLUCIÓN QUE DEJA SIN EFECTO»— y con el mismo efecto sobre el
 * padron; lo unico distinto es que oficina la tramita. Un acto propio que se comportara igual en
 * las tres consultas que leen la columna no distinguiria nada, y tres sitios donde nombrar dos
 * valores son tres sitios donde olvidar uno. El razonamiento entero esta en {@link ActoDelLibro}.
 *
 * <h2>Los candados, en el mismo orden que la cobranza</h2>
 *
 * <p>Se bloquea la obligacion antes de leer nada, igual que {@link
 * AcogimientoAConvenioCuentaCorriente} y {@link RegistroDeAbonosCuentaCorriente}: si una cobranza y
 * una baja se cruzan, la que llegue segunda relee el libro con lo que dejo la primera y da de baja
 * lo que <b>queda</b>. Sin el candado, las dos leerian la misma deuda y la extinguirian dos veces.
 *
 * <h2>Lo que queda se mide desde la fecha de la resolucion, no a esa fecha (#445)</h2>
 *
 * <p>El candado solo cumplia la promesa de arriba si la segunda llegaba con fecha igual o
 * posterior. La fecha de la resolucion la teclea quien la dicta y es retroactiva a proposito, y
 * releer el libro <b>a</b> esa fecha —{@link CalculoDeDeuda#deudaActualizadaA}— descarta el cobro
 * que entro despues de ella: una multa pagada el 20 de abril y dejada sin efecto por una resolucion
 * fechada el 15 se releia entera y se daba de baja entera, y la obligacion quedaba en negativo. Por
 * eso lo que se extingue es {@link CalculoDeDeuda#extinguibleDesde}: lo que se debia ese dia y
 * ningun abono posterior ha extinguido. Y si eso es cero no se asienta nada, que es lo que {@link
 * ExtincionDeDeuda} promete —lo que sobra de verdad es un pago, y eso es una devolucion—.
 *
 * <h2>Y solo si nadie mas origino esa deuda (#371)</h2>
 *
 * <p>La obligacion es contribuyente, tributo, ejercicio y unidad, y no dice que acto cargo cada
 * parte: dos papeletas del mismo obligado y ejercicio sin vehiculo del padron son una sola. Por
 * eso, despues del candado y antes de abonar, {@link
 * kamayuk.rentas.cuentacorriente.dominio.CargosDeUnSoloOrigen} mira la referencia de cada cargo de
 * origen, y si alguno es de otro no se asienta nada. Despues del candado y no antes: un cargo de
 * otra papeleta que entrara entre la comprobacion y la baja se extinguiria igual.
 *
 * <h2>Antes de abonar el devengo, cargarlo (#365)</h2>
 *
 * <p>Lo que se extingue es lo que se debe a la fecha, y eso incluye el reajuste y el interes que
 * {@link CalculoDeDeuda#deudaActualizadaA} proyecta y el libro todavia no tiene. Abonarlos sin
 * haberlos cargado dejaba {@code netear(INTERES)} en negativo para siempre: la multa sin efecto
 * quedaba con un saldo a favor que no existe. Por eso, por cada cuota, primero se asienta el cargo
 * de lo devengado —{@link CristalizacionDelDevengo}, la misma cuenta que la cobranza y el convenio—
 * y despues los abonos. El cargo lleva el documento de la resolucion, que es el papel que explica
 * las dos filas, pero <b>no</b> el acto {@code BAJA_DEUDA} ni la causal: no extingue nada, es la
 * misma fila que la cobranza escribe al cristalizar, y estamparlo lo haria salir en la relacion de
 * altas y bajas (RF-045) como una baja que no es.
 */
@Service
public class ExtincionDeDeudaCuentaCorriente implements ExtincionDeDeuda {

    /** Las cuatro partes del desglose, en el orden en que se dan de baja. */
    private static final List<Concepto> PARTES =
            List.of(Concepto.INSOLUTO, Concepto.REAJUSTE, Concepto.INTERES, Concepto.GASTO);

    private final AsientoRepository asientos;
    private final SaldoRepository saldos;
    private final RegistrarAsiento registrar;
    private final CalculoDeDeuda calculo;
    private final CristalizacionDelDevengo cristalizacion;
    private final PoliticaDeRedondeo redondeo;

    public ExtincionDeDeudaCuentaCorriente(
            AsientoRepository asientos,
            SaldoRepository saldos,
            RegistrarAsiento registrar,
            CalculoDeDeuda calculo,
            PoliticaDeRedondeo redondeo) {
        this.asientos = asientos;
        this.saldos = saldos;
        this.registrar = registrar;
        this.calculo = calculo;
        this.cristalizacion = new CristalizacionDelDevengo(calculo);
        this.redondeo = redondeo;
    }

    /**
     * La contencion de #371: se bloquea la obligacion, <b>despues</b> se mira quien origino su
     * deuda, y solo entonces se abona. En ese orden, un cargo de otra papeleta que entre mientras
     * tanto espera al candado y no se cuela entre la comprobacion y la baja.
     */
    @Override
    @Transactional
    public MovimientoAsentado extinguirLoOriginadoPor(
            long contribuyenteId,
            SeleccionDeObligacion obligacion,
            LocalDate fecha,
            String documentoOrigen,
            String referenciaExterna,
            CausalDeBaja causal,
            Observacion observacion) {

        ClaveDeObligacion clave =
                OrigenDeLaObligacionCuentaCorriente.claveDe(contribuyenteId, obligacion);
        saldos.bloquear(clave);
        OrigenDeLaObligacionCuentaCorriente.exigirUnSoloOrigen(
                asientos, contribuyenteId, obligacion, referenciaExterna);
        return darDeBaja(clave, fecha, documentoOrigen, referenciaExterna, causal, observacion);
    }

    // ------------------------------------------------------------------

    /** La baja misma, con la obligacion ya bloqueada y su origen ya comprobado. */
    private MovimientoAsentado darDeBaja(
            ClaveDeObligacion clave,
            LocalDate fecha,
            String documentoOrigen,
            String referenciaExterna,
            CausalDeBaja causal,
            Observacion observacion) {
        List<DeudaAcogida> dadasDeBaja = new ArrayList<>();
        int escritos = 0;
        for (SaldoProyectado fila : saldos.deLaObligacion(clave)) {
            List<Asiento> delLibro = asientos.deLaObligacion(fila.clave());
            // Lo que queda por extinguir DESDE la fecha, no lo que se debia a ella (#445): un
            // cobro con fecha posterior ya extinguio su parte.
            DeudaActualizada pendiente = calculo.extinguibleDesde(delLibro, fecha, redondeo);
            if (!pendiente.total().esPositivo()) {
                continue;
            }
            // El cargo de lo devengado antes de abonarlo (#365): sin el, el abono del interes
            // deja la cuota con un interes negativo que ningun acto explica.
            for (CristalizacionDelDevengo.Devengo devengo :
                    cristalizacion.sinAsentar(delLibro, fecha, redondeo)) {
                registrar.asentar(
                        Asiento.nuevo(
                                fila.clave().ejercicio(),
                                fila.clave().contribuyenteId(),
                                fila.clave().tributo(),
                                devengo.parte(),
                                TipoAsiento.CARGO,
                                fila.fase(),
                                fila.clave().periodo() == 0 ? null : fila.clave().periodo(),
                                fila.clave().predioId(),
                                fila.clave().vehiculoId(),
                                null,
                                devengo.monto(),
                                fecha,
                                documentoOrigen),
                        observacion);
                escritos++;
            }
            for (Concepto parte : PARTES) {
                Dinero importe = parteDe(pendiente, parte);
                if (!importe.esPositivo()) {
                    continue;
                }
                asentar(
                        fila.clave(),
                        fila.fase(),
                        parte,
                        importe,
                        fecha,
                        documentoOrigen,
                        referenciaExterna,
                        causal,
                        observacion);
                escritos++;
            }
            dadasDeBaja.add(filaDe(fila.clave(), fila.fase(), fecha, pendiente));
        }
        return new MovimientoAsentado(dadasDeBaja, escritos, fecha);
    }

    private void asentar(
            ClaveDeSaldo cuota,
            Fase fase,
            Concepto concepto,
            Dinero monto,
            LocalDate fecha,
            String documentoOrigen,
            @Nullable String referenciaExterna,
            CausalDeBaja causal,
            Observacion observacion) {
        registrar.asentar(
                Asiento.nuevoDelActo(
                        cuota.ejercicio(),
                        cuota.contribuyenteId(),
                        cuota.tributo(),
                        concepto,
                        // Una baja es un ABONO: extingue deuda. El sentido lo pone el tipo de
                        // asiento, nunca el signo del importe (ADR-0006).
                        TipoAsiento.ABONO,
                        fase,
                        // 0 en la proyeccion significa «anual», y en el asiento eso es nulo: es la
                        // traduccion inversa de ClaveDeSaldo.de(Asiento).
                        cuota.periodo() == 0 ? null : cuota.periodo(),
                        cuota.predioId(),
                        cuota.vehiculoId(),
                        referenciaExterna,
                        monto,
                        fecha,
                        documentoOrigen,
                        // BAJA_DEUDA, y no un acto propio (#662): esto ES una baja de deuda
                        // —los mismos asientos, por las mismas causales que el desplegable de
                        // RF-044 ofrece— y lo unico que cambia es que oficina la tramita. Ver
                        // ActoDelLibro para la decision entera.
                        ActoDelLibro.BAJA_DEUDA,
                        false,
                        // Y con su causal (#684), que declara quien llama. Sin ella, la via por
                        // la que se extingue deuda con mas consecuencias seria la unica que el
                        // filtro por causal de RF-045 no encuentra.
                        causal),
                observacion);
    }

    private static DeudaAcogida filaDe(
            ClaveDeSaldo clave, Fase fase, LocalDate fecha, DeudaActualizada deuda) {
        return new DeudaAcogida(
                clave.tributo(),
                clave.ejercicio(),
                clave.periodo(),
                clave.predioId(),
                clave.vehiculoId(),
                fase.name(),
                fecha,
                deuda.insoluto(),
                deuda.reajuste(),
                deuda.interes(),
                deuda.gasto());
    }

    private static Dinero parteDe(DeudaActualizada deuda, Concepto concepto) {
        return switch (concepto) {
            case INSOLUTO -> deuda.insoluto();
            case REAJUSTE -> deuda.reajuste();
            case INTERES -> deuda.interes();
            case GASTO -> deuda.gasto();
            default ->
                    throw new IllegalArgumentException(
                            "El desglose de la deuda tiene cuatro partes, y "
                                    + concepto
                                    + " no es una de ellas");
        };
    }
}
