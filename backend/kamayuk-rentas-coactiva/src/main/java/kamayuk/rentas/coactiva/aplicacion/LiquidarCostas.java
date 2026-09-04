package kamayuk.rentas.coactiva.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.coactiva.dominio.ActoCoactivo;
import kamayuk.rentas.coactiva.dominio.ActoCoactivoRepository;
import kamayuk.rentas.coactiva.dominio.CostaLiquidada;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostas;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostasRepository;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Liquida las costas y gastos del procedimiento coactivo y las asienta en el libro (#42, RF-104).
 *
 * <h2>La costa es un CARGO, no un campo del expediente</h2>
 *
 * <p>Es la decision de diseño de #42 y de ella se sigue todo lo demas. Una costa se cobra: forma
 * parte de lo que el obligado tiene que pagar para que el procedimiento concluya. Si viviera en una
 * columna del expediente habria dos verdades sobre cuanto se debe —el libro y esa columna—, la caja
 * cobraria la del libro y la pantalla mostraria la otra, y nada avisaria.
 *
 * <p>Lo que este caso de uso hace es asentar un <b>cargo de concepto {@code GASTO} en fase {@code
 * COACTIVA}</b> por el puerto publico de {@code cuentacorriente} ({@link
 * GeneradorDeCargos#generarGastoDelProcedimiento}). A partir de ahi la costa se consulta, se
 * fracciona y se cobra por los mismos caminos que cualquier otra deuda, y {@code
 * DeudaDelExpediente.costas} la <b>relee</b> a la fecha que se pida (regla 9). Este modulo no
 * guarda ningun saldo.
 *
 * <h2>Ninguna cifra la pone quien liquida</h2>
 *
 * <p>La peticion dice <b>que actos</b> se liquidan; <b>cuanto</b> vale cada uno lo dice el arancel
 * sellado ({@link ArancelDeCostasParametrizado}). No hay ningun importe en la firma, por el mismo
 * motivo por el que {@code SeleccionDeObligacion} no lo lleva: si viajara, quien liquida podria
 * mandar el que le diera la gana y el libro lo asentaria sin discutir.
 *
 * <p>Y por eso hoy, con D-02c abierta (#193), <b>liquidar falla</b> nombrando la llave que falta.
 * Es el resultado correcto: mejor que no se pueda liquidar a que se liquide con una cifra que
 * ninguna ordenanza respalda.
 *
 * <h2>Un acto se liquida una sola vez, y lo decide la base</h2>
 *
 * <p>{@code costa_acto_uq} (V35). La lectura previa de {@link
 * LiquidacionDeCostasRepository#actosYaLiquidados} esta para <b>explicar</b> que actos quedan; la
 * garantia es el indice, porque dos peticiones simultaneas pasan las dos por cualquier {@code if} y
 * el obligado acabaria pagando dos veces la costa de la misma resolucion.
 *
 * <h2>Lo que este caso de uso NO hace</h2>
 *
 * <p><b>No mueve el estado del expediente.</b> Liquidar costas no es un acto del procedimiento en
 * el sentido de {@link TipoDeActoCoactivo}: no inicia nada, no ordena nada y no lo concluye. El
 * historial del expediente no gana una fila.
 *
 * <p><b>No emite documento.</b> La pantalla tiene un boton «Imprimir», y el papel de la liquidacion
 * sale del mismo {@code EmitirDocumento} que dibuja las REC; conectarlo es trabajo de la capa de
 * documentos y no cambia nada de lo que hay aqui. Lo que #42 no puede hacer es imprimir un papel
 * cuyas cifras no existen.
 */
@Service
public class LiquidarCostas {

    private final ExpedienteRepository expedientes;
    private final MovimientoDelExpedienteRepository movimientos;
    private final ActoCoactivoRepository actos;
    private final LiquidacionDeCostasRepository liquidaciones;
    private final ArancelDeCostasParametrizado aranceles;
    private final GeneradorDeCargos cargos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public LiquidarCostas(
            ExpedienteRepository expedientes,
            MovimientoDelExpedienteRepository movimientos,
            ActoCoactivoRepository actos,
            LiquidacionDeCostasRepository liquidaciones,
            ArancelDeCostasParametrizado aranceles,
            GeneradorDeCargos cargos,
            Auditoria auditoria,
            Clock reloj) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.actos = actos;
        this.liquidaciones = liquidaciones;
        this.aranceles = aranceles;
        this.cargos = cargos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Liquida los actos pedidos —o todos los que queden por liquidar— y asienta su cargo.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de {@link Peticion}: la regla 10 exige
     * que se vea en el punto donde se escribe, y ArchUnit la comprueba mirando los parametros del
     * metodo transaccional.
     *
     * @throws CambiarEstadoDelExpediente.ExpedienteInexistente si no hay expediente con ese numero
     * @throws CambiarEstadoDelExpediente.ExpedienteConcluido si el procedimiento ya termino
     * @throws SinActosQueLiquidar si no queda ningun acto pendiente, o si la ordenanza —que si esta
     *     publicada— no tarifa los que quedan
     * @throws ActoAjeno si alguno de los actos pedidos no es de ese expediente
     * @throws ArancelDeCostasParametrizado.ArancelSinParametrizar si el arancel no tarifa un acto
     *     pedido expresamente, o si no hay <b>ningun</b> arancel publicado y quedan actos
     *     pendientes (#634)
     * @throws LiquidacionDeCostasRepository.ActoYaLiquidado si otro liquido el mismo acto a la vez
     * @throws LiquidacionDeCostasRepository.ObligacionDeOtroExpediente si otro expediente del mismo
     *     obligado ya tiene las costas de ese tributo y ejercicio
     */
    @Transactional
    public LiquidacionDeCostas liquidar(Peticion peticion, Observacion observacion) {
        Objects.requireNonNull(peticion, "No se liquida sin peticion");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        ExpedienteCoactivo expediente =
                expedientes
                        .porNumero(peticion.numeroDeExpediente())
                        .orElseThrow(
                                () ->
                                        new CambiarEstadoDelExpediente.ExpedienteInexistente(
                                                peticion.numeroDeExpediente()));

        EstadoDelExpediente estado =
                EstadoDelExpediente.delHistorial(
                        movimientos.deExpediente(expediente.identificador()));
        if (estado.estaConcluido()) {
            // Concluido el procedimiento no hay costas nuevas que devengar: liquidar sobre un
            // expediente cerrado seria cobrar por actuaciones que ya no se van a practicar.
            throw new CambiarEstadoDelExpediente.ExpedienteConcluido(expediente.numero());
        }

        ArancelDeCostasParametrizado.Vigente arancel = aranceles.aLaFechaDe(peticion.fecha());
        List<ActoCoactivo> candidatos = candidatosDe(expediente, peticion, arancel);

        long correlativo = liquidaciones.siguienteCorrelativo(Ejercicio.de(peticion.fecha()));
        List<CostaLiquidada> lineas = new ArrayList<>(candidatos.size());
        for (ActoCoactivo acto : candidatos) {
            lineas.add(
                    CostaLiquidada.nueva(
                            expediente.identificador(),
                            acto.identificador(),
                            acto.tipo(),
                            glosaDe(acto),
                            LiquidacionDeCostas.TRIBUTO,
                            arancel.paraElActo(acto.tipo()),
                            peticion.fecha(),
                            arancel.llaveDe(acto.tipo()),
                            arancel.conjuntoId()));
        }

        LiquidacionDeCostas guardada =
                liquidaciones.registrar(
                        LiquidacionDeCostas.nueva(
                                Ejercicio.de(peticion.fecha()),
                                correlativo,
                                expediente.identificador(),
                                expediente.contribuyenteId(),
                                peticion.fecha(),
                                arancel.conjuntoId(),
                                lineas,
                                reloj.instant(),
                                observacion));

        // El cargo, en la MISMA transaccion: o entran la liquidacion y su asiento, o no entra
        // ninguno de los dos. Una liquidacion sin cargo seria un papel que no cobra nada; un
        // cargo sin liquidacion, una deuda que nadie puede explicar.
        cargos.generarGastoDelProcedimiento(
                guardada.ejercicio(),
                guardada.contribuyenteId(),
                guardada.tributo(),
                expediente.numero(),
                guardada.total(),
                guardada.fecha(),
                guardada.numero(),
                observacion);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                guardada.fecha(),
                                "liquidacion_costas",
                                String.valueOf(guardada.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(expediente, guardada)));

        return guardada;
    }

    // ------------------------------------------------------------------

    /**
     * Los actos que esta liquidacion va a tarifar.
     *
     * <p>Con lista de actos, exactamente esos: si alguno no es del expediente se rechaza —liquidar
     * en el expediente A la costa de un acto de B mezclaria dos procedimientos— y si alguno no
     * tiene arancel, {@link ArancelDeCostasParametrizado.Vigente#paraElActo} falla nombrando la
     * llave. Sin lista, <b>todos los que quedan por liquidar y el arancel tarifa</b>.
     *
     * <h2>Que la ordenanza no tarife un acto y que no haya ordenanza NO son lo mismo (#634)</h2>
     *
     * <p>Hasta #634 los dos casos se contestaban igual —{@link SinActosQueLiquidar}— y por eso el
     * {@code catch} de {@code ArancelSinParametrizar} de {@code CostasController} era inalcanzable
     * por esta rama: el descarte por {@code arancel.tarifa(tipo)} se comia los actos antes de que
     * nadie preguntara su importe. Con D-02c abierta (#193) y <b>ninguna</b> llave {@code
     * ARANCEL_COSTA} publicada en ninguna municipalidad, liquidar un expediente con su REC-1
     * dictada contestaba «este expediente no tiene ningun acto pendiente de liquidar», que se lee
     * como «no hay nada que cobrar» y no como «falta publicar una cifra». Un resultado plausible y
     * equivocado, que es lo que este repositorio decidio no producir en #51 ({@code TASA_ANUNCIO}),
     * en #54 ({@code VIGENCIA_DEL_CERTIFICADO}) y en #72 ({@code BENEFICIO}).
     *
     * <p>El discriminador no hay que inventarlo, lo publica el propio conjunto sellado: {@link
     * ArancelDeCostasParametrizado.Vigente#tarifaAlgunActo()} pregunta si hay <b>alguna</b> clave
     * de esa familia. Con alguna, que este acto no este es la decision de la ordenanza y se respeta
     * —se omite en silencio, y si no queda ninguno sale {@code SinActosQueLiquidar}—. Sin ninguna,
     * no hay decision que respetar: falta la ordenanza, y se falla nombrando la llave del primer
     * acto pendiente para que quien lea el 422 sepa que publicar.
     *
     * <p>El {@code catch} de {@code CostasController} <b>no sobra</b> —la salida (1) del issue—: ya
     * era alcanzable pidiendo actos por su identificador, y retirarlo convertiria ese 422 en un 500
     * con incidencia. Lo que se corrige es el silencio de la otra rama.
     *
     * <p>Y {@link SinActosQueLiquidar} sigue existiendo para lo que de verdad es: que no quede
     * ningun acto pendiente —ya se liquidaron todos— o que la ordenanza, existiendo, no tarife los
     * que quedan. Por eso la comprobacion mira los actos <b>pendientes</b> y no el arancel a secas:
     * un expediente ya liquidado del todo no gana un 422 nuevo por que la ordenanza haya dejado de
     * estar cargada.
     */
    private List<ActoCoactivo> candidatosDe(
            ExpedienteCoactivo expediente,
            Peticion peticion,
            ArancelDeCostasParametrizado.Vigente arancel) {

        List<ActoCoactivo> delExpediente = actos.deExpediente(expediente.identificador());
        Set<Long> pedidos = peticion.actos();

        List<Long> ids = new ArrayList<>();
        for (ActoCoactivo acto : delExpediente) {
            ids.add(acto.identificador());
        }
        Set<Long> yaLiquidados = liquidaciones.actosYaLiquidados(ids);

        if (!pedidos.isEmpty()) {
            Set<Long> suyos = new LinkedHashSet<>(ids);
            for (Long pedido : pedidos) {
                if (!suyos.contains(pedido)) {
                    throw new ActoAjeno(expediente.numero(), pedido);
                }
            }
        }

        List<ActoCoactivo> pendientes = new ArrayList<>();
        for (ActoCoactivo acto : delExpediente) {
            long id = acto.identificador();
            if (yaLiquidados.contains(id)) {
                continue;
            }
            if (pedidos.isEmpty() || pedidos.contains(id)) {
                pendientes.add(acto);
            }
        }

        if (pendientes.isEmpty()) {
            // Ni uno pendiente: ya se liquidaron todos. Aqui no falta ninguna cifra.
            throw new SinActosQueLiquidar(expediente.numero(), arancel.ejercicio());
        }

        if (!pedidos.isEmpty()) {
            // Pedidos por su identificador: se tarifan todos, y el que no tenga arancel lo dice
            // `paraElActo` nombrando su llave. Es la rama que ya llegaba a la excepcion.
            return pendientes;
        }

        List<ActoCoactivo> candidatos = new ArrayList<>();
        for (ActoCoactivo acto : pendientes) {
            if (arancel.tarifa(acto.tipo())) {
                candidatos.add(acto);
            }
        }
        if (!candidatos.isEmpty()) {
            return candidatos;
        }
        if (!arancel.tarifaAlgunActo()) {
            // Nadie publico el arancel (D-02c, #193): no es que la ordenanza no tarife estos
            // actos, es que no hay ordenanza. Se dice QUE llave hay que publicar.
            throw new ArancelDeCostasParametrizado.ArancelSinParametrizar(
                    arancel.ejercicio(), pendientes.get(0).tipo());
        }
        throw new SinActosQueLiquidar(expediente.numero(), arancel.ejercicio());
    }

    /** La glosa que sale impresa: que acto se tarifa y con que numero. */
    private static String glosaDe(ActoCoactivo acto) {
        String glosa = acto.tipo().titulo() + " " + acto.numero();
        return glosa.length() > CostaLiquidada.CONCEPTO_MAXIMO
                ? glosa.substring(0, CostaLiquidada.CONCEPTO_MAXIMO)
                : glosa;
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(
            ExpedienteCoactivo expediente, LiquidacionDeCostas liquidacion) {
        return "{\"expediente\":\""
                + expediente.numero()
                + "\",\"numero\":\""
                + liquidacion.numero()
                + "\",\"tributo\":\""
                + liquidacion.tributo()
                + "\",\"total\":"
                + liquidacion.total().valor().toPlainString()
                + ",\"lineas\":"
                + liquidacion.costas().size()
                + ",\"conjuntoId\":"
                + liquidacion.conjuntoId()
                + ",\"fecha\":\""
                + liquidacion.fecha()
                + "\"}";
    }

    /**
     * Lo que la pantalla {@code costas_procesales} manda.
     *
     * @param numeroDeExpediente el numero impreso del expediente cuyo procedimiento se liquida
     * @param fecha el dia de la liquidacion; decide el conjunto sellado y el ejercicio del asiento
     * @param actos que actos se liquidan; vacio significa «todos los que queden y el arancel
     *     tarife»
     */
    public record Peticion(String numeroDeExpediente, LocalDate fecha, Set<Long> actos) {

        public Peticion {
            Objects.requireNonNull(numeroDeExpediente, "Falta el numero de expediente");
            Objects.requireNonNull(fecha, "La liquidacion es de un dia concreto (regla 6)");
            actos = Set.copyOf(actos);
        }

        /** Todos los actos pendientes del expediente. */
        public static Peticion deTodoElExpediente(String numeroDeExpediente, LocalDate fecha) {
            return new Peticion(numeroDeExpediente, fecha, Set.of());
        }
    }

    /**
     * No queda ningun acto del expediente sin liquidar que el arancel tarife.
     *
     * <p>Son <b>dos</b> situaciones y las dos son legitimas: que ya se liquidaran todos, y que la
     * ordenanza publicada no tarife los que quedan. Lo que desde #634 <b>ya no</b> sale por aqui es
     * la tercera —que no haya ninguna ordenanza publicada—, porque eso no es un expediente sin nada
     * que liquidar sino una cifra sin publicar, y se contesta nombrando su llave.
     */
    public static final class SinActosQueLiquidar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinActosQueLiquidar(String numero, Ejercicio ejercicio) {
            super(
                    "El expediente "
                            + numero
                            + " no tiene ningun acto pendiente de liquidar que el arancel de "
                            + ejercicio
                            + " tarife: o ya se liquidaron todos, o la ordenanza no tarifa los que"
                            + " quedan. Liquidar cero no es liquidar");
        }
    }

    /** Se pidio liquidar un acto que no es de ese expediente. */
    public static final class ActoAjeno extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ActoAjeno(String numero, long actoId) {
            super(
                    "El acto "
                            + actoId
                            + " no pertenece al expediente "
                            + numero
                            + ": liquidar en un expediente la costa de un acto de otro mezclaria"
                            + " dos procedimientos, y el obligado del segundo pagaria las"
                            + " actuaciones del primero");
        }
    }
}
