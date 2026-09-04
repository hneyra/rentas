package kamayuk.rentas.tesoreria.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.cuentacorriente.AbonoAsentado;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.LineaDeRecibo;
import kamayuk.rentas.tesoreria.dominio.NumeroDeConvenio;
import kamayuk.rentas.tesoreria.dominio.NumeroDeRecibo;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.tesoreria.dominio.ReciboRepository;
import kamayuk.rentas.tesoreria.dominio.TipoDePago;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCaja;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caja tributaria: cobra la deuda marcada en ventanilla y emite su recibo (#33, RF-080).
 *
 * <h2>Lo que este caso de uso NO hace</h2>
 *
 * <p><b>No calcula cuanto se debe.</b> ARQ-01 §3.8: «tesoreria asienta abonos; nunca determina. Si
 * la caja calcula deuda, el sistema tiene dos verdades». Aqui no hay una suma, ni un interes, ni un
 * descuento: hay una lista de obligaciones marcadas que se le pasa a {@link RegistroDeAbonos}, y
 * los importes que vuelven son los que se imprimen. Ni siquiera el total: sale de sumar las lineas
 * que el libro devolvio.
 *
 * <p><b>No aplica beneficios.</b> La campana declarada en ventanilla se guarda en el recibo como
 * constancia y nada mas: su efecto sobre el importe esta bloqueado por D-02b, que es la que firma
 * los valores de ordenanza local con su ratificacion provincial. Un porcentaje inventado seria una
 * condonacion sin sustento normativo repetida en todo un padron.
 *
 * <h2>Atomica</h2>
 *
 * <p>Una transaccion, y dentro de ella: el turno bloqueado, los abonos del libro, el correlativo,
 * el recibo, su detalle y la auditoria. O todo, o nada. Un recibo impreso sin su abono deja al
 * contribuyente con un papel que dice que pago y a la municipalidad con la deuda viva; un abono sin
 * su recibo deja dinero cobrado que no se puede justificar en el arqueo. Las dos mitades tienen que
 * caer juntas, y que puedan hacerlo es exactamente lo que ADR-0002 —monolito modular sobre una
 * base— compra.
 *
 * <h2>Cobrar dos veces es imposible</h2>
 *
 * <p>Tres barreras, y las tres en la base:
 *
 * <ol>
 *   <li>el turno de la caja, bloqueado con {@code FOR UPDATE}: dos peticiones de la misma
 *       ventanilla se ordenan en el motor;
 *   <li>{@code recibo_idempotencia_uq}: el mismo intento reenviado devuelve el recibo de la primera
 *       vez, no emite otro;
 *   <li>y la que cierra el caso general, incluso entre cajas distintas: {@link
 *       RegistroDeAbonos#abonarPagoIntegro} bloquea las filas de saldo y <b>relee el libro</b>. La
 *       segunda cobranza no trabaja sobre una cifra que traiga en la mano: trabaja sobre el libro
 *       que ya tiene dentro el abono de la primera, y por eso no encuentra nada que cobrar.
 * </ol>
 */
@Service
public class CobrarDeuda {

    /** El concepto con el que se rotula una linea de cobranza en {@code recibo_detalle}. */
    private static final String CONCEPTO_PAGO = "PAGO";

    /** El concepto con el que se rotula la linea de una cuota inicial de convenio (#35). */
    private static final String CONCEPTO_CUOTA_INICIAL = "CUOTA INICIAL";

    /** El tributo con el que se rotula esa misma linea. */
    private static final String TRIBUTO_CONVENIO = TributoDelLibro.CONVENIO.texto();

    private final AbrirCaja abrirCaja;
    private final RegistroDeAbonos abonos;
    private final ReciboRepository recibos;
    private final FormalizarConvenio formalizar;
    private final Auditoria auditoria;
    private final Clock reloj;

    public CobrarDeuda(
            AbrirCaja abrirCaja,
            RegistroDeAbonos abonos,
            ReciboRepository recibos,
            FormalizarConvenio formalizar,
            Auditoria auditoria,
            Clock reloj) {
        this.abrirCaja = abrirCaja;
        this.abonos = abonos;
        this.recibos = recibos;
        this.formalizar = formalizar;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Cobra y emite.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de {@link Cobranza}, y no es un capricho
     * de estilo: la regla 10 exige que se vea en el punto donde se escribe, y ArchUnit la comprueba
     * mirando los parametros del metodo transaccional. Escondida dentro de un objeto de peticion,
     * la comprobacion no la encuentra y la regla dejaria de proteger nada.
     *
     * @param peticion lo que el cajero marco
     * @param observacion por que se cobra (regla 10, RNF-052)
     * @throws NadaQueCobrar si ninguna de las obligaciones marcadas tenia deuda a la fecha de pago
     * @throws TipoDePagoNoImplementado si se pide una modalidad que #33 no escribe
     */
    @Transactional
    public Recibo cobrar(Cobranza peticion, Observacion observacion) {
        Objects.requireNonNull(peticion, "No se cobra sin peticion");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        if (peticion.tipoDePago() == TipoDePago.PRECONVENIO) {
            return cobrarLaCuotaInicial(peticion, observacion);
        }
        if (peticion.tipoDePago() != TipoDePago.NORMAL) {
            throw new TipoDePagoNoImplementado(peticion.tipoDePago());
        }

        // 1. La ventanilla, serializada. Todo lo que sigue corre con su turno bloqueado.
        AbrirCaja.Abierta abierta =
                abrirCaja.enLaCaja(
                        peticion.codigoDeCaja(),
                        peticion.cajero(),
                        peticion.fechaDePago(),
                        observacion);

        // 2. El reenvio del mismo intento: se devuelve lo que ya se emitio, sin cobrar otra vez.
        String clave = peticion.claveDeIdempotencia();
        if (clave != null) {
            Optional<Recibo> yaEmitido = recibos.porClaveDeIdempotencia(clave);
            if (yaEmitido.isPresent()) {
                return yaEmitido.get();
            }
        }

        // 3. El numero, antes de asentar: es el documento que explica los asientos, y sin el
        //    el libro tendria filas que solo se pueden rastrear por la auditoria. No deja
        //    huecos si algo falla despues, porque `recibo_correlativo` es una fila -no una
        //    secuencia- y su incremento se revierte con la transaccion.
        NumeroDeRecibo numero = recibos.siguienteNumero(abierta.caja());

        // 4. El libro decide cuanto. Aqui no se suma nada todavia.
        List<AbonoAsentado> abonado;
        try {
            abonado =
                    abonos.abonarPagoIntegro(
                            peticion.contribuyenteId(),
                            peticion.obligaciones(),
                            peticion.fechaDePago(),
                            "RECIBO " + numero.impreso(),
                            observacion);
        } catch (RegistroDeAbonos.SinDeudaQueAbonar sinDeuda) {
            throw new NadaQueCobrar(sinDeuda);
        }

        // 5. El recibo, con el desglose que el libro devolvio.
        TurnoDeCaja turno = abierta.turno();
        Recibo recibo =
                new Recibo(
                        null,
                        numero,
                        Objects.requireNonNull(abierta.caja().id()),
                        turno.idGuardado(),
                        peticion.cajero(),
                        peticion.contribuyenteId(),
                        reloj.instant(),
                        peticion.formaDePago(),
                        TipoDePago.NORMAL,
                        peticion.campaniaBeneficio(),
                        peticion.fechaDePago(),
                        observacion,
                        lineasDe(abonado));

        Recibo emitido = recibos.emitir(recibo, clave);
        auditar(emitido, observacion);
        return emitido;
    }

    /**
     * Cobra la cuota inicial de un preconvenio y lo formaliza (#35, RF-084).
     *
     * <p><b>En la misma transaccion que el resto</b>, y ahi esta el punto. O el recibo y el
     * acogimiento de la deuda a fase de convenio caen juntos, o no cae ninguno: un recibo de
     * inicial sin acogimiento deja al contribuyente con un papel que dice que su convenio existe y
     * a la municipalidad con la deuda todavia en cobranza ordinaria; un acogimiento sin recibo saca
     * la deuda de la cobranza sin que haya entrado un sol.
     *
     * <p><b>Este recibo no abona en el libro</b>, y no es un olvido. Cuanto de la deuda acogida
     * extingue la cuota inicial es una <b>regla de imputacion</b> —art. 31 del Codigo Tributario—
     * que no esta transcrita ni firmada; lo que la inicial hace es <b>formalizar</b>, y su efecto
     * sobre el libro es el acogimiento entero a fase de convenio. Mientras {@link
     * TipoDePago#CUOTA_CONVENIO} siga rechazado, ninguna cuota se puede cobrar y la cuenta cierra:
     * lo que el quiebre devuelve es lo acogido, sin repartir nada.
     *
     * <p>El desglose del recibo va integro en {@code insoluto}, como el de una tasa: la cuota
     * inicial es un compromiso del convenio, no un tramo de deuda con su reajuste y su interes
     * moratorio. Los que tenia la deuda acogida siguen congelados en {@code convenio_deuda}.
     */
    private Recibo cobrarLaCuotaInicial(Cobranza peticion, Observacion observacion) {
        NumeroDeConvenio convenio =
                NumeroDeConvenio.de(
                        Objects.requireNonNull(
                                peticion.numeroDeConvenio(),
                                "Un cobro de cuota inicial dice de que convenio es"));

        AbrirCaja.Abierta abierta =
                abrirCaja.enLaCaja(
                        peticion.codigoDeCaja(),
                        peticion.cajero(),
                        peticion.fechaDePago(),
                        observacion);

        String clave = peticion.claveDeIdempotencia();
        if (clave != null) {
            Optional<Recibo> yaEmitido = recibos.porClaveDeIdempotencia(clave);
            if (yaEmitido.isPresent()) {
                return yaEmitido.get();
            }
        }

        NumeroDeRecibo numero = recibos.siguienteNumero(abierta.caja());

        // El recibo se emite ANTES de formalizar porque la formalizacion lo nombra:
        // convenio_movimiento_formalizacion_ck exige el recibo_id en la propia base, de
        // modo que no hay forma de dejar un convenio en vigor sin el papel que lo cobro.
        Dinero inicial = cuotaInicialDe(convenio);
        TurnoDeCaja turno = abierta.turno();
        Recibo recibo =
                new Recibo(
                        null,
                        numero,
                        Objects.requireNonNull(abierta.caja().id()),
                        turno.idGuardado(),
                        peticion.cajero(),
                        peticion.contribuyenteId(),
                        reloj.instant(),
                        peticion.formaDePago(),
                        TipoDePago.PRECONVENIO,
                        peticion.campaniaBeneficio(),
                        peticion.fechaDePago(),
                        observacion,
                        List.of(
                                new LineaDeRecibo(
                                        TRIBUTO_CONVENIO,
                                        CONCEPTO_CUOTA_INICIAL,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        convenio.impreso(),
                                        null,
                                        null,
                                        inicial,
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        Dinero.CERO)));

        Recibo emitido = recibos.emitir(recibo, clave);
        formalizar.formalizar(
                convenio,
                Objects.requireNonNull(emitido.id(), "Un recibo emitido trae su identificador"),
                inicial,
                peticion.fechaDePago(),
                observacion);
        auditar(emitido, observacion);
        return emitido;
    }

    /**
     * La cuota inicial que el cronograma congelo.
     *
     * <p>Se lee del convenio y no llega en la peticion: si el importe viajara desde la ventanilla,
     * un cliente podria formalizar un convenio de diez mil soles pagando uno. {@code
     * FormalizarConvenio} lo vuelve a comprobar contra el cronograma, y esa segunda comprobacion no
     * sobra —es la que sigue valiendo si algun dia se llama a formalizar desde otro sitio—.
     */
    private Dinero cuotaInicialDe(NumeroDeConvenio numero) {
        Dinero inicial = formalizar.cuotaInicialDe(numero);
        if (!inicial.esPositivo()) {
            throw new SinCuotaInicialQueCobrar(numero);
        }
        return inicial;
    }

    // ------------------------------------------------------------------

    /** Una linea por obligacion cobrada, con el desglose tal como vino del libro. */
    private static List<LineaDeRecibo> lineasDe(List<AbonoAsentado> abonado) {
        List<LineaDeRecibo> lineas = new ArrayList<>(abonado.size());
        for (AbonoAsentado abono : abonado) {
            SeleccionDeObligacion obligacion = abono.obligacion();
            lineas.add(
                    new LineaDeRecibo(
                            obligacion.tributo(),
                            CONCEPTO_PAGO,
                            obligacion.ejercicio(),
                            null,
                            null,
                            obligacion.predioId(),
                            obligacion.vehiculoId(),
                            null,
                            null,
                            null,
                            abono.insoluto(),
                            abono.reajuste(),
                            abono.interes(),
                            abono.gasto()));
        }
        return lineas;
    }

    private void auditar(Recibo recibo, Observacion porQue) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                recibo.actualizadoA(),
                                "recibo",
                                String.valueOf(recibo.id()),
                                Operacion.ALTA,
                                porQue)
                        .con(null, descripcion(recibo)));
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(Recibo recibo) {
        return "{\"numero\":\""
                + recibo.numero().impreso()
                + "\",\"tipoDePago\":\""
                + recibo.tipoDePago()
                + "\",\"formaDePago\":\""
                + recibo.formaDePago()
                + "\",\"lineas\":"
                + recibo.lineas().size()
                + ",\"total\":"
                + recibo.total().valor().toPlainString()
                + ",\"actualizadoA\":\""
                + recibo.actualizadoA()
                + "\"}";
    }

    /**
     * Lo que el cajero marco.
     *
     * <p>Un tipo y no once argumentos: la firma de {@link #cobrar} es la frontera donde mas facil
     * es intercambiar dos parametros del mismo tipo sin que el compilador diga nada.
     *
     * @param codigoDeCaja la ventanilla
     * @param cajero quien cobra
     * @param contribuyenteId a quien se le cobra; lo resolvio el borde HTTP
     * @param obligaciones las marcadas en la grilla
     * @param formaDePago con que se paga
     * @param tipoDePago que clase de cobranza es; #33 solo escribe {@link TipoDePago#NORMAL}
     * @param campaniaBeneficio la campana declarada, si la hubo; <b>solo constancia</b> (D-02b)
     * @param fechaDePago la fecha a la que se relee la deuda; entra como argumento (regla 6)
     * @param claveDeIdempotencia la cabecera {@code idempotency-key}, si vino
     * @param numeroDeConvenio el convenio cuya cuota inicial se cobra; <b>solo</b> con {@link
     *     TipoDePago#PRECONVENIO}, y obligatorio en ese caso
     */
    public record Cobranza(
            String codigoDeCaja,
            String cajero,
            long contribuyenteId,
            List<SeleccionDeObligacion> obligaciones,
            FormaDePago formaDePago,
            TipoDePago tipoDePago,
            @Nullable String campaniaBeneficio,
            LocalDate fechaDePago,
            @Nullable String claveDeIdempotencia,
            @Nullable String numeroDeConvenio) {

        public Cobranza {
            Objects.requireNonNull(codigoDeCaja, "La cobranza es de una caja");
            Objects.requireNonNull(cajero, "La cobranza la hace un cajero con nombre");
            Objects.requireNonNull(obligaciones, "La lista es vacia, no nula");
            Objects.requireNonNull(formaDePago, "Hay que decir con que se paga");
            Objects.requireNonNull(tipoDePago, "Hay que decir que clase de cobranza es");
            Objects.requireNonNull(fechaDePago, "La fecha de pago entra como argumento (regla 6)");
            obligaciones = List.copyOf(obligaciones);
            // El cobro de una cuota inicial no marca deudas: las marco el preconvenio, y
            // estan congeladas en convenio_deuda con su fase de origen. Admitir las dos
            // cosas a la vez dejaria dos definiciones de que se esta cobrando.
            if (tipoDePago == TipoDePago.PRECONVENIO) {
                Objects.requireNonNull(
                        numeroDeConvenio,
                        "Un cobro de cuota inicial dice de que convenio es (#35, RF-084)");
                if (!obligaciones.isEmpty()) {
                    throw new IllegalArgumentException(
                            "La cuota inicial no marca deudas: la deuda que se acoge la fijo el"
                                    + " preconvenio, y volver a marcarla aqui dejaria dos"
                                    + " definiciones de que se esta cobrando");
                }
            } else {
                if (numeroDeConvenio != null) {
                    throw new IllegalArgumentException(
                            "Solo el cobro de una cuota inicial nombra un convenio: " + tipoDePago);
                }
                if (obligaciones.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Hay que marcar al menos una deuda: un recibo sin lineas no documenta"
                                    + " nada");
                }
            }
            // La grilla marca con casillas, asi que una obligacion repetida solo puede venir
            // de un cliente mal escrito -o de uno que lo intenta-. Cobrarla dos veces en el
            // mismo recibo seria cobrarla de mas, y ademas la segunda linea saldria en cero:
            // el libro ya no tendria deuda que devolver. Se rechaza aqui, en la frontera,
            // ademas de en el puerto que asienta.
            if (new java.util.LinkedHashSet<>(obligaciones).size() != obligaciones.size()) {
                throw new IllegalArgumentException(
                        "La misma obligacion viene marcada dos veces en el mismo recibo");
            }
            if (contribuyenteId <= 0) {
                throw new IllegalArgumentException("La cobranza es de un contribuyente concreto");
            }
        }
    }

    /**
     * Ninguna de las obligaciones marcadas tenia deuda a esa fecha.
     *
     * <p>Es lo que ve el cajero cuando alguien intenta cobrar dos veces: la primera cobranza dejo
     * el saldo en cero y la segunda no encuentra nada. Envuelve a {@link
     * RegistroDeAbonos.SinDeudaQueAbonar} para que el borde HTTP no tenga que conocer las
     * excepciones de otro contexto.
     */
    public static final class NadaQueCobrar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        NadaQueCobrar(RegistroDeAbonos.SinDeudaQueAbonar causa) {
            super(causa.getMessage(), causa);
        }
    }

    /**
     * Una modalidad de cobro que todavia no se escribe. Ver {@link TipoDePago}.
     *
     * <p>Quedan {@code A_CUENTA} y {@code CUOTA_CONVENIO}, y las dos por el mismo motivo: son pagos
     * <b>parciales</b>, y decidir que parte de la deuda extingue un pago parcial es una regla de
     * imputacion normativa (art. 31 del Codigo Tributario) que no esta transcrita ni firmada. #35
     * lo deja anotado en {@link TipoDePago}, con la consecuencia que hace la decision segura:
     * mientras esto siga rechazando {@code CUOTA_CONVENIO}, ninguna cuota se cobra y el quiebre
     * nunca tiene que repartir un pago parcial.
     */
    public static final class TipoDePagoNoImplementado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        TipoDePagoNoImplementado(TipoDePago tipo) {
            super(
                    "Caja tributaria cobra hoy NORMAL y PRECONVENIO: "
                            + tipo
                            + " es un pago parcial y exige una regla de imputacion —que parte de"
                            + " la deuda extingue— que no esta firmada. Aceptarla como si fuera"
                            + " normal dejaria el recibo diciendo una cosa y el libro otra");
        }
    }

    /** El convenio no tiene cuota inicial que cobrar: se pacto al 0 %. */
    public static final class SinCuotaInicialQueCobrar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinCuotaInicialQueCobrar(NumeroDeConvenio numero) {
            super(
                    "El convenio "
                            + numero.impreso()
                            + " se pacto sin cuota inicial: no hay nada que cobrar en caja, y un"
                            + " recibo por cero no documenta nada. Un convenio al 0 % de inicial"
                            + " necesita otro acto que lo formalice, y ese acto no existe todavia");
        }
    }
}
