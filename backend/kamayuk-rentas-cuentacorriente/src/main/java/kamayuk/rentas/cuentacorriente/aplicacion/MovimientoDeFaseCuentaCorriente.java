package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.cuentacorriente.ClaveDeObligacionPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.CristalizacionDelDevengo;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
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
 * difiere es <b>cuanto</b>: el paso a VALOR asienta el monto que el valor congelo, y el paso a
 * COACTIVA el que el libro tiene en VALOR (ver {@link MovimientoDeFase#moverACoactiva}).
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
 * la obligacion —{@link CristalizacionDelDevengo}, la cuenta de todo camino que escribe en el
 * libro—, y despues escribe el par. En cada cuota y no solo en la del par, como el convenio, porque
 * es la obligacion entera la que cambia de fase. El cargo cae en la fase ordinaria, donde esta la
 * deuda, y el par saca de ahi el monto que la OP congelo, que ya lo incluye.
 *
 * <p><b>El paso a coactiva no lo hace todavia</b>, y no por olvido: alli el monto lo decide el
 * libro —el neto en VALOR, acotado por lo que se debe—, y cristalizar antes cambiaria cuanto entra
 * en coactiva. Que el interes devengado despues de la OP se mueva con ella o se quede en VALOR es
 * una decision de cobranza que #365 no toma; queda dicho en su PR.
 */
@Service
public class MovimientoDeFaseCuentaCorriente implements MovimientoDeFase {

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
     */
    @Override
    @Transactional
    public void moverAValor(
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
        ClaveDeObligacion obligacion =
                new ClaveDeObligacion(contribuyenteId, tributo, ejercicio, predioId, vehiculoId);
        saldos.bloquear(obligacion);
        cristalizarElDevengo(obligacion, fechaValor, documentoOrigen, observacion);
        mover(
                Fase.ORDINARIA,
                Fase.VALOR,
                ejercicio,
                contribuyenteId,
                tributo,
                periodo,
                predioId,
                vehiculoId,
                referenciaExterna,
                monto,
                fechaValor,
                documentoOrigen,
                observacion);
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
        ClaveDeObligacion clave =
                new ClaveDeObligacion(
                        contribuyenteId,
                        obligacion.tributo(),
                        obligacion.ejercicio(),
                        obligacion.predioId(),
                        obligacion.vehiculoId());
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
                                // 0 en la proyeccion es «anual», y en el asiento eso es nulo.
                                fila.clave().periodo() == 0 ? null : fila.clave().periodo(),
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
