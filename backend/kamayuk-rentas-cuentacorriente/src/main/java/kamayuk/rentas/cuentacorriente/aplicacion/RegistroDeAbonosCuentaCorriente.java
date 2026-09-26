package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kamayuk.rentas.cuentacorriente.AbonoAsentado;
import kamayuk.rentas.cuentacorriente.ObligacionDelDeudor;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.ReversionDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
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
 * <p>Desde #365 esa diferencia la mide {@link
 * kamayuk.rentas.cuentacorriente.dominio.CristalizacionDelDevengo}, y no un bucle de aqui: la
 * invariante es del libro, no de este llamador, y la cumplen los seis caminos que escriben en el
 * con una sola cuenta.
 *
 * <h2>Primero se planifica, despues se compara, y solo entonces se escribe (#39)</h2>
 *
 * <p>El recorrido esta partido en dos a proposito. {@link #planificarUna} <b>lee</b> el libro y
 * compone los asientos que harian falta sin escribir ninguno; {@link #abonarPagoIntegro} suma lo
 * que esos asientos extinguirian y lo compara con lo que la caja cobro de verdad. Si no coinciden
 * al centimo lanza {@code ImporteCobradoNoCuadra} y <b>no se escribe una sola fila</b>.
 *
 * <p>Antes de #39 el recorrido asentaba a medida que leia, asi que no existia ningun momento en el
 * que estuviera decidido cuanto se iba a extinguir y todavia no se hubiera extinguido — y ese
 * momento es el unico sitio donde la comprobacion cabe. Dejar que la excepcion deshiciera la
 * transaccion daria el mismo resultado visible, pero escribiria en el libro para borrarlo despues,
 * que es lo contrario de lo que ADR-0006 pide de este camino.
 *
 * <h2>Lo que queda por extinguir desde la fecha de pago, no lo que se debia a ella (#471)</h2>
 *
 * <p>Lo cobrable de cada cuota es {@link CalculoDeDeuda#extinguibleDesde}, la misma respuesta que
 * #445 dejo para la baja, su reparto y la extincion. {@code deudaActualizadaA(fechaDePago)}
 * contesta otra pregunta —cuanto se debia ese dia— y descarta todo asiento posterior al corte: un
 * pago que la caja entrega tarde, fechado antes de otro cobro que ya esta en el libro, volvia a
 * abonar la cuota que ese cobro extinguio y la dejaba en negativo. La guarda de #39 no lo paraba,
 * porque comparaba lo cobrado con esa misma cifra y cuadraba. Sin nada posterior a la fecha de pago
 * —el cobro de ventanilla del dia, que es el de siempre— las dos dan el mismo centimo.
 *
 * <h2>Por cuota, no por obligacion</h2>
 *
 * <p>El cajero marca «predial 2026 del predio 7». El libro cuenta por cuota, y cada cuota puede
 * estar en una fase distinta —una en coactiva, dos ordinarias—. Los asientos se escriben cuota por
 * cuota, con <b>su</b> periodo y <b>su</b> fase; abonarlo todo en fase ordinaria dejaria la cuota
 * de coactiva intacta y la ordinaria en negativo, y el expediente coactivo seguiria vivo sobre una
 * deuda ya cobrada.
 *
 * <h2>Cada linea con su deudor (#431)</h2>
 *
 * <p>La clave de cada obligacion se compone con el deudor <b>de su linea</b>, no con uno comun a
 * todo el cobro: un recibo de caja junta ordenes de deudores distintos. Lo que sigue siendo uno es
 * la comprobacion de #39 —sobre el total, antes de escribir— y el orden de los candados, que
 * desempata por el deudor para seguir siendo total cuando dos condominos comparten el resto de la
 * clave. Ese orden no vive aqui desde #364: es {@link SaldoRepository#bloquearEnOrden}.
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
    private final CristalizacionDelDevengo cristalizacion;
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
        this.cristalizacion = new CristalizacionDelDevengo(calculo);
        this.redondeo = redondeo;
    }

    @Override
    @Transactional
    public List<AbonoAsentado> abonarPagoIntegro(
            List<ObligacionDelDeudor> obligaciones,
            Dinero cobrado,
            LocalDate fechaDePago,
            String documentoOrigen,
            Observacion observacion) {

        java.util.Objects.requireNonNull(
                cobrado, "El abono se comprueba contra lo que la caja cobro (#39)");
        if (obligaciones.isEmpty()) {
            throw new IllegalArgumentException("No se puede abonar sin marcar ninguna obligacion");
        }
        // Repetida es el mismo PAR: dos condominos del mismo predio comparten la seleccion y son
        // dos obligaciones distintas del libro (#431).
        Set<ObligacionDelDeudor> sinRepetir = new LinkedHashSet<>(obligaciones);
        if (sinRepetir.size() != obligaciones.size()) {
            throw new IllegalArgumentException(
                    "La misma obligacion del mismo deudor viene marcada dos veces: cobrarla dos"
                            + " veces en el mismo recibo es cobrarla de mas");
        }

        // 1. Bloquear TODO antes de leer nada. El orden no lo decide este servicio: lo pone
        //    `bloquearEnOrden`, el mismo para la cobranza y para el convenio (#364), y no
        //    depende de como llego la seleccion.
        saldos.bloquearEnOrden(
                sinRepetir.stream().map(RegistroDeAbonosCuentaCorriente::claveDe).toList());

        // 2. Ya con los candados puestos, releer el libro y PLANIFICAR. Todavia no se escribe
        //    nada, y el orden importa: el AC-2 de #39 pide comparar ANTES de asentar. Podria
        //    dejarse que la excepcion deshiciera la transaccion —el rechazo se escribe en una
        //    nueva, que es justo para lo que existe `RechazoDelPago`— pero escribir en el libro
        //    para deshacerlo despues es lo contrario de lo que ADR-0006 pide de este camino.
        List<PlanDeAbono> planes = new ArrayList<>();
        Dinero segunElLibro = Dinero.CERO;
        for (ObligacionDelDeudor marcada : sinRepetir) {
            PlanDeAbono plan = planificarUna(claveDe(marcada), marcada, fechaDePago);
            if (plan != null) {
                planes.add(plan);
                segunElLibro = segunElLibro.mas(plan.resumen().total());
            }
        }

        if (planes.isEmpty()) {
            throw new SinDeudaQueAbonar(
                    "Ninguna de las "
                            + sinRepetir.size()
                            + " obligaciones marcadas tenia deuda al "
                            + fechaDePago
                            + ": o ya se pagaron, o nunca se determinaron");
        }

        // 3. La comprobacion de #39. Se hace contra `cobrado` y no contra lo releido: una
        //    comparacion de `extinguibleDesde(fechaDePago)` consigo misma se cumple siempre y no
        //    protege de nada.
        if (!segunElLibro.equals(cobrado)) {
            throw ImporteCobradoNoCuadra.de(cobrado, segunElLibro, fechaDePago);
        }

        // 4. Y ahora si: se escribe el plan, en el mismo orden en que se leyo.
        List<AbonoAsentado> abonados = new ArrayList<>(planes.size());
        for (PlanDeAbono plan : planes) {
            for (AsientoPlaneado planeado : plan.asientos()) {
                asentar(planeado, fechaDePago, documentoOrigen, observacion);
            }
            abonados.add(plan.resumen());
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
     *
     * <h2>Y antes, el devengo de lo que vuelve a deberse (#365)</h2>
     *
     * <p>Los reversos se asientan con la fecha de la anulacion, y esa fecha pasa a ser el ultimo
     * movimiento de la cuota: desde ahi acumula {@link CalculoDeDeuda#deudaActualizadaA}. Si no se
     * hiciera nada mas, el interes del insoluto que vuelve a deberse entre el cobro anulado y la
     * anulacion se perderia entero —el cargo que el cobro cristalizo se reversa con el, y nadie lo
     * vuelve a devengar—.
     *
     * <p>Anular un cobro es decir que no ocurrio. Por eso lo que se cristaliza, antes de reversar,
     * es el devengo del libro <b>sin</b> los asientos de ese documento, a la fecha de la anulacion:
     * lo que el libro tendria si ese cobro no se hubiera asentado nunca. La cuenta es la de {@link
     * CristalizacionDelDevengo}, la misma de los otros cinco caminos; lo unico propio de este es
     * sobre que asientos se pregunta.
     *
     * <p>Las obligaciones se bloquean antes de leerlas, con {@link SaldoRepository#bloquearEnOrden}
     * como la cobranza: la cuenta lee el libro y luego escribe, y otro acto que se colara en medio
     * cristalizaria el mismo devengo dos veces.
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

        int cristalizados =
                cristalizarSinElDocumento(delDocumento, fecha, documentoDeLaReversion, observacion);

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
        return new ReversionDeAbonos(delDocumento.size() + cristalizados, abonado, fecha);
    }

    /**
     * El devengo que el libro tendria a {@code fecha} si el documento no se hubiera asentado nunca,
     * cuota por cuota, asentado como cargo con el documento de la reversion (#365).
     *
     * <p>El cargo va en la fase del asiento reversado de esa cuota, que es la fase en la que la
     * cobranza abono: asi la reversion y su devengo quedan en la misma fase y la cuota no cambia de
     * fase por cristalizar.
     *
     * @return cuantos cargos se asentaron
     */
    private int cristalizarSinElDocumento(
            List<Asiento> delDocumento,
            LocalDate fecha,
            String documentoDeLaReversion,
            Observacion observacion) {

        Set<Long> delCobro = new java.util.HashSet<>();
        Map<ClaveDeSaldo, Fase> cuotas = new java.util.LinkedHashMap<>();
        for (Asiento asiento : delDocumento) {
            delCobro.add(java.util.Objects.requireNonNull(asiento.id()));
            cuotas.putIfAbsent(ClaveDeSaldo.de(asiento), asiento.fase());
        }

        saldos.bloquearEnOrden(cuotas.keySet().stream().map(ClaveDeObligacion::de).toList());

        int cristalizados = 0;
        for (Map.Entry<ClaveDeSaldo, Fase> cuota : cuotas.entrySet()) {
            List<Asiento> sinElDocumento =
                    asientos.deLaObligacion(cuota.getKey()).stream()
                            .filter(asiento -> !delCobro.contains(asiento.id()))
                            .toList();
            for (CristalizacionDelDevengo.Devengo devengo :
                    cristalizacion.sinAsentar(sinElDocumento, fecha, redondeo)) {
                asentar(
                        new AsientoPlaneado(
                                cuota.getKey(),
                                cuota.getValue(),
                                devengo.parte(),
                                TipoAsiento.CARGO,
                                devengo.monto()),
                        fecha,
                        documentoDeLaReversion,
                        observacion);
                cristalizados++;
            }
        }
        return cristalizados;
    }

    // ------------------------------------------------------------------

    /**
     * Un asiento que <b>todavia no se ha escrito</b>: lo que la planificacion decidio y lo que la
     * escritura replica. Existe para que entre lo uno y lo otro quepa la comprobacion de #39.
     */
    private record AsientoPlaneado(
            ClaveDeSaldo cuota, Fase fase, Concepto concepto, TipoAsiento tipo, Dinero monto) {}

    /**
     * Lo que una obligacion va a dejar en el libro: sus asientos, en orden, y el resumen que se
     * devuelve a quien cobra. {@code resumen} nunca es nulo aqui — un plan sin deuda no se crea.
     */
    private record PlanDeAbono(List<AsientoPlaneado> asientos, AbonoAsentado resumen) {}

    /**
     * Una obligacion completa: todas sus cuotas con deuda. Devuelve {@code null} si no tenia
     * ninguna —eso no es un error por si solo: el error es que <b>ninguna</b> de las marcadas la
     * tuviera, y eso lo decide quien llama—.
     *
     * <p><b>Lee y no escribe</b> (#39). Antes de #39 este metodo asentaba a medida que recorria, y
     * por eso no habia ningun momento en el que estuviera decidido cuanto se iba a extinguir y
     * todavia no se hubiera extinguido. Ese momento es donde vive la comprobacion.
     */
    private @org.jspecify.annotations.Nullable PlanDeAbono planificarUna(
            ClaveDeObligacion obligacion, ObligacionDelDeudor marcada, LocalDate fechaDePago) {

        List<AsientoPlaneado> planeados = new ArrayList<>();
        Dinero insoluto = Dinero.CERO;
        Dinero reajuste = Dinero.CERO;
        Dinero interes = Dinero.CERO;
        Dinero gasto = Dinero.CERO;

        for (SaldoProyectado fila : saldos.deLaObligacion(obligacion)) {
            ClaveDeSaldo cuota = fila.clave();
            List<Asiento> delLibro = asientos.deLaObligacion(cuota);

            // Lo que queda por extinguir DESDE la fecha de pago, no lo que se debia A ella (#471):
            // la caja puede entregar tarde un cobro fechado antes de otro que ya esta en el libro,
            // y medido a su fecha ese abono posterior queda fuera del corte y la cuota se
            // abonaria dos veces. La guarda de #39 no lo para, porque compara con esta misma cifra.
            DeudaActualizada cobrable = calculo.extinguibleDesde(delLibro, fechaDePago, redondeo);

            if (!cobrable.total().esPositivo()) {
                continue;
            }

            // El cargo de lo devengado antes que el abono de cada parte: lo mide la
            // cristalizacion (#365), que es la misma cuenta para los seis caminos que escriben.
            // Aqui solo se planifica; se escribe despues de la comprobacion de #39.
            Map<Concepto, Dinero> devengado =
                    cristalizacion.sinAsentar(delLibro, fechaDePago, redondeo).stream()
                            .collect(
                                    Collectors.toMap(
                                            CristalizacionDelDevengo.Devengo::parte,
                                            CristalizacionDelDevengo.Devengo::monto));
            for (Concepto parte : PARTES) {
                Dinero sinAsentar = devengado.get(parte);
                if (sinAsentar != null) {
                    planeados.add(
                            new AsientoPlaneado(
                                    cuota, fila.fase(), parte, TipoAsiento.CARGO, sinAsentar));
                }
                Dinero aCobrar = parteDe(cobrable, parte);
                if (aCobrar.esPositivo()) {
                    planeados.add(
                            new AsientoPlaneado(
                                    cuota, fila.fase(), parte, TipoAsiento.ABONO, aCobrar));
                }
            }

            insoluto = insoluto.mas(cobrable.insoluto());
            reajuste = reajuste.mas(cobrable.reajuste());
            interes = interes.mas(cobrable.interes());
            gasto = gasto.mas(cobrable.gasto());
        }

        Dinero total = insoluto.mas(reajuste).mas(interes).mas(gasto);
        return total.esPositivo()
                ? new PlanDeAbono(
                        List.copyOf(planeados),
                        new AbonoAsentado(marcada, fechaDePago, insoluto, reajuste, interes, gasto))
                : null;
    }

    private void asentar(
            AsientoPlaneado planeado,
            LocalDate fechaDePago,
            String documentoOrigen,
            Observacion observacion) {
        ClaveDeSaldo cuota = planeado.cuota();
        registrar.asentar(
                Asiento.nuevo(
                        cuota.ejercicio(),
                        cuota.contribuyenteId(),
                        cuota.tributo(),
                        planeado.concepto(),
                        planeado.tipo(),
                        planeado.fase(),
                        // 0 en la proyeccion significa «anual», y en el asiento eso es nulo:
                        // es la traduccion inversa de ClaveDeSaldo.de(Asiento).
                        cuota.periodo() == 0 ? null : cuota.periodo(),
                        cuota.predioId(),
                        cuota.vehiculoId(),
                        null,
                        planeado.monto(),
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

    /** La clave del libro de esa linea: con SU deudor, no con uno comun a todo el cobro (#431). */
    private static ClaveDeObligacion claveDe(ObligacionDelDeudor marcada) {
        SeleccionDeObligacion seleccion = marcada.obligacion();
        return new ClaveDeObligacion(
                marcada.contribuyenteId(),
                seleccion.tributo(),
                seleccion.ejercicio(),
                seleccion.predioId(),
                seleccion.vehiculoId());
    }
}
