package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kamayuk.rentas.cuentacorriente.AbonoAsentado;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.ReversionDeAbonos;
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
 * Implementa {@link RegistroDeAbonos} (#33).
 *
 * <h2>El cargo antes del abono</h2>
 *
 * <p>Lo menos evidente de este servicio, y lo que mas cuesta si se omite: al cobrar hay que asentar
 * primero el <b>cargo</b> del reajuste y del interes devengados.
 *
 * <p>El motivo esta en ADR-0012 y lo repite {@link CalculoDeDeuda}: «el interes se calcula, no se
 * asienta». Es decir, la parte de interes de {@code deudaActualizadaA} no existe en el libro: la
 * produce la {@code PoliticaDeMora} cada vez que se pregunta. Si se abonara sin haberla cargado
 * antes, {@code netear(INTERES)} quedaria en negativo <b>para siempre</b> y esa obligacion
 * mostraria deuda negativa cada vez que alguien la consultara. El sintoma aparece semanas despues,
 * en una constancia de no adeudo que no cuadra, y para entonces hay miles de recibos iguales.
 *
 * <p>La diferencia entre {@link CalculoDeDeuda#deudaActualizadaA} y {@link
 * CalculoDeDeuda#asentadoA} es exactamente lo que hay que cargar. Cuando el dinero entra, el
 * devengo deja de ser una proyeccion y pasa a ser un hecho del libro; por eso el cargo se asienta
 * con la misma fecha valor que el abono y con el mismo documento de origen: el recibo explica las
 * dos filas.
 *
 * <h2>Por cuota, no por obligacion</h2>
 *
 * <p>El cajero marca «predial 2026 del predio 7». El libro cuenta por cuota, y cada cuota puede
 * estar en una fase distinta —una en coactiva, dos ordinarias—. Los asientos se escriben cuota por
 * cuota, con <b>su</b> periodo y <b>su</b> fase; abonarlo todo en fase ordinaria dejaria la cuota
 * de coactiva intacta y la ordinaria en negativo, y el expediente coactivo seguiria vivo sobre una
 * deuda ya cobrada.
 */
@Service
public class RegistroDeAbonosCuentaCorriente implements RegistroDeAbonos {

    /** Las cuatro partes del desglose, en el orden en que se imputan. */
    private static final List<Concepto> PARTES =
            List.of(Concepto.INSOLUTO, Concepto.REAJUSTE, Concepto.INTERES, Concepto.GASTO);

    private final AsientoRepository asientos;
    private final SaldoRepository saldos;
    private final RegistrarAsiento registrar;
    private final CalculoDeDeuda calculo;
    private final PoliticaDeRedondeo redondeo;

    public RegistroDeAbonosCuentaCorriente(
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
    public List<AbonoAsentado> abonarPagoIntegro(
            long contribuyenteId,
            List<SeleccionDeObligacion> obligaciones,
            LocalDate fechaDePago,
            String documentoOrigen,
            Observacion observacion) {

        if (obligaciones.isEmpty()) {
            throw new IllegalArgumentException("No se puede abonar sin marcar ninguna obligacion");
        }
        Set<SeleccionDeObligacion> sinRepetir = new LinkedHashSet<>(obligaciones);
        if (sinRepetir.size() != obligaciones.size()) {
            throw new IllegalArgumentException(
                    "La misma obligacion viene marcada dos veces: cobrarla dos veces en el mismo"
                            + " recibo es cobrarla de mas");
        }

        // 1. Bloquear TODO antes de leer nada, y en un orden que no dependa de como
        //    llego la seleccion: dos cobranzas que se solapan tienen que pedir los
        //    mismos candados en el mismo orden, o se abrazan y las dos esperan.
        List<ClaveDeObligacion> aBloquear =
                sinRepetir.stream()
                        .map(seleccion -> claveDe(contribuyenteId, seleccion))
                        .sorted(ORDEN_ESTABLE)
                        .toList();
        for (ClaveDeObligacion clave : aBloquear) {
            saldos.bloquear(clave);
        }

        // 2. Ya con los candados puestos, releer el libro y asentar.
        List<AbonoAsentado> abonados = new ArrayList<>();
        for (SeleccionDeObligacion seleccion : sinRepetir) {
            AbonoAsentado abono =
                    abonarUna(
                            claveDe(contribuyenteId, seleccion),
                            seleccion,
                            fechaDePago,
                            documentoOrigen,
                            observacion);
            if (abono != null) {
                abonados.add(abono);
            }
        }

        if (abonados.isEmpty()) {
            throw new SinDeudaQueAbonar(
                    "Ninguna de las "
                            + sinRepetir.size()
                            + " obligaciones marcadas tenia deuda al "
                            + fechaDePago
                            + ": o ya se pagaron, o nunca se determinaron");
        }
        return List.copyOf(abonados);
    }

    /**
     * Deshace los abonos de un documento asentando su reversion (#34, RF-083).
     *
     * <p>Lo que hace que la deuda vuelva a estar pendiente no es escribir la cifra en ningun sitio:
     * es que {@link CalculoDeDeuda#deudaActualizadaA} netea cargos contra abonos, y al quedar el
     * abono compensado por su reverso el neteo vuelve a dar lo que daba. Por eso aqui no hay
     * ninguna suma de deuda —solo se recorre lo que la cobranza escribio y se reversa fila a fila—,
     * y por eso reversar tambien los <b>cargos</b> es obligatorio: al cobrar se cristalizo el
     * devengo con un cargo, y dejarlo vivo dejaria a la obligacion debiendo un interes que ya no
     * corresponde.
     *
     * <p>{@link RegistrarAsiento#reversar} reproyecta el saldo de cada obligacion tocada, en esta
     * misma transaccion. O vuelven la deuda y su proyeccion, o no vuelve ninguna de las dos.
     */
    @Override
    @Transactional
    public ReversionDeAbonos reversarAbonos(
            String documentoOrigen,
            String documentoDeLaReversion,
            LocalDate fecha,
            Observacion observacion) {

        if (documentoOrigen.strip().equalsIgnoreCase(documentoDeLaReversion.strip())) {
            throw new IllegalArgumentException(
                    "La reversion tiene que llevar un documento de origen distinto del que"
                            + " reversa; con el mismo, una segunda anulacion encontraria los"
                            + " asientos de la primera y reversaria la reversion");
        }

        List<Asiento> delDocumento = asientos.porDocumentoOrigen(documentoOrigen);
        if (delDocumento.isEmpty()) {
            throw new SinAbonosQueReversar(
                    "El documento '"
                            + documentoOrigen
                            + "' no origino ningun asiento reversable: o nunca toco el libro, o sus"
                            + " abonos ya se reversaron");
        }

        Dinero abonado = Dinero.CERO;
        for (Asiento original : delDocumento) {
            registrar.reversar(
                    java.util.Objects.requireNonNull(original.id()),
                    fecha,
                    documentoDeLaReversion,
                    observacion);
            if (original.tipo() == TipoAsiento.ABONO) {
                abonado = abonado.mas(original.monto());
            }
        }
        return new ReversionDeAbonos(delDocumento.size(), abonado, fecha);
    }

    // ------------------------------------------------------------------

    /**
     * Una obligacion completa: todas sus cuotas con deuda. Devuelve {@code null} si no tenia
     * ninguna —eso no es un error por si solo: el error es que <b>ninguna</b> de las marcadas la
     * tuviera, y eso lo decide quien llama—.
     */
    private @org.jspecify.annotations.Nullable AbonoAsentado abonarUna(
            ClaveDeObligacion obligacion,
            SeleccionDeObligacion seleccion,
            LocalDate fechaDePago,
            String documentoOrigen,
            Observacion observacion) {

        Dinero insoluto = Dinero.CERO;
        Dinero reajuste = Dinero.CERO;
        Dinero interes = Dinero.CERO;
        Dinero gasto = Dinero.CERO;

        for (SaldoProyectado fila : saldos.deLaObligacion(obligacion)) {
            ClaveDeSaldo cuota = fila.clave();
            List<Asiento> delLibro = asientos.deLaObligacion(cuota);

            DeudaActualizada cobrable = calculo.deudaActualizadaA(delLibro, fechaDePago, redondeo);
            DeudaActualizada yaAsentado = calculo.asentadoA(delLibro, fechaDePago);

            if (!cobrable.total().esPositivo()) {
                continue;
            }

            for (Concepto parte : PARTES) {
                Dinero aCobrar = parteDe(cobrable, parte);
                if (!aCobrar.esPositivo()) {
                    continue;
                }
                Dinero devengadoSinAsentar = aCobrar.menos(parteDe(yaAsentado, parte));
                if (devengadoSinAsentar.esPositivo()) {
                    asentar(
                            cuota,
                            fila.fase(),
                            parte,
                            TipoAsiento.CARGO,
                            devengadoSinAsentar,
                            fechaDePago,
                            documentoOrigen,
                            observacion);
                }
                asentar(
                        cuota,
                        fila.fase(),
                        parte,
                        TipoAsiento.ABONO,
                        aCobrar,
                        fechaDePago,
                        documentoOrigen,
                        observacion);
            }

            insoluto = insoluto.mas(cobrable.insoluto());
            reajuste = reajuste.mas(cobrable.reajuste());
            interes = interes.mas(cobrable.interes());
            gasto = gasto.mas(cobrable.gasto());
        }

        Dinero total = insoluto.mas(reajuste).mas(interes).mas(gasto);
        return total.esPositivo()
                ? new AbonoAsentado(seleccion, fechaDePago, insoluto, reajuste, interes, gasto)
                : null;
    }

    private void asentar(
            ClaveDeSaldo cuota,
            Fase fase,
            Concepto concepto,
            TipoAsiento tipo,
            Dinero monto,
            LocalDate fechaDePago,
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
                        fechaDePago,
                        documentoOrigen),
                observacion);
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
    private static final Comparator<ClaveDeObligacion> ORDEN_ESTABLE =
            Comparator.comparing(ClaveDeObligacion::tributo)
                    .thenComparingInt(clave -> clave.ejercicio().valor())
                    .thenComparingLong(clave -> clave.predioId() == null ? 0L : clave.predioId())
                    .thenComparingLong(
                            clave -> clave.vehiculoId() == null ? 0L : clave.vehiculoId());
}
