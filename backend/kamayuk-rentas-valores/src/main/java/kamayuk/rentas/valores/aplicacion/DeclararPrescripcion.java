package kamayuk.rentas.valores.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.OrdenDeLosActos;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.valores.dominio.AlcanceDelHecho;
import kamayuk.rentas.valores.dominio.CausalDePrescripcion;
import kamayuk.rentas.valores.dominio.CoberturaDeLaPrescripcion;
import kamayuk.rentas.valores.dominio.ComputoDeEjercicio;
import kamayuk.rentas.valores.dominio.ComputoDePrescripcion;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.HechoDelComputo;
import kamayuk.rentas.valores.dominio.ObligacionPrescrita;
import kamayuk.rentas.valores.dominio.Prescripcion;
import kamayuk.rentas.valores.dominio.PrescripcionRepository;
import kamayuk.rentas.valores.dominio.ResultadoDeLaSolicitud;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Declara la prescripcion de la accion de cobro (#39, RF-094).
 *
 * <h2>No borra la deuda: la marca</h2>
 *
 * <p>No hay una sola sentencia contra {@code cuenta_corriente_asiento} en este camino, ni un {@code
 * DELETE} en ninguna parte (regla 4). Lo que queda es el acto —la fila de {@code prescripcion} con
 * su computo y sus hechos— y el estado {@link EstadoDeValor#PRESCRITO} en los valores cuya deuda
 * prescribio <b>entera</b> (#337, abajo). El libro conserva sus asientos: el dia que alguien
 * pregunte por que dejo de cobrarse, la respuesta es esta fila, y la deuda sigue estando donde
 * estaba.
 *
 * <h2>Y esa deuda SIGUE siendo cartera pendiente y emision del ejercicio (#674)</h2>
 *
 * <p>Es la decision de #674, y hasta ese issue no la habia tomado nadie: se seguia de que este caso
 * de uso no escribiera. La pregunta era «una deuda cuya accion de cobro prescribio, ¿sigue siendo
 * cartera pendiente y emision del ejercicio?», y la respuesta es <b>si, hasta que la administracion
 * la de de baja</b> (RF-044). El panel de recaudacion no cambia, y estas son las razones, en orden:
 *
 * <ol>
 *   <li><b>Lo que la norma dice que prescribe.</b> El art. 43 del TUO del Codigo Tributario —
 *       transcrito literalmente y verificado a doble firma en {@code
 *       docs/10-negocio/valores-normativos/prescripcion-y-plazos.md}— empieza: «La <b>accion</b> de
 *       la Administracion Tributaria para determinar la obligacion tributaria, asi como la
 *       <b>accion para exigir su pago</b> y aplicar sanciones prescribe a los cuatro (4) anios…».
 *       Lo que se pierde es la accion. La cuenta corriente es el libro de la <b>obligacion</b>
 *       —cargos y abonos, RF-040—, y un abono que la cancela afirma que la obligacion desaparecio,
 *       que es falso. El libro no lleva la cuenta de lo que se puede exigir: lleva la de lo que se
 *       debe.
 *   <li><b>Quien lo pide y como se resuelve.</b> Esto es una <b>solicitud</b> —asi la nombra el
 *       propio contrato— del administrado, sobre un rango de ejercicios, y puede salir {@link
 *       ResultadoDeLaSolicitud#NO_PROCEDE} o {@link ResultadoDeLaSolicitud#PROCEDE_EN_PARTE}. Que
 *       prospere no es una decision de la municipalidad de dejar de cobrar: es la perdida de una
 *       facultad. Si escribiera en el libro, el escrito de un tercero moveria la contabilidad
 *       municipal sin ninguna resolucion que lo ordenara.
 *   <li><b>La asimetria con RF-044 no es el mismo hecho tratado de dos maneras: son dos actos.</b>
 *       La causal de la baja de deuda se llama «PRESCRIPCIÓN <b>DECLARADA</b>» —participio, como
 *       sus vecinas «DEUDA DE COBRANZA DUDOSA» y «CONDONACIÓN POR ORDENANZA»—: nombra el
 *       <b>sustento</b> de la baja, no la baja. Y medido: {@code
 *       MovimientosDeDeudaController.PeticionDeMovimiento} —el cuerpo de RF-043 y RF-044, que es
 *       una lista blanca de diecinueve campos— <b>no tiene ninguno para la causal</b>; la interfaz
 *       la antepone a la observacion, que acaba en el {@code motivo} del asiento. Asi que el libro
 *       no sabe siquiera que una baja fue por prescripcion. Lo que hay son dos actos con dos
 *       autores: uno declara que se perdio la accion, y otro —de la administracion, con su
 *       privilegio, su sustento y su observacion— decide retirar de la cartera lo que ya no puede
 *       exigir.
 *   <li><b>Lo que costaria la otra respuesta.</b> Si esto extinguiera en el libro, la obligacion
 *       saldria de la cartera y la ventanilla <b>no podria recibir un pago voluntario</b>: {@code
 *       CobrarDeuda} no tiene sobre que abonar. Y ese pago existe —la prescripcion es oponible por
 *       quien la gano y sobre el rango que gano—, de modo que la salida comoda produciria un
 *       rechazo en caja que ninguna norma respalda.
 * </ol>
 *
 * <p><b>Lo que el corpus NO transcribe, dicho para que nadie lo cite de memoria desde aqui:</b> de
 * los articulos del TUO del Codigo Tributario que rodean a la prescripcion, el corpus de este
 * repositorio solo tiene verificados los <b>43 a 46</b> —mas el 104 y el 106 de notificacion—. Los
 * arts. 27 (medios de extincion de la obligacion), 47, 48 y 49 no estan. La decision de arriba se
 * apoya en el 43, que si esta; el cuarto argumento describe una consecuencia <b>del sistema</b> —la
 * ventanilla se quedaria sin obligacion sobre la que abonar— y no una cita.
 *
 * <p><b>Por eso no hay acto nuevo en el libro ni migracion</b>: el {@code CHECK} de V68 sigue con
 * sus dos valores. Lo que si hace falta —y es lo que #674 construye— es que la prescripcion se
 * <b>vea</b>: {@link ConsultaDePrescripciones} publica la relacion de declaraciones con los
 * ejercicios que de verdad prescribieron, porque una deuda inexigible que no se puede ver en
 * ninguna parte deja la decision indistinguible de un descuido.
 *
 * <p><b>Lo que esta decision cuesta, dicho antes de que alguien lo descubra:</b> mientras nadie
 * registre la baja, esa deuda sigue devengando en la cartera. No es un efecto colateral que se
 * pasara por alto — es la consecuencia de que la obligacion siga existiendo, y lo que la cierra es
 * el acto de la administracion, no el paso del tiempo.
 *
 * <h2>Ejercicio por ejercicio</h2>
 *
 * <p>La solicitud pide un rango y lo normal es que los primeros ejercicios hayan prescrito y los
 * ultimos no: por eso el computo se resuelve uno a uno y el resultado puede ser {@link
 * ResultadoDeLaSolicitud#PROCEDE_EN_PARTE}. Resolver el rango entero con un si o un no obligaria a
 * redondear hacia el contribuyente —extinguiendo deuda viva— o hacia la municipalidad —cobrando lo
 * prescrito—.
 *
 * <h2>Y el valor, por sus lineas (#337)</h2>
 *
 * <p>Lo mismo vale un nivel mas abajo. Un valor formaliza varias obligaciones —{@code POST
 * /valores} con N selectores, la emision masiva con todo el rango—, y hasta #337 bastaba con que
 * una linea coincidiera con un ejercicio prescrito para marcarlo entero: el PREDIAL 2022 que no
 * prescribio y los ARBITRIOS 2021 que nadie pidio acababan dentro de un titulo {@code PRESCRITO},
 * del que no sale nada —coactiva lo rechaza como no cobrable y no hay acto que lo revierta—. Ahora
 * lo decide {@link CoberturaDeLaPrescripcion}: solo {@code TOTAL} marca, con el conjunto prescrito
 * <b>acumulado</b> del contribuyente; {@code PARCIAL} se deja como estaba y se informa en la
 * respuesta y en la auditoria. En esa direccion y no en la otra porque {@code PRESCRITO} es
 * irreversible y {@code EMITIDO} no, y porque #674 ya dijo que la prescripcion la opone quien la
 * gano sobre el rango que gano. Que coactiva cobre un valor {@code PARCIAL} por sus lineas vivas es
 * el paso siguiente, y exige que el expediente importe lineas y no valores enteros.
 *
 * <h2>Cada hecho, en el computo de su ejercicio (#334)</h2>
 *
 * <p>Los arts. 45 y 46 interrumpen o suspenden el plazo de una deuda concreta, no el de todo el
 * rango. Hasta #334 este caso de uso pasaba la <b>misma</b> lista de hechos al computo de cada
 * ejercicio: el pago parcial del predial 2019 reiniciaba tambien el plazo del 2020 —negando una
 * prescripcion que procedia— y, en un rango hasta 2021, dejaba el inicio vigente del 2021 antes de
 * su inicio y la solicitud entera salia 422.
 *
 * <p>Ahora cada hecho declara su {@link AlcanceDelHecho}, y el filtro se hace <b>aqui</b>, antes de
 * llamar a {@link ComputoDePrescripcion#resolver}: la funcion pura recibe los hechos de un
 * ejercicio y no aprende nada de rangos. Lo que no se hace es suponer: en un rango de mas de un
 * ejercicio, un hecho sin alcance es {@link HechoSinAlcance}, porque suponer «todos» es el defecto
 * entero. En un rango de uno no hay a donde mas pertenecer y se completa con ese ejercicio, que es
 * lo que se guarda.
 *
 * <h2>El plazo y el inicio salen del parametro, no del codigo</h2>
 *
 * <p>Los dos: cuantos anios dura (art. 43, segun la causal) y desde cuando se cuenta (art. 44,
 * segun el tributo). {@link PlazosParametrizados} los lee del conjunto sellado vigente a la fecha
 * de presentacion —la fecha del hecho—, y el identificador de ese conjunto queda en la fila, para
 * que revisar la resolucion dentro de dos anios no resuelva otro plazo (ARQ-09 §3).
 */
@Service
public class DeclararPrescripcion {

    private final PrescripcionRepository repositorio;
    private final ValorRepository valores;
    private final PlazosParametrizados plazos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public DeclararPrescripcion(
            PrescripcionRepository repositorio,
            ValorRepository valores,
            PlazosParametrizados plazos,
            Auditoria auditoria,
            Clock reloj) {
        this.repositorio = repositorio;
        this.valores = valores;
        this.plazos = plazos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Resuelve la solicitud y deja el acto.
     *
     * @param contribuyenteId quien solicita; ya resuelto por quien llama
     * @param tributo sobre que tributo
     * @param ejercicioDesde primero del rango solicitado
     * @param ejercicioHasta ultimo del rango solicitado
     * @param fechaPresentacion cuando se presento; es la fecha a la que se resuelve el computo y de
     *     la que sale el conjunto de parametros, no "hoy"
     * @param causal cual de los tres plazos del art. 43 aplica
     * @param hechos las interrupciones y suspensiones alegadas; puede ir vacia. Cada una con su
     *     alcance, salvo en un rango de un solo ejercicio (#334)
     * @param resolucion el numero de la resolucion, si ya se emitio
     * @param observacion por que se declara (regla 10)
     * @return el acto, con los valores que marco y los que dejo porque formalizan deuda viva
     * @throws kamayuk.rentas.dominio.ActoFueraDeOrden si la presentacion es posterior a hoy (#402)
     */
    @Transactional
    public PrescripcionDeclarada declarar(
            long contribuyenteId,
            String tributo,
            Ejercicio ejercicioDesde,
            Ejercicio ejercicioHasta,
            LocalDate fechaPresentacion,
            CausalDePrescripcion causal,
            List<HechoDelComputo> hechos,
            @Nullable String resolucion,
            Observacion observacion) {

        if (ejercicioDesde.compareTo(ejercicioHasta) > 0) {
            throw new RangoInvertido(ejercicioDesde, ejercicioHasta);
        }

        // #402: el computo se resuelve a la fecha de presentacion, y lo que prescribe se marca
        // PRESCRITO hoy. Una presentacion fechada despues de hoy declaraba prescrito lo que todavia
        // no lo esta, en una tabla que no admite correccion.
        OrdenDeLosActos.exigir(
                "la presentacion de la solicitud de prescripcion",
                fechaPresentacion,
                LocalDate.now(reloj));

        List<HechoDelComputo> conAlcance = conSuAlcance(hechos, ejercicioDesde, ejercicioHasta);

        PlazosParametrizados.Vigentes vigentes = plazos.aLaFechaDe(fechaPresentacion);
        Plazo plazo = vigentes.paraPrescribir(causal);
        Plazo desfase = vigentes.inicioDelComputo(tributo);

        List<ComputoDeEjercicio> computos = new ArrayList<>();
        int prescritos = 0;
        for (int anio = ejercicioDesde.valor(); anio <= ejercicioHasta.valor(); anio++) {
            Ejercicio ejercicio = new Ejercicio(anio);
            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            inicioDelComputo(ejercicio, desfase),
                            plazo,
                            delEjercicio(conAlcance, ejercicio),
                            fechaPresentacion);
            if (computo.prescrita()) {
                prescritos++;
            }
            computos.add(ComputoDeEjercicio.nuevo(ejercicio, computo));
        }

        Prescripcion guardada =
                repositorio.insertar(
                        new Prescripcion(
                                null,
                                contribuyenteId,
                                tributo,
                                ejercicioDesde,
                                ejercicioHasta,
                                fechaPresentacion,
                                causal,
                                plazo,
                                vigentes.conjuntoId(),
                                ResultadoDeLaSolicitud.de(prescritos, computos.size()),
                                resolucion,
                                computos,
                                conAlcance,
                                null,
                                observacion));

        PrescripcionDeclarada declarada = marcarLosValoresCubiertos(guardada);
        auditar(declarada, observacion);
        return declarada;
    }

    // ------------------------------------------------------------------

    /**
     * Los hechos con su alcance resuelto, o el rechazo que nombra al que no lo tiene (#334).
     *
     * <p>Se resuelve <b>antes</b> de leer ningun parametro: un hecho mal declarado es un error de
     * la peticion, y tiene que decirse aunque falte publicar el plazo.
     */
    private static List<HechoDelComputo> conSuAlcance(
            List<HechoDelComputo> hechos, Ejercicio desde, Ejercicio hasta) {
        List<HechoDelComputo> resueltos = new ArrayList<>(hechos.size());
        for (HechoDelComputo hecho : hechos) {
            if (!hecho.alcance().declarado()) {
                if (!desde.equals(hasta)) {
                    throw new HechoSinAlcance(hecho, desde, hasta);
                }
                resueltos.add(hecho.con(AlcanceDelHecho.de(desde)));
                continue;
            }
            for (Ejercicio ejercicio : hecho.alcance().ejercicios()) {
                if (ejercicio.compareTo(desde) < 0 || ejercicio.compareTo(hasta) > 0) {
                    throw new AlcanceFueraDelRango(hecho, ejercicio, desde, hasta);
                }
            }
            resueltos.add(hecho);
        }
        return List.copyOf(resueltos);
    }

    /** Los hechos que actuan sobre el computo de ese ejercicio, y ninguno mas (#334). */
    private static List<HechoDelComputo> delEjercicio(
            List<HechoDelComputo> hechos, Ejercicio ejercicio) {
        return hechos.stream().filter(hecho -> hecho.alcance().alcanza(ejercicio)).toList();
    }

    /**
     * El dia 1 del computo: el 1 de enero del ejercicio mas el desfase parametrizado (art. 44).
     *
     * <p>El desfase es un plazo en anios porque el art. 44 lo expresa asi —"desde el uno (1) de
     * enero del anio siguiente a la fecha en que vence el plazo para la presentacion de la
     * declaracion anual respectiva"—, y cuantos anios sean depende de cuando vence esa declaracion,
     * que es distinto por tributo. Por eso entra por parametro y no como un uno compilado.
     */
    private static LocalDate inicioDelComputo(Ejercicio ejercicio, Plazo desfase) {
        return LocalDate.of(ejercicio.valor(), 1, 1).plusYears(desfase.cantidad());
    }

    /**
     * Marca {@code PRESCRITO} los valores que la declaracion deja cubiertos del todo (#337).
     *
     * <p>Solo los valores cobrables: uno ya pagado o anulado no tiene accion de cobro que
     * prescriba, y sobreescribir su estado borraria el dato de que se pago. Y de esos, solo los que
     * tocan lo que <b>esta</b> resolucion prescribio: los demas no cambian por ella.
     *
     * <p>El conjunto se lee <b>despues</b> de insertar, asi que ya incluye esta resolucion. Por eso
     * un candidato no puede salir {@link CoberturaDeLaPrescripcion#NINGUNA}: tiene una linea en lo
     * que se acaba de prescribir. Si sale, el conjunto y la consulta no dicen lo mismo, y marcar o
     * callar sobre esa base seria decidir a ciegas: se aborta la transaccion.
     */
    private PrescripcionDeclarada marcarLosValoresCubiertos(Prescripcion prescripcion) {
        List<Valor> prescritos = new ArrayList<>();
        List<Valor> enParte = new ArrayList<>();
        List<Valor> candidatos =
                valores.cobrablesConAlgunaLineaEn(
                        prescripcion.contribuyenteId(),
                        prescripcion.tributo(),
                        prescripcion.ejerciciosPrescritos());
        if (candidatos.isEmpty()) {
            return new PrescripcionDeclarada(prescripcion, prescritos, enParte);
        }
        Set<ObligacionPrescrita> acumuladas =
                repositorio.obligacionesPrescritasDe(prescripcion.contribuyenteId());
        for (Valor valor : candidatos) {
            long id = Objects.requireNonNull(valor.id(), "Un valor leido de la base tiene id");
            CoberturaDeLaPrescripcion cobertura =
                    CoberturaDeLaPrescripcion.de(valores.detalleDe(id), acumuladas);
            if (cobertura == CoberturaDeLaPrescripcion.TOTAL) {
                prescritos.add(valores.cambiarEstado(id, EstadoDeValor.PRESCRITO));
            } else if (cobertura == CoberturaDeLaPrescripcion.PARCIAL) {
                enParte.add(valor);
            } else {
                throw new IllegalStateException(
                        "El valor "
                                + valor.numero()
                                + " toca lo que la prescripcion "
                                + prescripcion.id()
                                + " acaba de declarar, y el conjunto prescrito no lo cubre en"
                                + " nada");
            }
        }
        return new PrescripcionDeclarada(prescripcion, prescritos, enParte);
    }

    private void auditar(PrescripcionDeclarada declarada, Observacion observacion) {
        Prescripcion prescripcion = declarada.prescripcion();
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                "prescripcion",
                                String.valueOf(prescripcion.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(declarada)));
    }

    /**
     * Sin datos personales: esto acaba en la columna JSON de la auditoria.
     *
     * <p>Con los numeros de los valores de los dos grupos (#337): los que marco y, sobre todo, los
     * que no marco porque formalizan deuda viva, que es lo que la administracion tiene que poder
     * encontrar despues. Un numero de valor no es un dato personal.
     */
    private static String descripcion(PrescripcionDeclarada declarada) {
        Prescripcion prescripcion = declarada.prescripcion();
        return "{\"tributo\":\""
                + prescripcion.tributo()
                + "\",\"desde\":"
                + prescripcion.ejercicioDesde().valor()
                + ",\"hasta\":"
                + prescripcion.ejercicioHasta().valor()
                + ",\"causal\":\""
                + prescripcion.causal()
                + "\",\"plazo\":\""
                + prescripcion.plazo()
                + "\",\"resultado\":\""
                + prescripcion.resultado()
                + "\",\"prescritos\":"
                + prescripcion.ejerciciosPrescritos().size()
                + ",\"valoresPrescritos\":"
                + numerosDe(declarada.valoresPrescritos())
                + ",\"valoresCubiertosEnParte\":"
                + numerosDe(declarada.valoresCubiertosEnParte())
                + "}";
    }

    private static String numerosDe(List<Valor> valores) {
        StringBuilder texto = new StringBuilder("[");
        for (Valor valor : valores) {
            if (texto.length() > 1) {
                texto.append(',');
            }
            texto.append('"').append(valor.numero()).append('"');
        }
        return texto.append(']').toString();
    }

    /**
     * Un hecho sin alcance en una solicitud de mas de un ejercicio (#334): 422 nombrandolo.
     *
     * <p>No se supone que alcanza a todos, porque eso es el defecto: el pago parcial del predial
     * 2019 no interrumpe el plazo del 2020.
     */
    public static final class HechoSinAlcance extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        HechoSinAlcance(HechoDelComputo hecho, Ejercicio desde, Ejercicio hasta) {
            super(
                    "El hecho "
                            + hecho.descripcion()
                            + " no dice de que ejercicio es la deuda que toca: en una solicitud de "
                            + desde.valor()
                            + " a "
                            + hasta.valor()
                            + " hay que declararlo en 'hechos[].ejercicios'");
        }
    }

    /** Un hecho que dice ser de un ejercicio que la solicitud no pide (#334): 422 nombrandolo. */
    public static final class AlcanceFueraDelRango extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        AlcanceFueraDelRango(
                HechoDelComputo hecho, Ejercicio fuera, Ejercicio desde, Ejercicio hasta) {
            super(
                    "El hecho "
                            + hecho.descripcion()
                            + " declara el ejercicio "
                            + fuera.valor()
                            + ", que no esta en la solicitud: va de "
                            + desde.valor()
                            + " a "
                            + hasta.valor());
        }
    }

    /** El rango de ejercicios va al reves. */
    public static final class RangoInvertido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        RangoInvertido(Ejercicio desde, Ejercicio hasta) {
            super("El rango de ejercicios va de menor a mayor: " + desde + " a " + hasta);
        }
    }
}
