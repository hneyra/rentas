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
        this.redondeo = redondeo;
    }

    @Override
    @Transactional
    public MovimientoAsentado extinguir(
            long contribuyenteId,
            SeleccionDeObligacion obligacion,
            LocalDate fecha,
            String documentoOrigen,
            @Nullable String referenciaExterna,
            CausalDeBaja causal,
            Observacion observacion) {

        ClaveDeObligacion clave =
                new ClaveDeObligacion(
                        contribuyenteId,
                        obligacion.tributo(),
                        obligacion.ejercicio(),
                        obligacion.predioId(),
                        obligacion.vehiculoId());
        saldos.bloquear(clave);

        List<DeudaAcogida> dadasDeBaja = new ArrayList<>();
        int escritos = 0;
        for (SaldoProyectado fila : saldos.deLaObligacion(clave)) {
            List<Asiento> delLibro = asientos.deLaObligacion(fila.clave());
            DeudaActualizada pendiente = calculo.deudaActualizadaA(delLibro, fecha, redondeo);
            if (!pendiente.total().esPositivo()) {
                continue;
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

    // ------------------------------------------------------------------

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
