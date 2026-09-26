package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import kamayuk.rentas.cuentacorriente.ClaveDeObligacionPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.CristalizacionDelDevengo;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.FaseDeLaObligacion;
import kamayuk.rentas.cuentacorriente.dominio.SaldoProyectado;
import kamayuk.rentas.cuentacorriente.dominio.SaldoRepository;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link MovimientoDeFase} como un par de asientos: un abono en la fase de salida y un
 * cargo por el mismo importe en la de entrada, con {@link Concepto#AJUSTE} —el mismo concepto que
 * ya usa cualquier movimiento administrativo que no altera el total adeudado, y que ya exige {@code
 * motivo} por {@code asiento_motivo_ck} (RNF-052)—.
 *
 * <p>Dos pares, y un solo motor: ORDINARIA→VALOR al emitir un valor (#37) y VALOR→COACTIVA al
 * importarlo a un expediente (#407). Son la misma operacion con otras fases, y escribirlas por
 * separado dejaria dos copias del par que la primera modificacion volveria asimetricas. Lo que
 * difiere es <b>cuanto</b>: el paso a VALOR asienta, cuota por cuota, lo que cada cuota ordinaria
 * debe (#448), y el paso a COACTIVA el que el libro tiene en VALOR (ver {@link
 * MovimientoDeFase#moverACoactiva}).
 *
 * <p>Las dos escrituras van en la misma transaccion: si la segunda fallara, la primera se revierte
 * con ella. Un abono sin su cargo dejaria una obligacion con menos deuda de la que en realidad
 * tiene, y eso es peor que no mover nada.
 *
 * <h2>Antes del par, el devengo de cada cuota (#365)</h2>
 *
 * <p>El par no cambia el total —{@code AJUSTE} no es ninguna de las cuatro partes que {@code
 * deudaActualizadaA} netea—, pero su fecha valor pasa a ser el ultimo movimiento de la cuota, y
 * desde ahi acumula la mora. Sin mas, el pase a valor se llevaba el interes devengado hasta la
 * emision: la OP congela un interes que el libro, del que leen caja y coactiva, ya no tenia al dia
 * siguiente.
 *
 * <p>Por eso {@link #moverAValor} carga primero lo devengado y no asentado en <b>cada cuota</b> de
 * la obligacion —{@link CristalizacionDelDevengo}, la cuenta de los seis caminos que #365 recorre—,
 * y despues escribe el par. En cada cuota y no solo en la del par, como el convenio, porque es la
 * obligacion entera la que cambia de fase. El cargo cae en la fase ordinaria, donde esta la deuda,
 * y el par saca de ahi el monto que la OP congelo, que ya lo incluye.
 *
 * <p><b>El paso a coactiva no lo hace todavia</b>, y no por olvido: alli el monto lo decide el
 * libro —el neto en VALOR, acotado por lo que se debe—, y cristalizar antes cambiaria cuanto entra
 * en coactiva. Que el interes devengado despues de la OP se mueva con ella o se quede en VALOR es
 * una decision de cobranza que #365 no toma; queda dicho en su PR. <b>Mientras no se tome, el par
 * VALOR→COACTIVA adelanta el ancla y ese interes se pierde en cada paso a coactiva</b>: el
 * encendido de la mora (D-02) exige resolverlo antes.
 */
@Service
public class MovimientoDeFaseCuentaCorriente implements MovimientoDeFase {

    /** Las cuotas en el orden del cronograma, para que los pares salgan siempre igual. */
    private static final Comparator<ClaveDeSaldo> POR_PERIODO =
            Comparator.comparingInt(ClaveDeSaldo::periodo);

    private final RegistrarAsiento registrar;
    private final AsientoRepository asientos;
    private final SaldoRepository saldos;
    private final CalculoDeDeuda calculo;
    private final CristalizacionDelDevengo cristalizacion;
    private final PoliticaDeRedondeo redondeo;

    public MovimientoDeFaseCuentaCorriente(
            RegistrarAsiento registrar,
            AsientoRepository asientos,
            SaldoRepository saldos,
            CalculoDeDeuda calculo,
            PoliticaDeRedondeo redondeo) {
        this.registrar = registrar;
        this.asientos = asientos;
        this.saldos = saldos;
        this.calculo = calculo;
        this.cristalizacion = new CristalizacionDelDevengo(calculo);
        this.redondeo = redondeo;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Bloquea la obligacion antes de leerla (#365): cristalizar el devengo es leer el libro y
     * escribir lo que falta, y una cobranza que se colara en medio cristalizaria el mismo interes
     * otra vez. Es el candado que el paso a coactiva ya pedia.
     *
     * <p>Cuota por cuota —las del libro, no las de la proyeccion—, y con la fase de cada una <b>a
     * la fecha</b> ({@link FaseDeLaObligacion}, la regla que tambien corta la consulta): una
     * corrida masiva emite a su fecha de criterio, y la proyeccion es de hoy.
     */
    @Override
    @Transactional
    public Dinero moverAValor(
            long contribuyenteId,
            ClaveDeObligacionPublica obligacion,
            String referenciaExterna,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion) {
        ClaveDeObligacion clave = claveDe(contribuyenteId, obligacion);
        saldos.bloquear(clave);
        cristalizarElDevengo(clave, fechaValor, documentoOrigen, observacion);

        // Las cuotas salen del libro y no de la proyeccion, como en el paso a coactiva: el libro es
        // la fuente, y se lee DESPUES de cristalizar, que acaba de escribir en el.
        Map<ClaveDeSaldo, List<Asiento>> porCuota = new TreeMap<>(POR_PERIODO);
        for (Asiento asiento : asientos.deTodosLosPeriodosDe(clave)) {
            porCuota.computeIfAbsent(ClaveDeSaldo.de(asiento), cuota -> new ArrayList<>())
                    .add(asiento);
        }

        Dinero movido = Dinero.CERO;
        for (Map.Entry<ClaveDeSaldo, List<Asiento>> cuota : porCuota.entrySet()) {
            List<Asiento> deLaCuota = cuota.getValue();
            if (FaseDeLaObligacion.a(deLaCuota, fechaValor) != Fase.ORDINARIA) {
                continue;
            }
            Dinero debe = calculo.deudaActualizadaA(deLaCuota, fechaValor, redondeo).total();
            if (!debe.esPositivo()) {
                continue;
            }
            mover(
                    Fase.ORDINARIA,
                    Fase.VALOR,
                    obligacion.ejercicio(),
                    contribuyenteId,
                    obligacion.tributo(),
                    periodoDelAsiento(cuota.getKey()),
                    obligacion.predioId(),
                    obligacion.vehiculoId(),
                    referenciaExterna,
                    debe,
                    fechaValor,
                    documentoOrigen,
                    observacion);
            movido = movido.mas(debe);
        }
        return movido;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Bloquea la obligacion antes de leerla, como el acogimiento a convenio: dos importaciones
     * simultaneas de valores de la misma obligacion leerian las dos el mismo neto en VALOR y lo
     * moverian dos veces.
     */
    @Override
    @Transactional
    public Dinero moverACoactiva(
            long contribuyenteId,
            ClaveDeObligacionPublica obligacion,
            String referenciaExterna,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion) {
        ClaveDeObligacion clave = claveDe(contribuyenteId, obligacion);
        saldos.bloquear(clave);
        List<Asiento> delLibro = asientos.deTodosLosPeriodosDe(clave);

        Dinero enValor = netoEn(Fase.VALOR, delLibro);
        Dinero seDebe = calculo.extinguibleDesde(delLibro, fechaValor, redondeo).total();
        Dinero monto = enValor.esMayorQue(seDebe) ? seDebe : enValor;
        if (!monto.esPositivo()) {
            return Dinero.CERO;
        }
        mover(
                Fase.VALOR,
                Fase.COACTIVA,
                obligacion.ejercicio(),
                contribuyenteId,
                obligacion.tributo(),
                null,
                obligacion.predioId(),
                obligacion.vehiculoId(),
                referenciaExterna,
                monto,
                fechaValor,
                documentoOrigen,
                observacion);
        return monto;
    }

    /**
     * Lo que el libro cuenta en una fase: cargos menos abonos, de todos los conceptos.
     *
     * <p>Todos, y no las cuatro partes que {@code deudaActualizadaA} netea: el par de un movimiento
     * de fase lleva {@link Concepto#AJUSTE} o {@code FRACCIONAMIENTO}, y es justo lo que dice que
     * la deuda salio de una fase y entro en otra. Sumados sobre todas las fases, los pares se
     * anulan y queda lo que se debe.
     */
    private static Dinero netoEn(Fase fase, List<Asiento> delLibro) {
        Dinero neto = Dinero.CERO;
        for (Asiento asiento : delLibro) {
            if (asiento.fase() != fase) {
                continue;
            }
            neto =
                    asiento.tipo() == TipoAsiento.CARGO
                            ? neto.mas(asiento.monto())
                            : neto.menos(asiento.monto());
        }
        return neto;
    }

    // ------------------------------------------------------------------

    private static ClaveDeObligacion claveDe(
            long contribuyenteId, ClaveDeObligacionPublica obligacion) {
        return new ClaveDeObligacion(
                contribuyenteId,
                obligacion.tributo(),
                obligacion.ejercicio(),
                obligacion.predioId(),
                obligacion.vehiculoId());
    }

    /** 0 en la proyeccion es «anual», y en el asiento eso es nulo. */
    private static @Nullable Integer periodoDelAsiento(ClaveDeSaldo cuota) {
        return cuota.periodo() == 0 ? null : cuota.periodo();
    }

    private void mover(
            Fase salida,
            Fase entrada,
            Ejercicio ejercicio,
            long contribuyenteId,
            String tributo,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion) {

        // nuevoConMotivo y no nuevo: AJUSTE exige motivo y el constructor de Asiento lo
        // comprueba, asi que sin el la fila NI SIQUIERA SE PUEDE CONSTRUIR -y este metodo
        // fallaba con IllegalArgumentException cada vez que la obligacion tenia deuda-.
        // Lo definitivo lo pone RegistrarAsiento#asentar con la observacion del usuario.
        Asiento abonoEnLaSalida =
                Asiento.nuevoConMotivo(
                        ejercicio,
                        contribuyenteId,
                        tributo,
                        Concepto.AJUSTE,
                        TipoAsiento.ABONO,
                        salida,
                        periodo,
                        predioId,
                        vehiculoId,
                        referenciaExterna,
                        monto,
                        fechaValor,
                        documentoOrigen,
                        observacion.texto());
        registrar.asentar(abonoEnLaSalida, observacion);

        Asiento cargoEnLaEntrada =
                Asiento.nuevoConMotivo(
                        ejercicio,
                        contribuyenteId,
                        tributo,
                        Concepto.AJUSTE,
                        TipoAsiento.CARGO,
                        entrada,
                        periodo,
                        predioId,
                        vehiculoId,
                        referenciaExterna,
                        monto,
                        fechaValor,
                        documentoOrigen,
                        observacion.texto());
        registrar.asentar(cargoEnLaEntrada, observacion);
    }

    /**
     * El cargo de lo devengado y no asentado en cada cuota de la obligacion, en la fase en la que
     * esta, antes de que el par adelante el ultimo movimiento (#365). La obligacion ya esta
     * bloqueada: lo hace quien llama.
     */
    private void cristalizarElDevengo(
            ClaveDeObligacion obligacion,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion) {
        for (SaldoProyectado fila : saldos.deLaObligacion(obligacion)) {
            for (CristalizacionDelDevengo.Devengo devengo :
                    cristalizacion.sinAsentar(
                            asientos.deLaObligacion(fila.clave()), fechaValor, redondeo)) {
                registrar.asentar(
                        Asiento.nuevo(
                                fila.clave().ejercicio(),
                                fila.clave().contribuyenteId(),
                                fila.clave().tributo(),
                                devengo.parte(),
                                TipoAsiento.CARGO,
                                fila.fase(),
                                periodoDelAsiento(fila.clave()),
                                fila.clave().predioId(),
                                fila.clave().vehiculoId(),
                                null,
                                devengo.monto(),
                                fechaValor,
                                documentoOrigen),
                        observacion);
            }
        }
    }
}
