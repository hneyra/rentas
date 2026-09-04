package kamayuk.rentas.valores.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.valores.dominio.CausalDePrescripcion;
import kamayuk.rentas.valores.dominio.ComputoDeEjercicio;
import kamayuk.rentas.valores.dominio.ComputoDePrescripcion;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.HechoDelComputo;
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
 * su computo y sus hechos— y el estado {@link EstadoDeValor#PRESCRITO} en los valores alcanzados.
 * El libro conserva sus asientos: el dia que alguien pregunte por que dejo de cobrarse, la
 * respuesta es esta fila, y la deuda sigue estando donde estaba.
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

    public DeclararPrescripcion(
            PrescripcionRepository repositorio,
            ValorRepository valores,
            PlazosParametrizados plazos,
            Auditoria auditoria) {
        this.repositorio = repositorio;
        this.valores = valores;
        this.plazos = plazos;
        this.auditoria = auditoria;
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
     * @param hechos las interrupciones y suspensiones alegadas; puede ir vacia
     * @param resolucion el numero de la resolucion, si ya se emitio
     * @param observacion por que se declara (regla 10)
     */
    @Transactional
    public Prescripcion declarar(
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

        PlazosParametrizados.Vigentes vigentes = plazos.aLaFechaDe(fechaPresentacion);
        Plazo plazo = vigentes.paraPrescribir(causal);
        Plazo desfase = vigentes.inicioDelComputo(tributo);

        List<ComputoDeEjercicio> computos = new ArrayList<>();
        int prescritos = 0;
        for (int anio = ejercicioDesde.valor(); anio <= ejercicioHasta.valor(); anio++) {
            Ejercicio ejercicio = new Ejercicio(anio);
            ComputoDePrescripcion.Computo computo =
                    ComputoDePrescripcion.resolver(
                            inicioDelComputo(ejercicio, desfase), plazo, hechos, fechaPresentacion);
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
                                hechos,
                                null,
                                observacion));

        marcarLosValoresAlcanzados(guardada);
        auditar(guardada, observacion);
        return guardada;
    }

    // ------------------------------------------------------------------

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
     * Marca {@code PRESCRITO} lo que la declaracion alcanza.
     *
     * <p>Solo los valores cobrables: uno ya pagado o anulado no tiene accion de cobro que
     * prescriba, y sobreescribir su estado borraria el dato de que se pago.
     */
    private void marcarLosValoresAlcanzados(Prescripcion prescripcion) {
        for (Ejercicio ejercicio : prescripcion.ejerciciosPrescritos()) {
            for (Valor valor :
                    valores.cobrablesDe(
                            prescripcion.contribuyenteId(), prescripcion.tributo(), ejercicio)) {
                Long id = valor.id();
                if (id != null) {
                    valores.cambiarEstado(id, EstadoDeValor.PRESCRITO);
                }
            }
        }
    }

    private void auditar(Prescripcion prescripcion, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                prescripcion.fechaPresentacion(),
                                "prescripcion",
                                String.valueOf(prescripcion.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(prescripcion)));
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(Prescripcion prescripcion) {
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
                + "}";
    }

    /** El rango de ejercicios va al reves. */
    public static final class RangoInvertido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        RangoInvertido(Ejercicio desde, Ejercicio hasta) {
            super("El rango de ejercicios va de menor a mayor: " + desde + " a " + hasta);
        }
    }
}
