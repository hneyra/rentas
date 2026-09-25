package kamayuk.rentas.sanciones.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ExtincionDeDeuda;
import kamayuk.rentas.cuentacorriente.MovimientoAsentado;
import kamayuk.rentas.cuentacorriente.ObligacionCompartida;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.ActoFueraDeOrden;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.OrdenDeLosActos;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import kamayuk.rentas.valores.ValoresSobreUnaObligacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deja sin efecto una papeleta: la <b>única</b> transición que este sistema escribe sobre ella
 * (#267).
 *
 * <h2>Por qué hacía falta, y por qué sólo ésta de las seis que faltaban</h2>
 *
 * <p>{@code EstadoDePapeleta} declara siete valores y la producción escribía <b>uno</b>: el {@code
 * INSERT} pone {@code IMPUESTA} y el único {@code UPDATE papeleta} de todo {@code src/main} era
 * {@code SET numero} (#46). Medido en #243 y confirmado en #259. Consecuencia: las dos guardas que
 * descartan una papeleta muerta —{@link RegistrarDescargo} y {@link
 * ResolverConResolucionDeGerencia}, con {@link RegistrarDescargo.PapeletaSinNadaQueImpugnar}— <b>no
 * descartaban nada</b>, porque ninguna fila podía llegar a ese estado; estaban escritas, probadas
 * con dobles, e inertes.
 *
 * <p>De los seis que nadie escribía, cinco <b>se derivan</b> de otros hechos y por eso no se
 * escriben —{@code EstadoDePapeleta} lo explica valor por valor—. {@code ANULADA} no se deriva de
 * nada: que una papeleta no vale es un acto de la administración, igual que el {@code ANULADA} del
 * acta de fiscalización (#214), y por eso es la única que se añade.
 *
 * <h2>Y {@code PRESCRITA} tampoco se añade, con la medida delante</h2>
 *
 * <p>Parece el mismo caso —«tampoco se deriva de nada, la declara un acto»— y no lo es. Lo que se
 * midió:
 *
 * <ol>
 *   <li>{@code DeclararPrescripcion.declarar(contribuyenteId, tributo, ejercicioDesde,
 *       ejercicioHasta, …)} <b>no nombra ninguna papeleta</b>, ni en su firma ni en su cuerpo:
 *       resuelve ejercicio por ejercicio y marca {@code PRESCRITO} los valores cuyas lineas
 *       prescribieron todas —lo decide {@code CoberturaDeLaPrescripcion} desde #337; antes bastaba
 *       con que {@code ValorRepository.cobrablesDe} devolviera el valor—.
 *   <li>Para escribir {@code PRESCRITA} en la papeleta habría que volver del valor a la papeleta
 *       —{@code valor} → {@code papeleta_masivo_item.valor_id} → {@code papeleta}—, y ese cruce
 *       vive en {@code sanciones}. {@code valores} no lo puede hacer: no existe ningún puerto de
 *       {@code sanciones} que lo publique, y un {@code JOIN} cruzaría el límite del módulo.
 *   <li>Y <b>no sería completo</b>. Ese cruce sólo conoce los valores de la corrida masiva, que
 *       exige resolución de multa dictada <b>y</b> notificada ({@code
 *       ProcesarPapeletaDeLaCorrida.impedimentoDe}); los que salen de la emisión individual o de la
 *       masiva de valores no dejan fila en {@code papeleta_masivo_item} (#372). Una papeleta que
 *       nunca pasó la corrida no recibiría {@code PRESCRITA} jamás, mientras que la deuda que
 *       originó —asentada por {@code RegistrarPapeleta} con tributo {@code MULTA_TRANSITO} o {@code
 *       MULTA_ADMINISTRATIVA}— prescribe igual. Dos verdades que discrepan, que es lo que #234 y
 *       #259 evitaron.
 *   <li>Y hay un tercer sitio que ya existe: #674 decidió que declarar la prescripción <b>no toca
 *       el libro</b>, y que su huella son la fila de {@code prescripcion} y el estado del valor.
 *       Añadir la columna de la papeleta sería el tercero.
 * </ol>
 *
 * <p>Así que {@code PRESCRITA} se queda como {@code PAGADA} y {@code COACTIVA}: un valor que sólo
 * un padrón migrado trae, que el enumerado tiene que poder <b>leer</b> (#259) y que este sistema no
 * escribe.
 *
 * <h2>Una papeleta cuya multa sostiene un valor vivo no se anula</h2>
 *
 * <p>Es lo que hace que esto sea de carga y no prosa, y es el mismo trato que {@code
 * AnularActaFiscalizacion} le da a una liquidación viva: anular la papeleta cuya multa ya se
 * formalizó en un valor dejaría ese valor —y quizá su expediente coactivo, que admite por valor y
 * no por obligación— cobrando una sanción que no existe, con el papel ya en manos del administrado.
 * Quien anula el valor es {@code valores}, y {@code sanciones} no tiene ningún puerto para hacerlo:
 * por eso aquí se rechaza diciendo qué valor lo impide en vez de dejar las dos mitades en
 * desacuerdo.
 *
 * <p><b>La pregunta se le hace a {@code valores}, y no a una copia propia (#372).</b> Hasta #372 se
 * leía la fila {@code GENERADO} de {@code papeleta_masivo_item}, que sólo deja la corrida por
 * papeletas; una RM —o una OP, o una RD— emitida desde la ventanilla de valores ({@code POST
 * /api/v1/valores}) sobre la misma deuda no dejaba esa fila, la anulación contestaba 201 y la baja
 * se llevaba lo que ese valor estaba cobrando. Saber si un valor <b>vivo</b> formaliza una
 * obligación es un hecho de {@code valores}, que ve todos los caminos por los que nace uno: {@link
 * ValoresSobreUnaObligacion}. Vivo, y no «que existió»: una RM ya anulada no sostiene nada.
 *
 * <p>Y se pregunta <b>por la obligación del libro</b>, que es lo que un valor formaliza. Mientras
 * dos papeletas del mismo obligado compartan obligación (#465), un valor vivo de la otra también
 * impide anular ésta. Es el lado conservador y es el correcto: la baja de más abajo extinguiría la
 * deuda que ese valor cobra.
 *
 * <h2>No borra: da de baja lo que la papeleta cargó</h2>
 *
 * <p>Regla 4 (RNF-051): la fila sigue entera y se sigue leyendo. Lo que cambia además del estado es
 * el <b>libro</b>: la papeleta asentó su cargo al registrarse ({@code RegistrarPapeleta}), y
 * anularla sin dar de baja esa deuda dejaría exactamente el defecto que {@code
 * ObligacionDeLaPapeleta} nombra —«una papeleta anulada que sigue debiendo»—: {@code
 * EstadoDePapeleta#seDebe()} diría que no se debe nada, la ventanilla seguiría cobrándola y ninguna
 * de las dos pantallas sabría de la otra.
 *
 * <p>La causal es {@link CausalDeBaja#ERROR_MATERIAL}, «la baja que deshace un alta que no debió
 * existir», que es exactamente lo que una anulación de papeleta es. <b>No</b> es {@code
 * RESOLUCION_QUE_DEJA_SIN_EFECTO}: ésa es la de {@link ResolverConResolucionDeGerencia}, donde hay
 * una resolución de gerencia que la ordena y un recurso que la motiva. Aquí no hay recurso ni
 * resolución: hay un acto de la administración sobre su propio error.
 *
 * <h2>Y sólo si la obligación es sólo suya (#371)</h2>
 *
 * <p>La obligación del libro no identifica a la papeleta: dos multas del mismo obligado, del mismo
 * ejercicio y de la misma unidad —o sin vehículo del padrón— se suman en una. Por eso la baja pasa
 * por {@link ExtincionDeDeuda#extinguirLoOriginadoPor}, que antes de abonar comprueba que todos los
 * cargos lleven la referencia de esta papeleta; si hay otra, se rechaza con {@link
 * ObligacionCompartidaConOtraPapeleta} nombrándola. Bloquea una anulación legítima, y es a
 * propósito: la alternativa era extinguir la multa de otra papeleta sin ningún acto. Que la
 * papeleta sea la unidad de su obligación es #465.
 *
 * <p>Y si la multa ya estaba cobrada no se llega hasta aquí: {@link Papeleta#anulada} lo rechaza
 * antes, porque una {@code PAGADA} ya no se debe. Lo que corresponde con lo cobrado de más es una
 * devolución, que es otro procedimiento.
 *
 * <h2>Y la fecha, ni antes de la infracción ni después de hoy (#402)</h2>
 *
 * <p>La fecha del acto es la de corte y la <b>fecha valor</b> de la baja, y el cargo de la papeleta
 * nace con fecha valor la de la infracción ({@code RegistrarPapeleta}). Hasta #402 nadie la
 * acotaba, y medido: una infracción del 4 de marzo anulada «el 1 de marzo» —un 1 tecleado por un 4—
 * releía la deuda a esa fecha, no veía el cargo, escribía <b>cero</b> asientos y confirmaba. La
 * papeleta quedaba {@code ANULADA} con 428,00 en el libro, y sin vuelta atrás: otra anulación choca
 * con {@link Papeleta.TransicionIlegal}. Y una fechada después de hoy asentaba una baja con fecha
 * valor futura: toda consulta «a hoy» seguía viendo la deuda hasta ese día. {@link OrdenDeLosActos}
 * rechaza las dos antes de tocar nada.
 *
 * <p><b>Y detrás, una defensa donde el daño no tiene marcha atrás.</b> Si la baja no asienta nada y
 * la obligación sigue debiendo a hoy, se rechaza con {@link AnulacionQueNoDaDeBaja} y la
 * transacción se deshace entera. Con la fecha acotada no debería pasar —el cargo nace el día de la
 * infracción—, y por eso es defensa y no regla: si mañana un cargo de la papeleta naciera con otra
 * fecha valor, el síntoma sería éste, y lo que queda es un 409 y no una papeleta anulada debiendo.
 */
@Service
public class AnularPapeleta {

    private static final String TABLA_AUDITADA = "papeleta";

    private final PapeletaRepository papeletas;
    private final ValoresSobreUnaObligacion valores;
    private final ExtincionDeDeuda extincion;
    private final ConsultaDeDeudaPublica deudas;
    private final Auditoria auditoria;
    private final Clock reloj;

    public AnularPapeleta(
            PapeletaRepository papeletas,
            ValoresSobreUnaObligacion valores,
            ExtincionDeDeuda extincion,
            ConsultaDeDeudaPublica deudas,
            Auditoria auditoria,
            Clock reloj) {
        this.papeletas = papeletas;
        this.valores = valores;
        this.extincion = extincion;
        this.deudas = deudas;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Anula la papeleta y da de baja lo que cargó.
     *
     * @param familia de qué familia es la papeleta
     * @param numero el número impreso
     * @param fecha el día del acto, no el de su registro (regla 9)
     * @param observacion por qué se anula (regla 10, RNF-052)
     * @throws RegistrarDescargo.PapeletaInexistente si no hay ninguna con ese número en esa familia
     * @throws PapeletaConResolucionDeMulta si un valor vivo formaliza su obligación, lo emitiera
     *     quien lo emitiera (#372)
     * @throws Papeleta.TransicionIlegal si en ese estado ya no se debe nada
     * @throws ObligacionCompartidaConOtraPapeleta si su obligacion del libro tiene tambien la multa
     *     de otra papeleta (#371): anularla extinguiria las dos
     * @throws ActoFueraDeOrden si la fecha es anterior a la infraccion o posterior a hoy (#402)
     * @throws AnulacionQueNoDaDeBaja si la baja no asienta nada y la obligacion sigue debiendo a
     *     hoy (#402)
     */
    @Transactional
    public Anulada anular(
            Familia familia, String numero, LocalDate fecha, Observacion observacion) {

        Papeleta antes =
                papeletas
                        .porNumero(familia, numero)
                        .orElseThrow(
                                () -> new RegistrarDescargo.PapeletaInexistente(familia, numero));

        LocalDate hoy = LocalDate.now(reloj);
        OrdenDeLosActos.exigir(
                "la anulacion de la papeleta " + antes.numero(), fecha, hoy, antes.laInfraccion());

        exigirQueNadaLaSostenga(antes);

        Papeleta anulada = papeletas.anular(antes.identificador());

        MovimientoAsentado baja;
        try {
            // La variante que comprueba el origen (#371): la obligacion de la papeleta es la de
            // todas las multas del obligado en ese tributo, ejercicio y unidad, y `extinguir` a
            // secas se llevaba tambien las de las otras papeletas.
            baja =
                    extincion.extinguirLoOriginadoPor(
                            antes.obligadoId(),
                            ObligacionDeLaPapeleta.de(antes),
                            fecha,
                            "ANULACION PAPELETA " + antes.numero(),
                            ObligacionDeLaPapeleta.referenciaDe(antes),
                            // «La baja que deshace un alta que no debio existir», que es lo que
                            // una anulacion de papeleta es. La declara quien anula, como en #684:
                            // un puerto que la dedujera de su unico caller de hoy afirmaria
                            // manana lo que ya no es cierto.
                            CausalDeBaja.ERROR_MATERIAL,
                            observacion);
        } catch (ObligacionCompartida compartida) {
            // Se relanza y la transaccion entera se deshace: tampoco queda la papeleta ANULADA,
            // que es justo el «anulada y debiendo» que esta clase existe para impedir.
            throw ObligacionDeLaPapeleta.compartida(antes, compartida, papeletas, "anularla");
        }

        if (baja.asientos() == 0) {
            exigirQueNoSigaDebiendo(antes, hoy);
        }

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                TABLA_AUDITADA,
                                String.valueOf(antes.identificador()),
                                Operacion.MODIFICACION,
                                observacion)
                        .con(descripcion(antes), descripcion(anulada)));

        return new Anulada(anulada, baja);
    }

    // ------------------------------------------------------------------

    /**
     * Pregunta a {@code valores}, no a {@code papeleta_masivo_item} (#372): la corrida es uno de
     * los tres caminos por los que nace un valor sobre esta deuda, y el único que deja esa fila. La
     * pregunta es la misma que hace {@link ResolverConResolucionDeGerencia} (#495), y por eso vive
     * en {@link ObligacionDeLaPapeleta}.
     */
    private void exigirQueNadaLaSostenga(Papeleta papeleta) {
        ObligacionDeLaPapeleta.exigirQueNingunValorVivoLaFormalice(
                papeleta, valores, "la papeleta");
    }

    /**
     * La defensa de #402: la baja no asentó nada, y la obligación <b>no puede</b> seguir debiendo a
     * hoy. Si debe, la anulación dejaría exactamente «una papeleta anulada que sigue debiendo», y
     * sin vuelta atrás; se rechaza y la transacción se deshace, con el estado de la papeleta.
     *
     * <p>Se pregunta a hoy y no a la fecha del acto, porque lo que se protege es lo que la
     * ventanilla cobraría mañana. Y se pregunta por la obligación que {@link
     * ObligacionDeLaPapeleta} compone, la misma que la baja acaba de intentar extinguir.
     */
    private void exigirQueNoSigaDebiendo(Papeleta papeleta, LocalDate hoy) {
        ObligacionPublica deuda = ObligacionDeLaPapeleta.deudaDe(papeleta, deudas, hoy);
        if (deuda != null && deuda.estaPendiente()) {
            throw new AnulacionQueNoDaDeBaja(papeleta, deuda);
        }
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoría. */
    private static String descripcion(Papeleta papeleta) {
        return "{\"estado\":\"" + papeleta.estado() + "\"}";
    }

    /**
     * La papeleta anulada y lo que de verdad se dio de baja.
     *
     * @param papeleta la fila, ya {@code ANULADA} y con todo lo demás intacto
     * @param baja los asientos de la baja; vacía si a esa fecha no se debía nada
     */
    public record Anulada(Papeleta papeleta, MovimientoAsentado baja) {}

    /**
     * La baja no extinguió nada y la obligación sigue debiendo a hoy (#402): anular dejaría una
     * papeleta {@code ANULADA} que el libro sigue cobrando, y sin vuelta atrás.
     *
     * <p>Sale {@code 409} y no {@code 422}: la fecha ya pasó {@link OrdenDeLosActos}, así que lo
     * que no admite el acto es la situación del libro, no un campo de la petición.
     */
    public static final class AnulacionQueNoDaDeBaja extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        AnulacionQueNoDaDeBaja(Papeleta papeleta, ObligacionPublica deuda) {
            super(
                    "La anulacion de la papeleta "
                            + papeleta.numero()
                            + " no dio de baja nada y su obligacion sigue debiendo "
                            + deuda.total().valor().toPlainString()
                            + " al "
                            + deuda.fecha()
                            + ": anularla dejaria una papeleta anulada que el libro sigue"
                            + " cobrando");
        }
    }

    /**
     * La multa de esa papeleta ya se formalizó en un valor que sigue vivo: anularla dejaría ese
     * valor cobrando una sanción que no existe.
     *
     * <p>Se llama así por el caso de siempre —la resolución de multa de la corrida—, pero desde
     * #372 la levanta cualquier valor vivo sobre su obligación: también una OP o una RD emitidas
     * desde la ventanilla de valores. Por eso el mensaje dice «valor» y no «resolución de multa».
     *
     * <p>Y desde #495 la levanta también {@link ResolverConResolucionDeGerencia} cuando la
     * resolución deja la multa sin efecto: es el mismo estado final, y el mismo rechazo.
     */
    public static final class PapeletaConResolucionDeMulta extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        PapeletaConResolucionDeMulta(
                String numeroDePapeleta, String numeroDelValor, String loQueSigue) {
            super(
                    "La multa de la papeleta "
                            + numeroDePapeleta
                            + " la formaliza el valor "
                            + numeroDelValor
                            + ", que sigue vivo: primero se deja sin efecto ese valor y despues "
                            + loQueSigue
                            + ", o quedaria un valor cobrando una sancion que ya no existe");
        }
    }
}
