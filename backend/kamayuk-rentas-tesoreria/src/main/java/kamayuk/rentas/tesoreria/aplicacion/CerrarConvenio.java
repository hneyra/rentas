package kamayuk.rentas.tesoreria.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.cuentacorriente.AcogimientoAConvenio;
import kamayuk.rentas.cuentacorriente.MovimientoAsentado;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.AnulacionesDeRecibo;
import kamayuk.rentas.tesoreria.aplicacion.RegistrarPreconvenio.Peticion;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.ConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.EstadoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.NumeroDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeConvenio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cierra un convenio devolviendo su deuda a la fase de la que salio: anulacion, quiebre o
 * reformulacion (#35, RF-085, RF-086).
 *
 * <h2>La deuda vuelve con asientos, nunca con un {@code UPDATE} de fase</h2>
 *
 * <p>{@link AcogimientoAConvenio#devolver} escribe el par contrario al del acogimiento —abono en
 * fase de convenio, cargo en la fase de origen— con la fase que {@code convenio_deuda.fase_origen}
 * guardo cuota por cuota. Una deuda que venia de coactiva vuelve a coactiva, no a ordinaria: el
 * expediente sigue vivo, y devolverla a ordinaria lo dejaria sin sustento.
 *
 * <p>Nunca un {@code UPDATE saldo_proyectado SET fase = ...}: la fase de una obligacion es la de su
 * ultimo asiento, y escribirla a mano dejaria la proyeccion diciendo una cosa y el libro otra.
 *
 * <h2>Vuelve lo pendiente, no lo acogido</h2>
 *
 * <p>Se devuelve lo que el libro dice <b>hoy</b>. Reversar los asientos del acogimiento seria mas
 * corto y estaria mal: devolveria a la fase de origen tambien lo que entretanto se hubiera cobrado,
 * y el contribuyente acabaria debiendo otra vez lo que ya pago.
 *
 * <h2>Anular no es quebrar</h2>
 *
 * <ul>
 *   <li><b>Anulacion</b>: el convenio no debio existir. Como su cuota inicial se cobro con un
 *       recibo, anularlo exige que ese recibo este anulado —y un recibo solo se anula el mismo dia
 *       del pago (#34, RF-083)—. Sin esa comprobacion, anular un convenio de hace tres meses
 *       dejaria un recibo vivo cobrando la inicial de un convenio que ya no existe.
 *   <li><b>Quiebre</b>: el convenio existio y se incumplio. El recibo de la inicial sigue siendo
 *       valido —ese dinero entro— y no se toca.
 *   <li><b>Reformulacion</b>: quiebre mas un preconvenio nuevo sobre el saldo pendiente. Se
 *       resuelve en la misma transaccion: si el preconvenio nuevo fallara, el quiebre se revierte
 *       con el y la deuda no se queda a medio camino.
 * </ul>
 *
 * <h2>Cerrar dos veces es imposible</h2>
 *
 * <p>{@code convenio_movimiento_cierre_uq}, un indice unico parcial sobre los <b>tres</b> tipos de
 * cierre a la vez. La lectura previa del estado esta para dar un mensaje util; la garantia es el
 * indice, porque dos peticiones simultaneas pasan las dos por cualquier {@code if} de Java —y dos
 * devoluciones dejarian al contribuyente debiendo el doble—.
 *
 * <h2>Y reenviar el mismo intento devuelve el acta de la primera vez (#606)</h2>
 *
 * <p>Que el doble cierre fuera imposible no bastaba: sin leer la cabecera {@code Idempotency-Key},
 * el reenvio contestaba <b>409 CONFLICTO</b>, que quien atiende lee como un fallo nuevo y no como
 * «ya estaba hecho» —y una interfaz no puede ofrecer «Reintentar» sobre una escritura cuyo
 * reintento contesta un error—. Con la clave, el reenvio devuelve el acta que se registro y el
 * convenio ya cerrado.
 *
 * <p><b>La clave la reclama el cierre, no el preconvenio que la reformulacion abre.</b> Los dos
 * actos van en la misma transaccion y en dos tablas distintas; guardar la clave en las dos haria
 * que identificara dos filas, y un reenvio tendria que elegir con cual contestar. Con la clave en
 * {@code convenio_movimiento}, el reenvio se para <b>antes</b> de registrar nada, asi que no puede
 * abrir un preconvenio de mas; y si se colara, {@code convenio_movimiento_cierre_uq} aborta la
 * transaccion entera y el preconvenio se va con ella.
 */
@Service
public class CerrarConvenio {

    private final ConvenioRepository convenios;
    private final MovimientoDeConvenioRepository movimientos;
    private final AnulacionesDeRecibo anulacionesDeRecibo;
    private final AcogimientoAConvenio acogimiento;
    private final RegistrarPreconvenio preconvenios;
    private final Auditoria auditoria;
    private final Clock reloj;

    public CerrarConvenio(
            ConvenioRepository convenios,
            MovimientoDeConvenioRepository movimientos,
            AnulacionesDeRecibo anulacionesDeRecibo,
            AcogimientoAConvenio acogimiento,
            RegistrarPreconvenio preconvenios,
            Auditoria auditoria,
            Clock reloj) {
        this.convenios = convenios;
        this.movimientos = movimientos;
        this.anulacionesDeRecibo = anulacionesDeRecibo;
        this.acogimiento = acogimiento;
        this.preconvenios = preconvenios;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Cierra el convenio y devuelve su acta.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de {@link Cierre}: la regla 10 exige que
     * se vea en el punto donde se escribe, y ArchUnit la comprueba mirando los parametros del
     * metodo transaccional. El {@code motivo} es <b>otra cosa</b> y va aparte: la observacion
     * explica la operacion a quien lea la bitacora, y el motivo es el sustento del acto
     * administrativo, que queda en el propio convenio y se imprime en su resolucion.
     *
     * @param claveDeIdempotencia la cabecera {@code Idempotency-Key}; opcional
     * @throws FormalizarConvenio.ConvenioInexistente si no hay convenio con ese numero
     * @throws ConvenioSinFormalizar si todavia es un preconvenio: no acogio nada que devolver
     * @throws MovimientoDeConvenioRepository.ConvenioYaCerrado si ya estaba cerrado
     * @throws ReciboDeLaInicialVigente si se anula y el recibo de la inicial no esta anulado
     * @throws ClaveDeOtroActo si esa clave cerro otro convenio
     */
    @Transactional
    public Cerrado cerrar(
            Cierre peticion, @Nullable String claveDeIdempotencia, Observacion observacion) {
        Objects.requireNonNull(peticion, "No se cierra sin peticion");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        Convenio convenio =
                convenios
                        .porNumero(peticion.numero())
                        .orElseThrow(
                                () ->
                                        new FormalizarConvenio.ConvenioInexistente(
                                                peticion.numero()));
        long convenioId = convenio.idGuardado();

        // El reenvio, ANTES de la comprobacion de estado: sin esto un segundo envio del mismo
        // intento se estrella contra `ConvenioYaCerrado` y contesta 409, que es exactamente lo
        // que #606 arregla. La garantia sigue siendo el indice, no esta lectura.
        String clave = limpiar(claveDeIdempotencia);
        if (clave != null) {
            Optional<MovimientoDeConvenio> yaHecho = movimientos.porClaveDeIdempotencia(clave);
            if (yaHecho.isPresent()) {
                MovimientoDeConvenio anterior = yaHecho.get();
                if (anterior.convenioId() != convenioId) {
                    throw new ClaveDeOtroActo(peticion.numero());
                }
                return reenvioDe(convenio, anterior);
            }
        }

        EstadoDeConvenio estado =
                EstadoDeConvenio.deLosMovimientos(movimientos.deConvenio(convenioId));
        if (estado.esPreconvenio()) {
            throw new ConvenioSinFormalizar(peticion.numero());
        }
        if (estado.estaCerrado()) {
            throw new MovimientoDeConvenioRepository.ConvenioYaCerrado(
                    "El convenio "
                            + peticion.numero().impreso()
                            + " ya esta "
                            + estado
                            + ": su deuda ya volvio a su fase de origen, y devolverla otra vez la"
                            + " duplicaria",
                    new IllegalStateException("estado " + estado));
        }
        if (peticion.tipo() == TipoDeMovimientoDeConvenio.ANULACION) {
            exigirQueElReciboDeLaInicialEsteAnulado(convenio, convenioId);
        }

        MovimientoAsentado devuelto =
                acogimiento.devolver(
                        convenio.contribuyenteId(),
                        convenio.acogida(),
                        peticion.fecha(),
                        documentoDelCierre(peticion.tipo(), peticion.numero()),
                        observacion);

        // La reformulacion registra ANTES su convenio nuevo, para poder nombrarlo en el
        // movimiento: convenio_movimiento_reformulacion_ck exige que lo nombre, y en la
        // misma transaccion, de modo que si el preconvenio nuevo fallara el quiebre se
        // revertiria con el.
        @Nullable Convenio reformulado = null;
        if (peticion.tipo() == TipoDeMovimientoDeConvenio.REFORMULACION) {
            reformulado =
                    preconvenios.registrar(
                            Objects.requireNonNull(
                                            peticion.reformulacion(),
                                            "Una reformulacion trae el preconvenio que la sustituye")
                                    .conOrigen(convenioId),
                            // Sin clave: la del intento la reclama el movimiento de cierre. Ver el
                            // javadoc de la clase.
                            null,
                            observacion);
        }

        MovimientoDeConvenio cierre =
                movimientos.registrar(
                        MovimientoDeConvenio.cierre(
                                convenioId,
                                peticion.tipo(),
                                peticion.fecha(),
                                peticion.motivo(),
                                peticion.autorizadoPor(),
                                peticion.documentoAutorizacion(),
                                devuelto.importe(),
                                devuelto.asientos(),
                                reformulado == null ? null : reformulado.id(),
                                reloj.instant(),
                                observacion),
                        clave);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                peticion.fecha(),
                                "convenio_movimiento",
                                String.valueOf(cierre.id()),
                                Operacion.ANULACION,
                                observacion)
                        .con(null, descripcion(peticion.numero(), cierre)));

        return new Cerrado(convenio, cierre, devuelto, reformulado);
    }

    // ------------------------------------------------------------------

    /**
     * La respuesta del reenvio: el convenio y el acta que ya se registraron.
     *
     * <p>{@code devuelto} viene <b>nulo</b>, y es lo unico honesto que se puede poner: {@link
     * MovimientoAsentado} deriva su importe de la lista de cuotas que movio, y recomponerla
     * exigiria releer el libro a la fecha de entonces, que ya no dice lo mismo (regla 9). Lo que se
     * devolvio esta congelado en el acta —{@code importe} y {@code asientos}—, que es de donde hay
     * que leerlo.
     */
    private Cerrado reenvioDe(Convenio convenio, MovimientoDeConvenio cierre) {
        Long nuevoId = cierre.convenioNuevoId();
        return new Cerrado(
                convenio,
                cierre,
                null,
                nuevoId == null ? null : convenios.porId(nuevoId).orElse(null));
    }

    /** Una cabecera vacia o en blanco es no traer clave, no traer la cadena vacia. */
    private static @Nullable String limpiar(@Nullable String clave) {
        if (clave == null) {
            return null;
        }
        String limpia = clave.strip();
        return limpia.isEmpty() ? null : limpia;
    }

    /**
     * Anular exige que el recibo de la inicial ya este anulado.
     *
     * <p>Los dos actos van juntos porque el convenio y su recibo son las dos mitades del mismo
     * hecho: la inicial se cobro <b>porque</b> el convenio existia. Dejar el recibo vivo tras
     * anular el convenio deja dinero cobrado por un acto que ya no existe, y ningun arqueo lo
     * detecta.
     *
     * <p>Se comprueba aqui y no se anula desde aqui a proposito: un recibo solo se anula el mismo
     * dia del pago (#34), lo autoriza quien responde por la caja, y encadenarlo desde este caso de
     * uso saltaria esa autorizacion. Desde P5D el argumento es todavia mas fuerte: anular un recibo
     * es un acto de OTRO SISTEMA.
     *
     * <p><b>Y desde P5D esto se pregunta, no se lee.</b> Hasta `V7` la comprobacion era un {@code
     * SELECT} sobre {@code recibo_movimiento}, que estaba en esta base; ahora es {@link
     * AnulacionesDeRecibo}, un puerto a {@code caja}. Lo que NO cambia es que la respuesta decide:
     * el puerto no devuelve {@code false} cuando no se puede preguntar —eso significaria «el recibo
     * sigue vigente» sin haberlo mirado— sino que lanza, y hoy lanza siempre porque {@code caja}
     * publica sus recibos por el numero impreso y aqui lo que hay es el identificador interno. Ver
     * {@code AnulacionesDeReciboSinRuta}, que dice exactamente que se rompe mientras eso dure y que
     * no se rompe.
     */
    private void exigirQueElReciboDeLaInicialEsteAnulado(Convenio convenio, long convenioId) {
        Optional<MovimientoDeConvenio> formalizacion = movimientos.formalizacionDe(convenioId);
        if (formalizacion.isEmpty()) {
            return;
        }
        Long reciboId = formalizacion.get().reciboId();
        if (reciboId == null) {
            return;
        }
        if (!anulacionesDeRecibo.estaAnulado(reciboId)) {
            throw new ReciboDeLaInicialVigente(convenio.numero());
        }
    }

    /**
     * Como se marcan en el libro los asientos de la devolucion.
     *
     * <p><b>Distinto del que uso el acogimiento</b>, y no es una formalidad: con el mismo texto,
     * cualquier operacion que busque «los asientos del convenio» encontraria mezclados los de ida y
     * los de vuelta, y una reversion podria encontrarse a si misma.
     */
    public static String documentoDelCierre(
            TipoDeMovimientoDeConvenio tipo, NumeroDeConvenio numero) {
        return tipo.name() + " " + numero.impreso();
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(NumeroDeConvenio numero, MovimientoDeConvenio cierre) {
        return "{\"numero\":\""
                + numero.impreso()
                + "\",\"tipo\":\""
                + cierre.tipo()
                + "\",\"motivo\":\""
                + cierre.motivoDelCierre()
                + "\",\"devuelto\":"
                + cierre.importe().valor().toPlainString()
                + ",\"asientos\":"
                + cierre.asientos()
                + ",\"fecha\":\""
                + cierre.fecha()
                + "\"}";
    }

    /**
     * Lo que se pide cerrar.
     *
     * @param numero el convenio, por su numero impreso
     * @param tipo anulacion, quiebre o reformulacion
     * @param fecha la fecha valor de los asientos de la devolucion (regla 6)
     * @param motivo el sustento del acto; obligatorio (RNF-052)
     * @param autorizadoPor quien lo autorizo, si consta
     * @param documentoAutorizacion la resolucion o el memorando, si consta
     * @param reformulacion el preconvenio que sustituye a este; solo en la reformulacion
     */
    public record Cierre(
            NumeroDeConvenio numero,
            TipoDeMovimientoDeConvenio tipo,
            LocalDate fecha,
            String motivo,
            @Nullable String autorizadoPor,
            @Nullable String documentoAutorizacion,
            @Nullable Peticion reformulacion) {

        public Cierre {
            Objects.requireNonNull(numero, "Se cierra un convenio concreto, por su numero");
            Objects.requireNonNull(tipo, "Hay que decir como se cierra");
            Objects.requireNonNull(fecha, "El cierre es de un dia concreto (regla 6)");
            Objects.requireNonNull(motivo, "Cerrar un convenio exige su motivo (RNF-052)");
            motivo = motivo.strip();
            if (motivo.isEmpty()) {
                throw new IllegalArgumentException(
                        "El motivo del cierre no puede estar vacio: es el sustento de dejar sin"
                                + " efecto un compromiso que el contribuyente firmo");
            }
            if (!tipo.cierra()) {
                throw new IllegalArgumentException(
                        "La formalizacion no cierra un convenio: la cobra la caja (#33)");
            }
            if ((tipo == TipoDeMovimientoDeConvenio.REFORMULACION) != (reformulacion != null)) {
                throw new IllegalArgumentException(
                        "Una reformulacion trae el preconvenio que la sustituye, y solo ella lo"
                                + " trae: sin el, el saldo pendiente se quedaria sin convenio");
            }
        }
    }

    /**
     * El convenio cerrado y su acta.
     *
     * @param convenio el convenio, intacto: su cronograma sigue donde estaba
     * @param cierre la fila que se agrego —o la que ya estaba, si esto fue un reenvio—
     * @param devuelto lo que de verdad volvio a su fase de origen, con su fecha. <b>Nulo en el
     *     reenvio</b>: lo devuelto esta congelado en el acta, y recomponer la lista de cuotas
     *     exigiria releer el libro a otra fecha (regla 9)
     * @param reformulado el preconvenio nuevo, si el cierre fue una reformulacion
     */
    public record Cerrado(
            Convenio convenio,
            MovimientoDeConvenio cierre,
            @Nullable MovimientoAsentado devuelto,
            @Nullable Convenio reformulado) {}

    /**
     * Esa clave de idempotencia cerro otro convenio.
     *
     * <p>Un reenvio es el <b>mismo</b> intento repetido. Con la clave apuntando a otro convenio,
     * devolver el acta de la primera vez diria que se cerro un convenio que sigue vivo. Quien llama
     * responde 409.
     */
    public static final class ClaveDeOtroActo extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ClaveDeOtroActo(NumeroDeConvenio numero) {
            super(
                    "Esa clave de idempotencia ya cerro otro convenio, no el "
                            + numero.impreso()
                            + ": reenviar un intento devuelve lo de la primera vez, no lo de otra"
                            + " peticion. Use una clave nueva");
        }
    }

    /** El convenio todavia es un preconvenio: no acogio ninguna deuda que devolver. */
    public static final class ConvenioSinFormalizar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ConvenioSinFormalizar(NumeroDeConvenio numero) {
            super(
                    "El convenio "
                            + numero.impreso()
                            + " todavia es un preconvenio: no acogio ninguna deuda, asi que no hay"
                            + " nada que devolver. Un preconvenio que no se formaliza simplemente"
                            + " no llega a existir");
        }
    }

    /** Se pide anular un convenio cuyo recibo de cuota inicial sigue vigente. */
    public static final class ReciboDeLaInicialVigente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ReciboDeLaInicialVigente(NumeroDeConvenio numero) {
            super(
                    "El recibo que cobro la cuota inicial del convenio "
                            + numero.impreso()
                            + " sigue vigente: anular el convenio dejaria dinero cobrado por un"
                            + " acto que ya no existe. Anulese primero el recibo (RF-083), o"
                            + " quiebrese el convenio, que es lo que corresponde cuando la inicial"
                            + " si se cobro");
        }
    }
}
