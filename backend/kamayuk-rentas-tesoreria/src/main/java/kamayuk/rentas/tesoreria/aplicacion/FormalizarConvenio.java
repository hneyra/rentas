package kamayuk.rentas.tesoreria.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.cuentacorriente.AcogimientoAConvenio;
import kamayuk.rentas.cuentacorriente.MovimientoAsentado;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.ConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.EstadoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.NumeroDeConvenio;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El cobro de la cuota inicial pone el convenio en vigor y acoge su deuda (#35, RF-084).
 *
 * <h2>Sin cuota inicial pagada en caja no hay convenio</h2>
 *
 * <p>Es el criterio de aceptacion central de #35, y no es una comprobacion: es la
 * <b>estructura</b>. Un preconvenio no acoge nada; lo que acoge es este caso de uso, y a este caso
 * de uso solo se llega desde la caja, dentro de la transaccion que emite el recibo ({@code
 * CobrarDeuda}). No hay ningun camino que ponga un convenio en vigor sin recibo, porque {@code
 * convenio_movimiento_formalizacion_ck} exige el {@code recibo_id} en la propia base.
 *
 * <p>Y por eso corre en la <b>misma transaccion</b> que la cobranza: o el recibo y el acogimiento
 * caen juntos, o no cae ninguno. Un recibo de inicial sin acogimiento deja al contribuyente con un
 * papel que dice que su convenio existe y a la municipalidad con la deuda todavia en cobranza
 * ordinaria; un acogimiento sin recibo saca la deuda de la cobranza sin que haya entrado un sol.
 *
 * <h2>Se acoge lo pendiente, no lo simulado</h2>
 *
 * <p>El preconvenio congelo la deuda a su fecha de corte. Entre la simulacion y la firma pueden
 * pasar dias: la deuda devenga, o el contribuyente paga una cuota por ventanilla. Lo que se mueve a
 * fase de convenio es <b>lo que el libro dice hoy</b> —{@link AcogimientoAConvenio#acoger} lo relee
 * con las filas bloqueadas—, y lo movido se congela en el movimiento. La cifra del preconvenio
 * sigue donde estaba, con su fecha, porque es lo que explica el cronograma que se firmo.
 *
 * <h2>Formalizar dos veces es imposible</h2>
 *
 * <p>{@code convenio_movimiento_formalizacion_uq}, un indice unico parcial. La lectura previa del
 * estado esta para dar un mensaje util y para no acoger en balde; la garantia es el indice, porque
 * dos peticiones simultaneas pasan las dos por cualquier comprobacion escrita en Java —y dos
 * acogimientos dejarian la deuda contada dos veces en fase de convenio—.
 */
@Service
public class FormalizarConvenio {

    private final ConvenioRepository convenios;
    private final MovimientoDeConvenioRepository movimientos;
    private final AcogimientoAConvenio acogimiento;
    private final Auditoria auditoria;
    private final Clock reloj;

    public FormalizarConvenio(
            ConvenioRepository convenios,
            MovimientoDeConvenioRepository movimientos,
            AcogimientoAConvenio acogimiento,
            Auditoria auditoria,
            Clock reloj) {
        this.convenios = convenios;
        this.movimientos = movimientos;
        this.acogimiento = acogimiento;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * La cuota inicial que el cronograma de ese convenio congelo.
     *
     * <p>La caja la necesita <b>antes</b> de emitir el recibo —el papel dice cuanto se cobro— y
     * sale de aqui y no de la peticion: si el importe viajara desde la ventanilla, un cliente
     * podria formalizar un convenio de diez mil soles pagando uno.
     *
     * @throws ConvenioInexistente si no hay ningun convenio con ese numero
     */
    @Transactional(readOnly = true)
    public Dinero cuotaInicialDe(NumeroDeConvenio numero) {
        return convenios
                .porNumero(numero)
                .orElseThrow(() -> new ConvenioInexistente(numero))
                .cuotaInicial();
    }

    /**
     * Acoge la deuda del convenio y registra su formalizacion.
     *
     * <p>{@code REQUIRED} y no {@code REQUIRES_NEW}: se une a la transaccion de la cobranza que la
     * llama, que es justo lo que hace atomicas las dos mitades.
     *
     * @param numero el convenio que se formaliza
     * @param reciboId el recibo que cobro la inicial
     * @param importeCobrado lo que ese recibo cobro; tiene que ser la cuota inicial del cronograma
     * @param fecha la fecha de pago, que es la fecha valor de los asientos
     * @param observacion por que se formaliza (regla 10, RNF-052)
     * @throws ConvenioInexistente si no hay ningun convenio con ese numero
     * @throws ConvenioNoEsPreconvenio si ya estaba formalizado o cerrado
     * @throws LaInicialNoCuadra si lo cobrado no es la cuota inicial del cronograma
     * @throws SinDeudaQueAcoger si la deuda del preconvenio ya no existe
     */
    @Transactional
    public Formalizado formalizar(
            NumeroDeConvenio numero,
            long reciboId,
            Dinero importeCobrado,
            LocalDate fecha,
            Observacion observacion) {

        Objects.requireNonNull(numero, "Se formaliza un convenio concreto, por su numero");
        Objects.requireNonNull(importeCobrado, "La formalizacion dice cuanto se cobro");
        Objects.requireNonNull(fecha, "La fecha de pago entra como argumento (regla 6)");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        Convenio convenio =
                convenios.porNumero(numero).orElseThrow(() -> new ConvenioInexistente(numero));
        long convenioId = convenio.idGuardado();

        EstadoDeConvenio estado =
                EstadoDeConvenio.deLosMovimientos(movimientos.deConvenio(convenioId));
        if (!estado.esPreconvenio()) {
            throw new ConvenioNoEsPreconvenio(numero, estado);
        }

        // La inicial se compara centimo a centimo con la del cronograma congelado. Sin
        // esto, un recibo por un sol formalizaria un convenio de diez mil: el papel
        // diria una cosa y el compromiso firmado otra.
        Dinero inicial = convenio.cuotaInicial();
        if (!importeCobrado.equals(inicial)) {
            throw new LaInicialNoCuadra(numero, inicial, importeCobrado);
        }

        MovimientoAsentado acogido =
                acogimiento.acoger(
                        convenio.contribuyenteId(),
                        convenio.acogida(),
                        fecha,
                        documentoDelConvenio(numero),
                        observacion);
        if (acogido.estaVacio()) {
            throw new SinDeudaQueAcoger(numero, fecha);
        }

        MovimientoDeConvenio formalizacion =
                movimientos.registrar(
                        MovimientoDeConvenio.formalizacion(
                                convenioId,
                                fecha,
                                reciboId,
                                CUOTA_INICIAL,
                                acogido.importe(),
                                acogido.asientos(),
                                reloj.instant(),
                                observacion),
                        // Sin clave de idempotencia: la formalizacion entra por la caja y su
                        // reenvio lo para `recibo_idempotencia_uq` (V29), antes de llegar aqui.
                        null);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fecha,
                                "convenio_movimiento",
                                String.valueOf(formalizacion.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(numero, formalizacion)));

        return new Formalizado(convenio, formalizacion, acogido);
    }

    // ------------------------------------------------------------------

    /** La cuota inicial es la 0 del cronograma. */
    private static final int CUOTA_INICIAL = 0;

    /**
     * Como se marcan en el libro los asientos del acogimiento.
     *
     * <p>Es lo unico que los relaciona entre si, igual que {@code "RECIBO 001-0000123"} relaciona
     * los de una cobranza (#33). {@code asiento_documento_origen_ix} (V30) es lo que hace que
     * encontrarlos sea una lectura de indice por particion y no un recorrido completo del libro.
     */
    public static String documentoDelConvenio(NumeroDeConvenio numero) {
        return "CONVENIO " + numero.impreso();
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(NumeroDeConvenio numero, MovimientoDeConvenio movimiento) {
        return "{\"numero\":\""
                + numero.impreso()
                + "\",\"tipo\":\""
                + movimiento.tipo()
                + "\",\"importe\":"
                + movimiento.importe().valor().toPlainString()
                + ",\"asientos\":"
                + movimiento.asientos()
                + ",\"fecha\":\""
                + movimiento.fecha()
                + "\"}";
    }

    /**
     * El convenio formalizado, con lo que se movio.
     *
     * @param convenio el convenio, intacto: su cronograma sigue donde estaba
     * @param formalizacion la fila que se agrego
     * @param acogido lo que de verdad se movio a fase de convenio, con su fecha
     */
    public record Formalizado(
            Convenio convenio, MovimientoDeConvenio formalizacion, MovimientoAsentado acogido) {}

    /** No hay ningun convenio con ese numero en esta municipalidad. */
    public static final class ConvenioInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ConvenioInexistente(NumeroDeConvenio numero) {
            super("No hay ningun convenio " + numero.impreso() + " en esta municipalidad");
        }
    }

    /** El convenio ya no espera su cuota inicial: o esta vigente, o esta cerrado. */
    public static final class ConvenioNoEsPreconvenio extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ConvenioNoEsPreconvenio(NumeroDeConvenio numero, EstadoDeConvenio estado) {
            super(
                    "El convenio "
                            + numero.impreso()
                            + " esta "
                            + estado
                            + ": solo un preconvenio se formaliza, y formalizar el que ya lo esta"
                            + " acogeria su deuda por segunda vez");
        }
    }

    /** Lo cobrado no es la cuota inicial que el cronograma dice. */
    public static final class LaInicialNoCuadra extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        LaInicialNoCuadra(NumeroDeConvenio numero, Dinero delCronograma, Dinero cobrado) {
            super(
                    "El convenio "
                            + numero.impreso()
                            + " tiene una cuota inicial de "
                            + delCronograma
                            + " y el recibo cobro "
                            + cobrado
                            + ": formalizarlo dejaria el compromiso firmado diciendo una cosa y la"
                            + " caja otra");
        }
    }

    /** La deuda que el preconvenio congelo ya no esta pendiente. */
    public static final class SinDeudaQueAcoger extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinDeudaQueAcoger(NumeroDeConvenio numero, LocalDate fecha) {
            super(
                    "La deuda que el convenio "
                            + numero.impreso()
                            + " acogia ya no esta pendiente al "
                            + fecha
                            + ": se pago entre la firma y el cobro de la inicial. Lo que"
                            + " corresponde es un preconvenio nuevo, no acoger un saldo que no"
                            + " existe");
        }
    }
}
