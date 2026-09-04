package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kamayuk.rentas.cuentacorriente.AcogimientoAConvenio;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.cuentacorriente.MovimientoAsentado;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link AcogimientoAConvenio} (#35, RF-084, RF-086).
 *
 * <h2>El par de asientos, y por que su concepto no es una de las cuatro partes</h2>
 *
 * <p>Mover de fase es un abono en la fase de salida y un cargo por el mismo importe en la de
 * entrada, con {@link Concepto#FRACCIONAMIENTO} —el concepto que {@code
 * cuenta_corriente_asiento_concepto_check} tiene desde V2 justo para esto—. Que el concepto del par
 * <b>no</b> sea {@code INSOLUTO}, {@code REAJUSTE}, {@code INTERES} ni {@code GASTO} es lo que hace
 * que {@code deudaActualizadaA} lo ignore: netea por concepto, y el total adeudado no cambia.
 *
 * <p>Lo que si cambia es la <b>fase</b>, porque la fase de una obligacion es la de su ultimo
 * asiento ({@code ProyeccionDelSaldo}). Es el mismo mecanismo de {@link
 * MovimientoDeFaseCuentaCorriente} para el pase a valor, y por el mismo motivo: nunca un {@code
 * UPDATE} de una columna de fase, porque el libro no se edita.
 *
 * <h2>El devengo se cristaliza antes de mover</h2>
 *
 * <p>{@code deudaActualizadaA} agrega el reajuste y el interes que todavia <b>no</b> estan en el
 * libro, y los cuenta desde el ultimo movimiento conocido. Un asiento nuevo mueve ese punto hacia
 * adelante, asi que acoger sin mas <b>perderia</b> el devengo acumulado hasta hoy: la deuda saldria
 * a fase de convenio con menos interes del que tenia, sin ningun error de por medio.
 *
 * <p>Por eso, antes del par, se asienta el cargo de lo devengado y no asentado —exactamente la
 * diferencia entre {@link CalculoDeDeuda#deudaActualizadaA} y {@link CalculoDeDeuda#asentadoA}, la
 * misma que {@link RegistroDeAbonosCuentaCorriente} cristaliza al cobrar—. Hoy esa diferencia es
 * cero, porque la unica {@code PoliticaDeMora} implementada es la que no acumula nada (D-02a); el
 * dia que deje de serlo, esto es lo que evita una condonacion silenciosa en todo el padron.
 *
 * <h2>Devolver no es reversar</h2>
 *
 * <p>{@link #devolver} mueve al reves <b>lo pendiente ahora</b>, no lo que se acogio entonces.
 * Reversar los asientos del acogimiento —que es la salida comoda, y la que {@code reversarAbonos}
 * ya ofrece— devolveria a la fase de origen tambien lo que entretanto se hubiera pagado, y el
 * contribuyente acabaria debiendo otra vez lo que ya pago.
 */
@Service
public class AcogimientoAConvenioCuentaCorriente implements AcogimientoAConvenio {

    /** La fase a la que se acoge, y de la que se devuelve. */
    private static final Fase FASE_DEL_CONVENIO = Fase.CONVENIO;

    /** Las cuatro partes del desglose, en el orden en que se leen y se cristalizan. */
    private static final List<Concepto> PARTES =
            List.of(Concepto.INSOLUTO, Concepto.REAJUSTE, Concepto.INTERES, Concepto.GASTO);

    private final AsientoRepository asientos;
    private final SaldoRepository saldos;
    private final RegistrarAsiento registrar;
    private final CalculoDeDeuda calculo;
    private final PoliticaDeRedondeo redondeo;

    public AcogimientoAConvenioCuentaCorriente(
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
    @Transactional(readOnly = true)
    public List<DeudaAcogida> deudaAcogible(
            long contribuyenteId,
            List<SeleccionDeObligacion> obligaciones,
            LocalDate fechaDeCorte) {

        if (obligaciones.isEmpty()) {
            throw new IllegalArgumentException(
                    "Un convenio acoge deuda concreta: no se puede fraccionar sin marcar ninguna"
                            + " obligacion");
        }
        Set<SeleccionDeObligacion> sinRepetir = new LinkedHashSet<>(obligaciones);
        if (sinRepetir.size() != obligaciones.size()) {
            throw new IllegalArgumentException(
                    "La misma obligacion viene marcada dos veces: acogerla dos veces la contaria"
                            + " dos veces en el cronograma");
        }

        List<DeudaAcogida> acogibles = new ArrayList<>();
        for (SeleccionDeObligacion seleccion : sinRepetir) {
            ClaveDeObligacion obligacion = claveDe(contribuyenteId, seleccion);
            for (SaldoProyectado fila : saldos.deLaObligacion(obligacion)) {
                DeudaActualizada deuda =
                        calculo.deudaActualizadaA(
                                asientos.deLaObligacion(fila.clave()), fechaDeCorte, redondeo);
                if (!deuda.total().esPositivo()) {
                    continue;
                }
                acogibles.add(filaDe(fila.clave(), fila.fase().name(), fechaDeCorte, deuda));
            }
        }
        acogibles.sort(ORDEN_ESTABLE);
        return List.copyOf(acogibles);
    }

    @Override
    @Transactional
    public MovimientoAsentado acoger(
            long contribuyenteId,
            List<DeudaAcogida> acogidas,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion) {
        return mover(contribuyenteId, acogidas, fecha, documentoOrigen, observacion, true);
    }

    @Override
    @Transactional
    public MovimientoAsentado devolver(
            long contribuyenteId,
            List<DeudaAcogida> acogidas,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion) {
        return mover(contribuyenteId, acogidas, fecha, documentoOrigen, observacion, false);
    }

    // ------------------------------------------------------------------

    /**
     * El motor de los dos sentidos.
     *
     * <p>Uno solo, y con una bandera, porque acoger y devolver son <b>la misma operacion con las
     * fases intercambiadas</b>. Escribirlos por separado dejaria dos copias de la cristalizacion
     * del devengo y del orden de los candados, y la primera que alguien tocara dejaria de ser
     * simetrica de la otra: el quiebre devolveria una cifra distinta de la que el acogimiento
     * movio, y nadie sabria cual de las dos esta bien.
     *
     * @param haciaElConvenio {@code true} acoge (de la fase de origen a CONVENIO); {@code false}
     *     devuelve (de CONVENIO a la fase de origen)
     */
    private MovimientoAsentado mover(
            long contribuyenteId,
            List<DeudaAcogida> acogidas,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion,
            boolean haciaElConvenio) {

        if (acogidas.isEmpty()) {
            throw new IllegalArgumentException(
                    "No hay ninguna cuota que mover: un convenio sin deuda acogida no es un"
                            + " convenio");
        }

        // Bloquear TODO antes de leer nada, y en el mismo orden estable que usa la
        // cobranza: dos operaciones que se solapan tienen que pedir los mismos
        // candados en el mismo orden, o se abrazan y las dos esperan.
        List<DeudaAcogida> enOrden = new ArrayList<>(acogidas);
        enOrden.sort(ORDEN_ESTABLE);
        Set<ClaveDeObligacion> bloqueadas = new LinkedHashSet<>();
        for (DeudaAcogida cuota : enOrden) {
            ClaveDeObligacion obligacion = ClaveDeObligacion.de(claveDe(contribuyenteId, cuota));
            if (bloqueadas.add(obligacion)) {
                saldos.bloquear(obligacion);
            }
        }

        List<DeudaAcogida> movidas = new ArrayList<>();
        int escritos = 0;
        for (DeudaAcogida cuota : enOrden) {
            ClaveDeSaldo clave = claveDe(contribuyenteId, cuota);
            List<Asiento> delLibro = asientos.deLaObligacion(clave);

            DeudaActualizada pendiente = calculo.deudaActualizadaA(delLibro, fecha, redondeo);
            if (!pendiente.total().esPositivo()) {
                continue;
            }
            DeudaActualizada yaAsentado = calculo.asentadoA(delLibro, fecha);

            Fase salida = haciaElConvenio ? Fase.valueOf(cuota.faseOrigen()) : FASE_DEL_CONVENIO;
            Fase entrada = haciaElConvenio ? FASE_DEL_CONVENIO : Fase.valueOf(cuota.faseOrigen());

            // 1. El devengo que todavia no estaba en el libro, cristalizado en la fase
            //    de la que se sale. Sin esto, el asiento del par mueve el «ultimo
            //    movimiento» hacia adelante y el interes acumulado hasta hoy se pierde.
            for (Concepto parte : PARTES) {
                Dinero sinAsentar = parteDe(pendiente, parte).menos(parteDe(yaAsentado, parte));
                if (sinAsentar.esPositivo()) {
                    asentar(
                            clave,
                            salida,
                            parte,
                            TipoAsiento.CARGO,
                            sinAsentar,
                            fecha,
                            documentoOrigen,
                            observacion);
                    escritos++;
                }
            }

            // 2. El par que mueve la fase. El total no cambia: FRACCIONAMIENTO no es
            //    ninguna de las cuatro partes que `deudaActualizadaA` netea.
            Dinero total = pendiente.total();
            asentar(
                    clave,
                    salida,
                    Concepto.FRACCIONAMIENTO,
                    TipoAsiento.ABONO,
                    total,
                    fecha,
                    documentoOrigen,
                    observacion);
            asentar(
                    clave,
                    entrada,
                    Concepto.FRACCIONAMIENTO,
                    TipoAsiento.CARGO,
                    total,
                    fecha,
                    documentoOrigen,
                    observacion);
            escritos += 2;

            movidas.add(filaDe(clave, cuota.faseOrigen(), fecha, pendiente));
        }

        return new MovimientoAsentado(movidas, escritos, fecha);
    }

    private void asentar(
            ClaveDeSaldo cuota,
            Fase fase,
            Concepto concepto,
            TipoAsiento tipo,
            Dinero monto,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion) {
        registrar.asentar(
                Asiento.nuevo(
                        cuota.ejercicio(),
                        cuota.contribuyenteId(),
                        cuota.tributo(),
                        concepto,
                        tipo,
                        fase,
                        // 0 en la proyeccion significa «anual», y en el asiento eso es nulo:
                        // es la traduccion inversa de ClaveDeSaldo.de(Asiento).
                        cuota.periodo() == 0 ? null : cuota.periodo(),
                        cuota.predioId(),
                        cuota.vehiculoId(),
                        null,
                        monto,
                        fecha,
                        documentoOrigen),
                observacion);
    }

    private static DeudaAcogida filaDe(
            ClaveDeSaldo clave, String faseOrigen, LocalDate fecha, DeudaActualizada deuda) {
        return new DeudaAcogida(
                clave.tributo(),
                clave.ejercicio(),
                clave.periodo(),
                clave.predioId(),
                clave.vehiculoId(),
                faseOrigen,
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

    /**
     * La clave de una cuota acogida.
     *
     * <p>El titular entra por la firma y no dentro de {@link DeudaAcogida}: un convenio es de un
     * contribuyente, y repetirlo en cada fila invitaria a que alguien mandara una lista con dos
     * titulares distintos y a que este metodo tuviera que decidir cual gana.
     */
    private static ClaveDeSaldo claveDe(long contribuyenteId, DeudaAcogida cuota) {
        return new ClaveDeSaldo(
                contribuyenteId,
                cuota.tributo(),
                cuota.ejercicio(),
                cuota.periodo(),
                cuota.predioId(),
                cuota.vehiculoId());
    }

    private static ClaveDeObligacion claveDe(
            long contribuyenteId, SeleccionDeObligacion seleccion) {
        return new ClaveDeObligacion(
                contribuyenteId,
                seleccion.tributo(),
                seleccion.ejercicio(),
                seleccion.predioId(),
                seleccion.vehiculoId());
    }

    /** El orden en que se piden los candados. Total y estable: no depende de nulos ni del mapa. */
    private static final Comparator<DeudaAcogida> ORDEN_ESTABLE =
            Comparator.comparing(DeudaAcogida::tributo)
                    .thenComparingInt(cuota -> cuota.ejercicio().valor())
                    .thenComparingInt(DeudaAcogida::periodo)
                    .thenComparingLong(cuota -> cuota.predioId() == null ? 0L : cuota.predioId())
                    .thenComparingLong(
                            cuota -> cuota.vehiculoId() == null ? 0L : cuota.vehiculoId());
}
