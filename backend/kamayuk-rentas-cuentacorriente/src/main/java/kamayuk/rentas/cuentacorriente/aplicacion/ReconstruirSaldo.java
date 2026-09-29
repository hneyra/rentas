package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Divergencia;
import kamayuk.rentas.cuentacorriente.dominio.ProyeccionDelSaldo;
import kamayuk.rentas.cuentacorriente.dominio.SaldoProyectado;
import kamayuk.rentas.cuentacorriente.dominio.SaldoRepository;
import kamayuk.rentas.dominio.Dinero;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconstruye y concilia el saldo proyectado contra el libro (#23).
 *
 * <p>Es la red de seguridad de ADR-0006: «el saldo es cache, no verdad; si discrepa del libro, el
 * libro gana». Aqui viven las dos mitades de esa frase —comprobar que coinciden, y rehacer la
 * proyeccion cuando no—, y estan <b>separadas a proposito</b>: {@link #conciliar} no repara nada.
 *
 * <p>Los dos metodos son de <b>un</b> contribuyente. El recorrido del padron entero vive en {@link
 * ReconstruirPadron}, que es un {@code @Service} distinto, y no es una separacion estetica: llamar
 * a {@link #deContribuyente} desde otro metodo de <i>esta</i> clase pasaria por dentro del proxy de
 * Spring y no abriria transaccion ninguna, con lo que el padron entero caeria en una sola —o en
 * ninguna—. Es el mismo motivo por el que {@code ImportarVias} y {@code RegistrarVia} son dos
 * clases.
 *
 * <p><b>Ninguna auditoria.</b> Reconstruir no modifica ningun dato del contribuyente: recalcula un
 * cache desde una fuente que no se toca. Por eso tampoco pide {@code Observacion}: la regla 10
 * gobierna las modificaciones de datos, y aqui el unico dato que existe —el libro— queda intacto.
 * Si esto exigiera observacion, la exigiria un proceso automatico de madrugada, que no tiene
 * ninguna que dar. Lo mismo vale para {@link #ponerACeroSinLibro} (#641), que es la reconstruccion
 * de quien no tiene libro: lo que el libro dice de el es cero.
 *
 * <p><b>Ese proceso es {@link CorrerLaConciliacionDelSaldo}</b> (#630), que el {@code CronJob}
 * {@code kamayuk-rentas-conciliacion-del-saldo} lanza cada noche: concilia el padron de cada
 * municipalidad y solo reconstruye si se le pide. Hasta #630 no existia, y {@link #conciliar} no lo
 * llamaba nadie fuera de sus pruebas.
 */
@Service
public class ReconstruirSaldo {

    private final AsientoRepository asientos;
    private final SaldoRepository saldos;
    private final Clock reloj;

    public ReconstruirSaldo(AsientoRepository asientos, SaldoRepository saldos, Clock reloj) {
        this.asientos = asientos;
        this.saldos = saldos;
        this.reloj = reloj;
    }

    /**
     * Rehace <b>todos</b> los saldos de un contribuyente desde su libro.
     *
     * @return los saldos que quedaron proyectados
     */
    @Transactional
    public List<SaldoProyectado> deContribuyente(long contribuyenteId) {
        List<SaldoProyectado> proyectados =
                ProyeccionDelSaldo.de(asientos.deContribuyente(contribuyenteId), reloj.instant());
        for (SaldoProyectado saldo : proyectados) {
            saldos.proyectar(saldo);
        }
        return proyectados;
    }

    /**
     * Compara la proyeccion de un contribuyente contra su libro y <b>reporta</b> lo que no cuadra.
     *
     * <p>No repara: ver el javadoc de {@link Divergencia}. Devolver la lista vacia significa que la
     * proyeccion coincide con el libro obligacion por obligacion.
     *
     * <h4>{@code REPEATABLE READ}, porque compara dos tablas (#630)</h4>
     *
     * <p>Lee el libro y despues la proyeccion, en dos sentencias. Con el {@code READ COMMITTED} de
     * siempre cada una ve lo confirmado hasta ELLA, asi que un asiento que otra transaccion
     * confirme entre las dos —la ventanilla, un pago que llega del buzon de {@code caja}, una
     * corrida— entra en la proyeccion y no en el libro ya leido, y un saldo correcto sale como
     * divergencia. Medido: «el libro dice 1000.00 y la proyeccion 1250.00» sobre un contribuyente
     * que cuadraba. Con una sola instantanea para toda la transaccion, las dos lecturas ven lo
     * mismo. Es de lectura, asi que en PostgreSQL no puede fallar por serializacion: no cuesta
     * nada.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Divergencia> conciliar(long contribuyenteId) {
        List<Asiento> libro = asientos.deContribuyente(contribuyenteId);
        Map<ClaveDeSaldo, Dinero> segunElLibro = new LinkedHashMap<>();
        for (SaldoProyectado saldo : ProyeccionDelSaldo.de(libro, reloj.instant())) {
            segunElLibro.put(saldo.clave(), saldo.insolutoSaldo());
        }

        Map<ClaveDeSaldo, Dinero> proyectados = new LinkedHashMap<>();
        for (SaldoProyectado saldo : saldos.deContribuyente(contribuyenteId)) {
            proyectados.put(saldo.clave(), saldo.insolutoSaldo());
        }

        List<Divergencia> divergencias = new ArrayList<>();
        segunElLibro.forEach(
                (clave, delLibro) -> {
                    Dinero proyectado = proyectados.get(clave);
                    if (proyectado == null || !proyectado.equals(delLibro)) {
                        divergencias.add(new Divergencia(clave, proyectado, delLibro));
                    }
                });

        // Y al reves: una fila proyectada de una obligacion que el libro no tiene. Pasa
        // si alguien escribio en la proyeccion algo que nunca se asento, y es tan
        // divergencia como la otra —el libro dice cero y la fila dice otra cosa—.
        //
        // Pero solo si dice OTRA cosa (#641). Una fila a cero de una obligacion sin asientos
        // dice lo mismo que el libro, y es justo como la deja `ponerACeroSinLibro`, que no
        // puede borrarla. Contarla haria de cada fila reparada una divergencia para siempre
        // el dia que su contribuyente estrenara libro con otra obligacion.
        proyectados.forEach(
                (clave, proyectado) -> {
                    if (!segunElLibro.containsKey(clave) && !proyectado.esCero()) {
                        divergencias.add(new Divergencia(clave, proyectado, Dinero.CERO));
                    }
                });

        return List.copyOf(divergencias);
    }

    /**
     * Pone a cero la proyeccion de un contribuyente que <b>no tiene ningun asiento</b>, si sigue
     * diciendo exactamente lo que se informo de el, y devuelve cuantas filas cambio (#641).
     *
     * <p>Es la reparacion que {@link #deContribuyente} no puede hacer: esa reescribe las
     * obligaciones que el libro tiene, y de un contribuyente sin libro no reescribe nada. Y no se
     * puede borrar la fila —{@code kamayuk_app} no tiene {@code DELETE} sobre {@code
     * saldo_proyectado}, y la regla 4 no se negocia—, asi que se deja diciendo lo que dice el
     * libro: cero.
     *
     * <h4>Solo lo que ya salio en una linea ERROR</h4>
     *
     * <p>{@code informadas} es lo que {@link #conciliar} dijo de el en la pasada que ahora repara,
     * y que {@link CorrerLaConciliacionDelSaldo} acaba de escribir, cifra a cifra, en su linea
     * ERROR. Si su proyeccion ya no dice eso —le aparecio otra fila, o una cifra cambio—, no se
     * toca <b>ninguna</b> de sus filas: ponerlas a cero borraria algo que ninguna linea dijo. La
     * conciliacion de despues de reparar lo informa con lo que diga entonces, y lo repara una
     * pasada posterior. Se comparan como conjuntos: el orden de dos filas de la misma cuota con
     * distinta unidad no lo fija ninguna consulta.
     *
     * <h4>Por que a cero basta para que cuadre</h4>
     *
     * <p>{@link #conciliar} compara solo el insoluto, y el de una obligacion sin asientos es cero.
     * Una fila a cero de una obligacion que el libro no tiene <b>no</b> es una divergencia: dice lo
     * mismo que el libro. Asi lo cuentan los dos recorridos de {@link ReconstruirPadron} —el cursor
     * de los contribuyentes sin libro no los devuelve, y {@link #conciliar} no los informa—, y por
     * eso la fila reparada no vuelve a salir ni aunque su contribuyente estrene libro con otra
     * obligacion. La fase y el ultimo asiento de la fila se quedan como estaban: no hay ningun
     * asiento del que sacarlos, y la conciliacion no los mira.
     *
     * <h4>Y lo que NO arregla: el renglon del estado de cuenta</h4>
     *
     * <p>{@link ConsultarDeuda#porContribuyente} usa la proyeccion como <b>indice</b> —que
     * obligaciones tiene el contribuyente, y en que fase— y saca el importe del libro. Una fila sin
     * asientos sale alli como un renglon de importe cero con la fase de la fila, y se puede filtrar
     * por ella; y sigue saliendo igual despues de ponerla a cero, porque la fila se queda y su fase
     * tambien. Esta reparacion deja la proyeccion de acuerdo con el libro; ese renglon es otro
     * defecto, y no lo quita.
     *
     * <h4>{@code REPEATABLE READ}, por el primer asiento que llegue a la vez</h4>
     *
     * <p>La sentencia comprueba ella misma que el contribuyente sigue sin asientos. Con {@code READ
     * COMMITTED} eso no basta: si otra transaccion asienta su primer cargo y reproyecta la fila
     * <b>mientras</b> el {@code UPDATE} espera su candado, PostgreSQL vuelve a evaluar el {@code
     * WHERE} sobre la fila nueva pero con la instantanea de antes, en la que el asiento no existe,
     * y pone a cero un saldo que el libro ya respalda. Con una sola instantanea para toda la
     * transaccion, esa fila cambiada despues de tomarla hace fallar la escritura con {@code 40001}
     * en vez de pisarla: la municipalidad sale como no conciliada, y la siguiente pasada la ve con
     * su asiento. Y es la misma instantanea la que hace valer la comparacion de arriba: el {@code
     * UPDATE} ve exactamente lo que se comparo, una fila modificada despues lo hace fallar igual, y
     * una insertada despues no la ve.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public int ponerACeroSinLibro(long contribuyenteId, List<Divergencia> informadas) {
        // Desde dentro y sin pasar por el proxy, a proposito: la lectura tiene que ser de ESTA
        // transaccion, con su instantanea, y no de una propia.
        if (!Set.copyOf(conciliar(contribuyenteId)).equals(Set.copyOf(informadas))) {
            return 0;
        }
        return saldos.ponerACeroSinLibro(contribuyenteId, reloj.instant());
    }
}
